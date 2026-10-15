package dev.omalauncher.widgets.runtime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The phone's location, for `source l = android.location`, and `location(...)`
 * to ask for access. Android guards it behind a runtime permission the user
 * grants once; until then `granted` is false and the coordinates are null.
 */
class Location(
    private val context: Context,
) {
    private val manager = context.getSystemService(LocationManager::class.java)

    /** Whether the user has let the launcher read the phone's location. */
    val granted: Boolean
        get() =
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The phone's location switch (quick settings), which the user can turn off on its own. */
    private val enabled: Boolean get() = runCatching { manager.isLocationEnabled }.getOrDefault(false)

    /** The freshest cached fix, or null before anything has been located. */
    private fun lastKnown(): android.location.Location? =
        if (!granted) {
            null
        } else {
            runCatching { manager.getProviders(true) }
                .getOrNull()
                .orEmpty()
                .mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
        }

    fun value(): Value {
        val l = lastKnown()
        return Obj(
            mapOf(
                "available" to bool(granted && enabled),
                "granted" to bool(granted),
                "provider" to (l?.provider?.let(::Str) ?: Null),
                // Degrees, as the phone reports them; null until the first fix.
                "latitude" to (l?.let { Num(it.latitude) } ?: Null),
                "longitude" to (l?.let { Num(it.longitude) } ?: Null),
                "altitude" to (l?.takeIf { it.hasAltitude() }?.let { Num(it.altitude) } ?: Null),
                "accuracy" to (l?.takeIf { it.hasAccuracy() }?.let { Num(it.accuracy.toDouble()) } ?: Null),
                "speed" to (l?.takeIf { it.hasSpeed() }?.let { Num(it.speed.toDouble()) } ?: Null),
                "bearing" to (l?.takeIf { it.hasBearing() }?.let { Num(it.bearing.toDouble()) } ?: Null),
                // Unix seconds, like clock.
                "time" to (l?.let { Num(Math.floor(it.time / 1000.0)) } ?: Null),
            ),
        )
    }

    /**
     * Emits on every location change, and again when the permission is granted
     * or the phone's location switch is toggled, so a widget picks up access it
     * was granted after its page opened.
     */
    fun updates(): Flow<Value> =
        callbackFlow {
            // onLocationChanged is the one abstract method on API 30+, but the others are abstract on older
            // phones (the launcher supports Android 10), and a class that skips them can't be instantiated.
            val listener =
                object : LocationListener {
                    override fun onLocationChanged(location: android.location.Location) {
                        trySend(Unit)
                    }

                    override fun onProviderEnabled(provider: String) {}

                    override fun onProviderDisabled(provider: String) {}

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(
                        provider: String?,
                        status: Int,
                        extras: Bundle?,
                    ) {}
                }
            var listening = false

            fun listen() {
                if (listening || !granted) return
                listening =
                    runCatching {
                        manager.getProviders(true).forEach { p ->
                            manager.requestLocationUpdates(p, 60_000L, 0f, listener, Looper.getMainLooper())
                        }
                    }.isSuccess
            }
            listen()
            trySend(Unit)
            val ticker =
                launch {
                    var wasGranted = granted
                    var wasEnabled = enabled
                    while (true) {
                        // Long enough to be cheap, short enough to notice the permission or the switch change.
                        delay(5000)
                        val nowGranted = granted
                        val nowEnabled = enabled
                        if (nowGranted != wasGranted || nowEnabled != wasEnabled) {
                            wasGranted = nowGranted
                            wasEnabled = nowEnabled
                            listen()
                            trySend(Unit)
                        }
                    }
                }
            awaitClose {
                ticker.cancel()
                if (listening) runCatching { manager.removeUpdates(listener) }
            }
        }.conflate().map { value() }.distinctUntilChanged()

    /** location("request") asks for the permission, when the UI can show it; location("open") opens the app's settings. */
    fun control(
        command: String,
        request: (() -> Unit)?,
    ) {
        if (granted && command != "open") return
        when (command) {
            "request" -> if (request != null) request() else openSettings()
            "open" -> openSettings()
        }
    }

    /** The app's settings, where the user can grant (or revoke) the location permission. */
    private fun openSettings() {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
