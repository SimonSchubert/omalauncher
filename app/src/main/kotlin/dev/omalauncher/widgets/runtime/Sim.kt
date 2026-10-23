package dev.omalauncher.widgets.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The SIM and the mobile network, for `source s = android.sim`: whether there's a SIM, the network's name, the signal
 * and whether mobile data is on. All of it is what Android gives any app; which network type (LTE, 5G) and which SIM
 * takes calls would need the phone permission, so they're left out.
 */
class Sim(
    private val context: Context,
) {
    private val telephony: TelephonyManager? =
        context.takeIf { it.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) }?.getSystemService(TelephonyManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** The last signal Android reported, 0 to 4; read directly until a callback says. */
    @Volatile private var signal: Int? = null

    fun value(): Value {
        val t = telephony
        // Every field on every phone, null where there's nothing to say, so a widget reads one shape.
        val present = t != null && t.simState == TelephonyManager.SIM_STATE_READY
        // Without a SIM there's no network of its own, signal or data to speak of.
        val carrier = if (t != null && present) t.networkOperatorName.ifBlank { t.simOperatorName }.takeIf { it.isNotBlank() } else null
        val level = if (t != null && present) signal ?: runCatching { t.signalStrength?.level }.getOrNull() else null
        val data = if (present) mobileData() else null
        return Obj(
            mapOf(
                "available" to bool(t != null),
                "present" to bool(present),
                // The network it's on (another's name while roaming), else the SIM's own.
                "carrier" to (carrier?.let(::Str) ?: Null),
                "signal" to (level?.let { Num(it.toDouble()) } ?: Null),
                "data" to (data?.let(::bool) ?: Null),
                "roaming" to bool(t != null && present && runCatching { t.isNetworkRoaming }.getOrDefault(false)),
            ),
        )
    }

    /** Android's own setting, which its mobile data switch writes; null where an app may not read it. */
    private fun mobileData(): Boolean? = runCatching { Settings.Global.getInt(context.contentResolver, MOBILE_DATA) == 1 }.getOrNull()

    /** Emits as the signal, the network or the SIM change, or mobile data is switched. */
    fun updates(): Flow<Value> {
        val t = telephony ?: return callbackFlow<Unit> { trySend(Unit); awaitClose() }.map { value() }
        return callbackFlow {
            val onSignal: (SignalStrength) -> Unit = {
                signal = it.level
                trySend(Unit)
            }
            val unregister: () -> Unit =
                if (Build.VERSION.SDK_INT >= 31) {
                    val callback =
                        object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener, TelephonyCallback.ServiceStateListener {
                            override fun onSignalStrengthsChanged(s: SignalStrength) = onSignal(s)

                            override fun onServiceStateChanged(s: ServiceState) {
                                trySend(Unit)
                            }
                        }
                    t.registerTelephonyCallback(context.mainExecutor, callback)
                    ({ t.unregisterTelephonyCallback(callback) })
                } else {
                    @Suppress("DEPRECATION")
                    val listener =
                        object : PhoneStateListener() {
                            @Deprecated("Deprecated in Java")
                            override fun onSignalStrengthsChanged(s: SignalStrength) = onSignal(s)

                            @Deprecated("Deprecated in Java")
                            override fun onServiceStateChanged(s: ServiceState?) {
                                trySend(Unit)
                            }
                        }
                    @Suppress("DEPRECATION")
                    t.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_SERVICE_STATE)
                    @Suppress("DEPRECATION")
                    ({ t.listen(listener, PhoneStateListener.LISTEN_NONE) })
                }
            val observer =
                object : ContentObserver(main) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(Unit)
                    }
                }
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(MOBILE_DATA), true, observer)
            trySend(Unit)
            awaitClose {
                unregister()
                context.contentResolver.unregisterContentObserver(observer)
            }
        }.conflate().map { value() }.distinctUntilChanged()
    }

    private companion object {
        /** Settings.Global.MOBILE_DATA, which Android keeps hidden; with several SIMs, the default one's. */
        const val MOBILE_DATA = "mobile_data"
    }
}
