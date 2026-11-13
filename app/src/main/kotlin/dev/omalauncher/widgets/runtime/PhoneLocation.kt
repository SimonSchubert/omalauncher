package dev.omalauncher.widgets.runtime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.registerNotExported
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import dev.omalauncher.hasPermission

/**
 * Where the phone roughly is, for widgets: `source here = android.location` and `location("request")`. Approximate
 * only (ACCESS_COARSE_LOCATION, a kilometre or two), which is what weather, the nearest station or prayer times need.
 * Nothing is asked for until a widget's tap asks, and nothing is read until it's granted. Updates flow only while a
 * widget showing them is on screen, like every android source: no background location. Main thread only.
 */
class PhoneLocation(
    private val context: Context,
) {
    private val manager = context.getSystemService(LocationManager::class.java)

    /** Asks the activity in front for the location, then says whether it was granted; set as it resumes, like Dictation's. */
    var ask: ((granted: (Boolean) -> Unit) -> Unit)? = null

    /** State, so a source that read it ungranted starts listening the moment it's granted. */
    private var granted by mutableStateOf(check())

    private fun check() = context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Granted or taken back in Android's settings while away: each activity that shows widgets checks as it resumes. */
    fun recheck() {
        granted = check()
    }

    /** android.location: {granted, available, lat, lon, accuracy, time}, nulls until there's a fix. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun updates(): Flow<Value> = snapshotFlow { granted }.flatMapLatest { if (it) fixes() else flowOf(value(false, null)) }.distinctUntilChanged()

    /** location("request") from a tap: the permission dialog, or the app's settings once Android won't show it again. */
    fun request() {
        recheck()
        if (granted) return
        // A preview or a widget behind another app has no activity to ask from.
        ask?.invoke { recheck() }
    }

    /** Only while [granted]; taken back in the meantime, the calls throw, and runCatching makes that no fix. */
    @SuppressLint("MissingPermission")
    private fun fixes(): Flow<Value> =
        callbackFlow {
            var last: Location? = null

            fun send(fix: Location?) {
                // A provider's cached fix can be older than one already sent.
                if (fix != null && (last == null || fix.time >= last!!.time)) last = fix
                trySend(value(true, last?.takeIf { manager.isLocationEnabled }))
            }

            // An object, not a lambda: before Android 11 the other three methods have no default, and a call to one of
            // them would find nothing there.
            val listener =
                object : LocationListener {
                    override fun onLocationChanged(location: Location) = send(location)

                    override fun onProviderEnabled(provider: String) = send(null)

                    override fun onProviderDisabled(provider: String) = send(null)

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(
                        provider: String?,
                        status: Int,
                        extras: Bundle?,
                    ) = Unit
                }
            val provider = provider()
            send(lastKnown())
            if (provider != null) {
                // Coarse fixes move in steps of a kilometre or so: every few minutes is plenty, and lets the phone batch.
                runCatching { manager.requestLocationUpdates(provider, EVERY_MS, 0f, listener, Looper.getMainLooper()) }
            }
            // The phone's location switch, which hides the fix while it's off and brings it back as it's turned on.
            val receiver = context.registerNotExported(IntentFilter(LocationManager.MODE_CHANGED_ACTION)) { send(lastKnown()) }
            awaitClose {
                runCatching { manager.removeUpdates(listener) }
                context.unregisterReceiver(receiver)
            }
        }

    /** Android's fused provider where it has one (Wi-Fi, cells and GPS together, coarsened for us), else the network's. */
    private fun provider(): String? {
        val fused = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(LocationManager.FUSED_PROVIDER) else emptyList()
        val all = runCatching { manager.allProviders }.getOrDefault(emptyList())
        return (fused + LocationManager.NETWORK_PROVIDER + LocationManager.PASSIVE_PROVIDER).firstOrNull { it in all }
    }

    /** The newest fix any provider has cached, so a widget has a place at once rather than after the next one. */
    @SuppressLint("MissingPermission")
    private fun lastKnown(): Location? =
        runCatching { manager.allProviders }
            .getOrDefault(emptyList())
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

    private fun value(
        granted: Boolean,
        fix: Location?,
    ): Value =
        Obj(
            mapOf(
                "granted" to bool(granted),
                "available" to bool(fix != null),
                // Degrees.
                "lat" to (fix?.let { Num(it.latitude) } ?: Null),
                "lon" to (fix?.let { Num(it.longitude) } ?: Null),
                // Metres; a coarse fix says a kilometre or more.
                "accuracy" to (fix?.takeIf { it.hasAccuracy() }?.let { Num(it.accuracy.toDouble()) } ?: Null),
                // Unix seconds, like clock.
                "time" to (fix?.let { Num((it.time / 1000).toDouble()) } ?: Null),
            ),
        )

    private companion object {
        const val EVERY_MS = 5 * 60_000L
    }
}
