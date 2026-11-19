package dev.omalauncher.widgets.runtime

import dev.omalauncher.system.changeObserver
import dev.omalauncher.system.hasPermission
import dev.omalauncher.system.registerNotExported
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import dev.omalauncher.widgets.lang.num
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The volume, for `source v = android.volume`: the media stream's at the top, the others under their names, and where
 * the sound goes, with a Bluetooth device's battery once the launcher may use Bluetooth; and `volume(...)` to change one.
 */
class Volume(
    private val context: Context,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val router = context.getSystemService(MediaRouter::class.java)
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Bluetooth connect, a runtime permission since Android 12, which the battery needs; before it, an install-time one. */
    private val granted: Boolean
        get() = Build.VERSION.SDK_INT < 31 || context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    /** The grant as last read, so a change in Android's settings or a dialog reads the value again. */
    private val allowed = MutableStateFlow(granted)

    /**
     * Asks the activity in front for Bluetooth, then says whether it was granted; set as it resumes, like AirPods'.
     * Null behind another app, so nothing asks over it.
     */
    var ask: ((granted: (Boolean) -> Unit) -> Unit)? = null

    /** Granted or taken back in Android's settings while away: each activity that shows widgets checks as it resumes. */
    fun recheck() {
        allowed.value = granted
    }

    private fun level(stream: Int): Map<String, Value> {
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        val now = audio.getStreamVolume(stream)
        return mapOf(
            "level" to Num((now * 100.0 / max).roundToInt().toDouble()),
            "muted" to bool(audio.isStreamMute(stream)),
            // The phone's own steps: a slider with step: 100 / steps lands on each.
            "steps" to Num(max.toDouble()),
        )
    }

    fun value(): Value =
        Obj(level(AudioManager.STREAM_MUSIC) + STREAMS.filterKeys { it != "media" }.mapValues { (_, s) -> Obj(level(s)) } + ("output" to output()))

    /**
     * Where media plays: `{type, name, battery, granted}`. Android has no public way to ask which output a stream uses,
     * so it's what's plugged in, in the order Android routes media to them, else the speaker. A Bluetooth device's name
     * comes from the media router's selected route when that is Bluetooth, as Android shows it. [name] is null where
     * there's none, and the battery wherever the device doesn't report one, as Android's Bluetooth settings then show
     * none either.
     */
    private fun output(): Value {
        val plugged = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { d -> OUTPUTS[d.type]?.let { it to d } }
        val (type, device) = plugged.minByOrNull { (type, _) -> ORDER.indexOf(type) } ?: return output("speaker", null)
        if (type == "bluetooth") {
            val battery = battery(device)
            val route = runCatching { router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO) }.getOrNull()
            if (route?.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH) return output(type, route.name?.toString() ?: name(device), battery)
            return output(type, name(device), battery)
        }
        return output(type, name(device))
    }

    private fun output(
        type: String,
        name: String?,
        battery: Int? = null,
    ) = Obj(
        mapOf(
            "type" to Str(type),
            "name" to (name?.takeIf { it.isNotBlank() }?.let(::Str) ?: Null),
            "battery" to (battery?.let { Num(it.toDouble()) } ?: Null),
            "granted" to bool(granted),
        ),
    )

    /**
     * The battery a Bluetooth output last reported, in percent, as Android's Bluetooth settings show it: from the
     * hands-free link's battery reports or the LE battery service. BluetoothDevice.getBatteryLevel is a system API,
     * open to apps' reflection, that only needs Bluetooth connect. Null without it, for a device that doesn't say, and
     * where the output's address isn't a Bluetooth one.
     */
    private fun battery(device: AudioDeviceInfo): Int? {
        if (!granted) return null
        val address = device.address?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) } ?: return null
        val method = batteryLevel ?: return null
        return runCatching { method.invoke(adapter?.getRemoteDevice(address) ?: return null) as Int }
            .onFailure { Log.w(TAG, "no battery: ${it.message}") }
            .getOrNull()
            ?.takeIf { it in 0..100 }
    }

    // A wired headset's product name is the phone's own model on most phones, which says nothing.
    private fun name(d: AudioDeviceInfo) = d.productName?.toString()?.takeUnless { it == Build.MODEL }

    /** Emits whenever the volume or mute changes, from any app or the hardware buttons. */
    fun updates(): Flow<Value> =
        callbackFlow {
            val main = Handler(Looper.getMainLooper())
            // Not public API, but sent by every Android version; the settings observer below covers phones that don't.
            val filter =
                IntentFilter().apply {
                    addAction("android.media.VOLUME_CHANGED_ACTION")
                    addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
                }
            val receiver = context.registerNotExported(filter) { trySend(Unit) }
            // Bluetooth's own process sends it, not the system's, so a receiver that isn't exported would miss it; it's a
            // protected broadcast, which no app can send. It only reaches apps that may use Bluetooth: until then, nothing.
            val batteries =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        c: Context,
                        intent: Intent,
                    ) {
                        trySend(Unit)
                    }
                }
            val batteryFilter = IntentFilter(ACTION_BATTERY_LEVEL_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(batteries, batteryFilter, Context.RECEIVER_EXPORTED) else context.registerReceiver(batteries, batteryFilter)
            // Bluetooth allowed or taken back: the battery comes or goes.
            val grants = launch { allowed.collect { trySend(Unit) } }
            val observer = changeObserver(main) { trySend(Unit) }
            context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
            // Headphones plugged in or out, and an output picked in Android's switcher.
            val devices =
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
                        trySend(Unit)
                    }

                    override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
                        trySend(Unit)
                    }
                }
            audio.registerAudioDeviceCallback(devices, main)
            val routes =
                object : MediaRouter.SimpleCallback() {
                    override fun onRouteSelected(
                        router: MediaRouter,
                        type: Int,
                        info: MediaRouter.RouteInfo,
                    ) {
                        trySend(Unit)
                    }

                    override fun onRouteChanged(
                        router: MediaRouter,
                        info: MediaRouter.RouteInfo,
                    ) {
                        trySend(Unit)
                    }
                }
            // The router calls back on the thread that added the callback's looper: the main one.
            main.post { router.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, routes) }
            trySend(Unit)
            awaitClose {
                context.unregisterReceiver(receiver)
                context.unregisterReceiver(batteries)
                grants.cancel()
                context.contentResolver.unregisterContentObserver(observer)
                audio.unregisterAudioDeviceCallback(devices)
                main.post { router.removeCallback(routes) }
            }
        }.conflate().map { value() }.distinctUntilChanged()

    /**
     * volume(50) sets the level in percent; "up", "down", "mute", "unmute" and "toggle" step or mute it. [name] picks
     * the stream, media when it's null. volume("request") from a tap asks for Bluetooth, for the output's battery.
     */
    fun control(
        arg: Value,
        name: String? = null,
    ) {
        if (arg.toString() == "request") {
            if (!granted) ask?.invoke { recheck() }
            return
        }
        val stream = STREAMS[name ?: "media"] ?: return
        // Do Not Disturb refuses changes to the ringer while it's on; a widget tap mustn't crash the launcher over it.
        runCatching {
            val level = arg.num()
            if (level != null) {
                val max = audio.getStreamMaxVolume(stream)
                audio.setStreamVolume(stream, (level / 100 * max).roundToInt().coerceIn(0, max), 0)
                return
            }
            val direction =
                when (arg.toString()) {
                    "up" -> AudioManager.ADJUST_RAISE
                    "down" -> AudioManager.ADJUST_LOWER
                    "mute" -> AudioManager.ADJUST_MUTE
                    "unmute" -> AudioManager.ADJUST_UNMUTE
                    "toggle" -> AudioManager.ADJUST_TOGGLE_MUTE
                    else -> return
                }
            audio.adjustStreamVolume(stream, direction, 0)
        }
    }

    private companion object {
        const val TAG = "volume"

        /** BluetoothDevice's, a system API rather than a public one: the same in every Android since 8.1. */
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"

        val batteryLevel by lazy {
            runCatching { BluetoothDevice::class.java.getMethod("getBatteryLevel") }
                .onFailure { Log.w(TAG, "no getBatteryLevel: ${it.message}") }
                .getOrNull()
        }

        /** The outputs `output.type` names, by Android's device types; earpieces and the like aren't for media. */
        val OUTPUTS =
            mapOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "bluetooth",
                AudioDeviceInfo.TYPE_BLE_HEADSET to "bluetooth",
                AudioDeviceInfo.TYPE_BLE_SPEAKER to "bluetooth",
                AudioDeviceInfo.TYPE_HEARING_AID to "bluetooth",
                AudioDeviceInfo.TYPE_WIRED_HEADSET to "wired",
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES to "wired",
                AudioDeviceInfo.TYPE_USB_HEADSET to "usb",
                AudioDeviceInfo.TYPE_USB_DEVICE to "usb",
                AudioDeviceInfo.TYPE_HDMI to "hdmi",
                AudioDeviceInfo.TYPE_DOCK to "dock",
            )

        /** Which wins when several are plugged in, as Android routes media. */
        val ORDER = listOf("bluetooth", "wired", "usb", "hdmi", "dock")

        /** By the words in owl's VOLUME_STREAMS. */
        val STREAMS =
            mapOf(
                "media" to AudioManager.STREAM_MUSIC,
                "ring" to AudioManager.STREAM_RING,
                "notification" to AudioManager.STREAM_NOTIFICATION,
                "alarm" to AudioManager.STREAM_ALARM,
            )
    }
}
