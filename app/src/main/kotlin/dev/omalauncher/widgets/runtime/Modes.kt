package dev.omalauncher.widgets.runtime

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.database.ContentObserver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import dev.omalauncher.widgets.lang.Bool
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import dev.omalauncher.widgets.lang.num
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The phone's modes, for `source m = android.modes`: Do Not Disturb, battery saver, the ringer, the torch and how bright
 * it shines, the radios, location, rotation, dark theme and brightness; `torch(...)` to switch or dim the torch, and
 * `dnd(...)` Do Not Disturb, which notification access lets the launcher switch as well as read. None of the rest needs
 * a permission:
 * the radios, rotation and brightness are read from Android's settings, which any app may read, not from their managers.
 */
class Modes(
    private val context: Context,
) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val cameras = context.getSystemService(CameraManager::class.java)
    private val location = context.getSystemService(LocationManager::class.java)
    private val resolver = context.contentResolver

    /** The camera with a flash, the back one if there are several; null on phones without one. */
    private val flash: String? by lazy {
        runCatching {
            val ids = cameras.cameraIdList.filter { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ids.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } ?: ids.firstOrNull()
        }.getOrNull()
    }

    /** The flash's characteristics, read once: they don't change. */
    private val flashInfo by lazy { flash?.let { runCatching { cameras.getCameraCharacteristics(it) }.getOrNull() } }

    /** How many brightness levels the torch has: 1 where it can't be dimmed, as before Android 13. */
    private val torchSteps: Int by lazy {
        (if (Build.VERSION.SDK_INT >= 33) runCatching { flashInfo?.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) }.getOrNull() else null) ?: 1
    }

    /** The level it shines at unless told otherwise. */
    private val torchDefault: Int by lazy {
        (if (Build.VERSION.SDK_INT >= 33) runCatching { flashInfo?.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL) }.getOrNull() else null)?.takeIf { it in 1..torchSteps } ?: torchSteps
    }

    private val dimmable get() = Build.VERSION.SDK_INT >= 33 && torchSteps > 1

    /** Whether the torch is on, as Android last said; it says so as soon as a callback is registered. */
    @Volatile private var torchOn = false

    /** The level it shines at while on, which another app may have picked; 0 until it was on. */
    @Volatile private var torchStrength = 0

    /** Listeners waiting for torch changes; the torch callback is registered while there are any, or once torch(...) ran. */
    private val torchListeners = mutableSetOf<() -> Unit>()
    private var torchWatched = false
    private val torchCallback =
        object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(
                cameraId: String,
                enabled: Boolean,
            ) {
                if (cameraId != flash) return
                torchOn = enabled
                if (enabled && dimmable) torchStrength = runCatching { cameras.getTorchStrengthLevel(cameraId) }.getOrDefault(0)
                synchronized(torchListeners) { torchListeners.toList() }.forEach { it() }
            }

            override fun onTorchStrengthLevelChanged(
                cameraId: String,
                newStrengthLevel: Int,
            ) {
                if (cameraId != flash) return
                torchStrength = newStrengthLevel
                synchronized(torchListeners) { torchListeners.toList() }.forEach { it() }
            }
        }

    private val main = Handler(Looper.getMainLooper())

    private fun watchTorch() {
        if (torchWatched || flash == null) return
        torchWatched = true
        // Kept for the process: unregistering would lose what the torch is doing, and a callback costs nothing.
        cameras.registerTorchCallback(torchCallback, main)
    }

    /** Off means every notification gets through; anything else is Do Not Disturb in some form. */
    private fun dndOn() = dndAllows() != "all"

    /** What gets through Do Not Disturb: "all" (it's off), "priority" (the user's exceptions), "alarms" or "none". */
    private fun dndAllows() =
        when (notifications.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
            else -> "all"
        }

    /** The levels dnd(...) and torch(...) picked last, which turning them on uses again. */
    private val prefs = context.getSharedPreferences("modes", Context.MODE_PRIVATE)

    fun value(): Value =
        Obj(
            mapOf(
                "dnd" to bool(dndOn()),
                "dndAllows" to Str(dndAllows()),
                "saver" to bool(power.isPowerSaveMode),
                "ringer" to
                    Str(
                        when (audio.ringerMode) {
                            AudioManager.RINGER_MODE_SILENT -> "silent"
                            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                            else -> "normal"
                        },
                    ),
                "torch" to if (flash == null) Null else bool(torchOn),
                // In percent: what it shines at while on, what torch(...) turns it on at while off; null if it can't be dimmed.
                "torchLevel" to if (dimmable) Num(((if (torchOn && torchStrength > 0) torchStrength else torchPicked()) * 100.0 / torchSteps).roundToInt().toDouble()) else Null,
                "airplane" to bool(global(Settings.Global.AIRPLANE_MODE_ON) == 1),
                // 2 is Wi-Fi left on in airplane mode.
                "wifi" to bool(global(Settings.Global.WIFI_ON).let { it == 1 || it == 2 }),
                "bluetooth" to bool(global(Settings.Global.BLUETOOTH_ON) == 1),
                "location" to bool(location.isLocationEnabled),
                "rotate" to bool(system(Settings.System.ACCELEROMETER_ROTATION) == 1),
                // The system's, not the launcher's own: Resources.getSystem() isn't themed by the app.
                "dark" to bool(Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES),
                // As Android stores it, in percent of its maximum: the settings slider is curved, so the two can differ.
                "brightness" to (system(Settings.System.SCREEN_BRIGHTNESS)?.let { Num((it.coerceIn(0, maxBrightness) * 100.0 / maxBrightness).roundToInt().toDouble()) } ?: Null),
                "autoBrightness" to bool(system(Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC),
            ),
        )

    /** 255 on most phones; some store more steps, and say so in the framework's own config. */
    private val maxBrightness: Int by lazy {
        val res = Resources.getSystem()
        @Suppress("DiscouragedApi")
        val id = res.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        (if (id != 0) runCatching { res.getInteger(id) }.getOrNull() else null)?.takeIf { it > 0 } ?: 255
    }

    private fun global(name: String) = runCatching { Settings.Global.getInt(resolver, name) }.getOrNull()

    private fun system(name: String) = runCatching { Settings.System.getInt(resolver, name) }.getOrNull()

    /** Emits whenever one of the modes changes, from anywhere: quick settings, a schedule, another app. */
    fun updates(): Flow<Value> =
        callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        c: Context,
                        intent: Intent,
                    ) {
                        trySend(Unit)
                    }
                }
            val filter =
                IntentFilter().apply {
                    addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                    addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                    addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                    addAction(LocationManager.MODE_CHANGED_ACTION)
                    // Dark theme turning on or off changes the configuration.
                    addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, filter)
            // The radios, rotation and brightness send no broadcast an app without permissions may hear; their settings change.
            val observer =
                object : ContentObserver(main) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(Unit)
                    }
                }
            for (name in listOf(Settings.Global.AIRPLANE_MODE_ON, Settings.Global.WIFI_ON, Settings.Global.BLUETOOTH_ON)) {
                resolver.registerContentObserver(Settings.Global.getUriFor(name), false, observer)
            }
            for (name in listOf(Settings.System.ACCELEROMETER_ROTATION, Settings.System.SCREEN_BRIGHTNESS, Settings.System.SCREEN_BRIGHTNESS_MODE)) {
                resolver.registerContentObserver(Settings.System.getUriFor(name), false, observer)
            }
            val onTorch: () -> Unit = { trySend(Unit) }
            synchronized(torchListeners) { torchListeners += onTorch }
            watchTorch()
            trySend(Unit)
            awaitClose {
                context.unregisterReceiver(receiver)
                resolver.unregisterContentObserver(observer)
                synchronized(torchListeners) { torchListeners -= onTorch }
            }
        }.conflate().map { value() }.distinctUntilChanged()

    /**
     * dnd(true), dnd(false), or dnd("on"), "off", "toggle": Do Not Disturb as the quick settings tile switches it, letting
     * through what the user's settings let through, or what dnd(...) picked last; dnd("priority"), "alarms" and "none"
     * pick what gets through and turn it on, and "all" turns it off, the words dndAllows reads. "none" isn't kept for
     * next time: a plain tap turning on total silence again, alarms too, could cost someone their alarm. A notification
     * listener may; false without notification access.
     * Since Android 15 an app's switch is a mode of its own, which ends only its own Do Not Disturb: one the user turned
     * on stays, and [stays] runs, for Android's own settings.
     */
    fun dnd(
        arg: Value,
        stays: () -> Unit,
    ): Boolean {
        val listener = MediaListener.connected ?: return false
        val word = arg.toString()
        val level = arg !is Bool && (word == "priority" || word == "alarms" || word == "none")
        if (level && word != "none") prefs.edit().putString("dnd", word).apply()
        val on =
            when {
                arg is Bool -> arg.v
                word == "on" || level -> true
                word == "off" || word == "all" -> false
                word == "toggle" -> !dndOn()
                else -> return true
            }
        val filter =
            when {
                !on -> NotificationListenerService.INTERRUPTION_FILTER_ALL
                else ->
                    when (if (word == "none") word else prefs.getString("dnd", null)) {
                        "alarms" -> NotificationListenerService.INTERRUPTION_FILTER_ALARMS
                        "none" -> NotificationListenerService.INTERRUPTION_FILTER_NONE
                        else -> NotificationListenerService.INTERRUPTION_FILTER_PRIORITY
                    }
            }
        runCatching { listener.requestInterruptionFilter(filter) }.onFailure { Log.w("widgets", "dnd: ${it.message}") }
        // Android applies it at once, but says so through the listener's thread: a moment later, it's either off or stays.
        if (!on) main.postDelayed({ if (dndOn()) stays() }, 500)
        return true
    }

    /** The level torch(percent) picked last, which turning it on uses again; the phone's own default before that. */
    private fun torchPicked(): Int = prefs.getInt("torch", 0).takeIf { it in 1..torchSteps } ?: torchDefault

    /**
     * torch(true), torch(false), or torch("on"), "off", "toggle"; torch(40) turns it on at 40% of its brightness, and on
     * and toggle use that level from then on. Phones before Android 13, and some after, have one level: any number but 0
     * is just on. Does nothing on a phone without a flash.
     */
    fun torch(arg: Value) {
        val id = flash ?: return
        watchTorch()
        // After the callback's first call, which registering it queued on the same looper: toggle needs to know the state.
        main.post {
            val percent = if (arg is Bool) null else arg.num()
            if (percent != null && percent > 0 && dimmable) {
                prefs.edit().putInt("torch", ceil(percent.coerceAtMost(100.0) / 100 * torchSteps).toInt().coerceIn(1, torchSteps)).apply()
            }
            val on =
                when {
                    arg is Bool -> arg.v
                    percent != null -> percent > 0
                    arg.toString() == "on" -> true
                    arg.toString() == "off" -> false
                    arg.toString() == "toggle" -> !torchOn
                    else -> return@post
                }
            // The camera app holding the camera refuses the torch; a widget tap mustn't crash the launcher over it.
            runCatching {
                if (on && dimmable) cameras.turnOnTorchWithStrengthLevel(id, torchPicked()) else cameras.setTorchMode(id, on)
            }.onFailure { Log.w("widgets", "torch: ${it.message}") }
        }
    }
}
