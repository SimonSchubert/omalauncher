package dev.omalauncher.linux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.File

/** Publishes the Android-owned power/default-network state into the guest runtime bind. */
internal class PortalSystemStatus(context: Context, private val runtimeDir: File) {
    private val context = context.applicationContext ?: context
    private val lock = Any()
    private val ownership = PortalStatusOwnership()
    private var receiver: BroadcastReceiver? = null
    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var started = false
    private var lastPublished: String? = null

    /** Call from runtime IO startup. Registration, initial queries and writes serialize with callbacks. */
    fun start() = synchronized(lock) {
        if (started) return@synchronized
        started = true
        val token = ownership.start()
        // Replace any snapshot left by a previous run before consulting Android services.
        publish(force = true)

        val power = tryService { context.getSystemService(PowerManager::class.java) }
        val powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) return
                synchronized(lock) {
                    if (ownership.accepts(token)) {
                        ownership.power(token, queryPower(power))
                        publish()
                    }
                }
            }
        }
        try {
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(powerReceiver, filter)
            }
            receiver = powerReceiver
        } catch (e: Exception) {
            Log.w(TAG, "Power receiver unavailable", e)
        }
        // A failed receiver registration cannot keep an initial power reading fresh.
        ownership.power(token, if (receiver != null) queryPower(power) else null)
        publish()

        val manager = tryService { context.getSystemService(ConnectivityManager::class.java) }
        if (manager != null) {
            var callbackLive = true // cleared under lock if registration fails partially
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = synchronized(lock) {
                    if (callbackLive && ownership.accepts(token)) {
                        ownership.available(token, network)
                        publish()
                    }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(lock) {
                    if (callbackLive && ownership.accepts(token)) {
                        ownership.capabilities(token, network, status(capabilities))
                        publish()
                    }
                }

                override fun onLost(network: Network) = synchronized(lock) {
                    if (callbackLive && ownership.accepts(token)) {
                        ownership.lost(token, network)
                        publish()
                    }
                }
            }
            try {
                manager.registerDefaultNetworkCallback(callback)
                connectivity = manager
                networkCallback = callback
                // Register first, then query. Any callback queued while querying runs after the lock;
                // if it has already been observed, the pure ownership policy ignores the query.
                try {
                    val network = manager.activeNetwork
                    val initial = if (network == null) null else manager.getNetworkCapabilities(network)?.let(::status)
                    ownership.initialNetwork(token, network, initial)
                    publish()
                } catch (e: Exception) {
                    Log.w(TAG, "Initial default-network query unavailable", e)
                    // Unknown, not a fabricated offline status.
                }
            } catch (e: Exception) {
                Log.w(TAG, "Default-network callback unavailable", e)
                callbackLive = false
                // A failed registration can have partially succeeded; release it if necessary.
                try { manager.unregisterNetworkCallback(callback) } catch (_: Exception) { }
            }
        }
    }

    /** Call from teardown. Old callbacks remain harmless even if Android delivers them after unregister. */
    fun stop() = synchronized(lock) {
        if (!started) return@synchronized
        started = false
        ownership.stop()
        networkCallback?.let { callback ->
            try { connectivity?.unregisterNetworkCallback(callback) } catch (e: Exception) {
                Log.w(TAG, "Default-network unregister failed", e)
            }
        }
        networkCallback = null
        connectivity = null
        receiver?.let { registered ->
            try { context.unregisterReceiver(registered) } catch (e: Exception) {
                Log.w(TAG, "Power receiver unregister failed", e)
            }
        }
        receiver = null
        try { PortalStatusFile.remove(runtimeDir) } catch (e: Exception) {
            Log.w(TAG, "Could not remove status snapshot", e)
        }
        lastPublished = null
    }

    private fun queryPower(power: PowerManager?): Boolean? =
        try { power?.isPowerSaveMode } catch (e: Exception) {
            Log.w(TAG, "Power saver query unavailable", e)
            null
        }

    private fun <T> tryService(query: () -> T?): T? = try { query() } catch (e: Exception) {
        Log.w(TAG, "Status system service unavailable", e)
        null
    }

    private fun publish(force: Boolean = false) {
        val snapshot = ownership.snapshot
        val text = snapshot.text()
        if (!force && text == lastPublished) return
        try {
            PortalStatusFile.write(runtimeDir, snapshot)
            lastPublished = text
        } catch (e: Exception) {
            Log.w(TAG, "Could not publish status snapshot", e)
        }
    }

    private companion object {
        const val TAG = "PortalSystemStatus"

        fun status(caps: NetworkCapabilities): PortalNetworkStatus = PortalNetworkStatus.fromCapabilities(
            internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }
}
