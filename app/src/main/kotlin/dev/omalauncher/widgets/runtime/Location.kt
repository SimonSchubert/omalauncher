package dev.omalauncher.widgets.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The phone's location, for `source l = android.location`. Approximate
 * (coarse) location: enough to place the weather, and the only location
 * permission the launcher asks for. `available` is false until the user
 * grants it, so a widget can fall back to a fixed place or the IP-based
 * wttr.in lookup.
 */
class Location(
    private val context: Context,
) {
    private val manager = context.getSystemService(LocationManager::class.java)

    /** Set while a widget wants the location without the permission; the home screen asks for it. */
    val wanted = MutableStateFlow(false)

    /** Whether the launcher may read the phone's location. */
    val granted: Boolean
        get() = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Re-checked after the home screen's permission dialog, so a grant starts the updates. */
    private val access = MutableStateFlow(granted)

    fun refresh() {
        access.value = granted
    }

    /** The freshest fix Android already has, or `{available: false}`. Never triggers a new fix. */
    fun value(): Value {
        if (!granted) {
            wanted.value = true
            return unavailable()
        }
        val fix = best() ?: return unavailable()
        return Obj(
            mapOf(
                "available" to bool(true),
                "lat" to Num(fix.latitude),
                "lon" to Num(fix.longitude),
                "accuracy" to Num(fix.accuracy.toDouble()),
                "altitude" to if (fix.hasAltitude()) Num(fix.altitude) else Null,
                "time" to Num(fix.time / 1000.0),
            ),
        )
    }

    /** Emits the current fix and every change while a widget listens; stays open across a permission change. */
    fun updates(): Flow<Value> =
        callbackFlow {
            var listener: LocationListener? = null
            fun stop() {
                listener?.let { runCatching { manager.removeUpdates(it) } }
                listener = null
            }
            fun start() {
                if (listener != null || !granted) return
                // An explicit object, not a SAM lambda: at minSdk 29 the provider callbacks are still
                // abstract, and the framework calls them when location is toggled (AbstractMethodError).
                val l =
                    object : LocationListener {
                        override fun onLocationChanged(location: android.location.Location) {
                            trySend(value())
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

                        override fun onProviderEnabled(provider: String) {}

                        override fun onProviderDisabled(provider: String) {}
                    }
                listener = l
                runCatching { manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, UPDATE_MS, 0f, l, Looper.getMainLooper()) }
                trySend(value())
            }
            val watching =
                launch {
                    access.collect { ok ->
                        if (ok) {
                            start()
                        } else {
                            stop()
                            trySend(unavailable())
                        }
                    }
                }
            if (granted) start() else {
                wanted.value = true
                trySend(unavailable())
            }
            awaitClose {
                watching.cancel()
                stop()
            }
        }.distinctUntilChanged()

    private fun unavailable() =
        Obj(
            mapOf(
                "available" to bool(false),
                "lat" to Null,
                "lon" to Null,
                "accuracy" to Null,
                "altitude" to Null,
                "time" to Null,
            ),
        )

    /** The freshest fix among the enabled providers; a provider that won't answer is skipped. */
    private fun best(): android.location.Location? =
        runCatching {
            providers()
                .mapNotNull { p -> if (manager.isProviderEnabled(p)) runCatching { manager.getLastKnownLocation(p) }.getOrNull() else null }
                .maxByOrNull { it.time }
        }.getOrNull()

    private fun providers(): List<String> =
        buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }

    private companion object {
        /** At most one fix a minute: a listener fires on a real change, not a tight poll. */
        const val UPDATE_MS = 60_000L
    }
}
