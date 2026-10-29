package dev.omalauncher.widgets.runtime

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import dev.omalauncher.widgets.lang.Bool
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.num
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * AirPods, for `source a = android.airpods`: each bud's and the case's battery, which buds are in, the listening mode and
 * the settings, as AirPods tell Apple's own devices over AAP ([Aap]); and `airpods(...)` to change them. The link is up
 * while a widget reads the source and for a minute after, so turning pages doesn't set it up anew each time.
 *
 * Android has no public way to open it: AAP is a classic L2CAP channel, and the public API only makes RFCOMM and LE
 * sockets. So it's made with BluetoothSocket's own constructor, as Android's hidden createL2capSocket does, which the
 * hidden API checks let the launcher reach only once aap.c has exempted it. From Android 16 QPR3 on, Android's stack
 * lets that channel through; before, it needs root, and `connected` stays false.
 */
class AirPods(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val state = MutableStateFlow(AapState())
    private val allowed = MutableStateFlow(granted)

    /** The name Android shows for the AirPods, which the user may have changed. */
    @Volatile private var alias: String? = null

    /** The link while it's up, for airpods(...) to write to. */
    @Volatile private var link: BluetoothSocket? = null

    /** Set once this Android won't make the socket at all, which no retry changes. */
    @Volatile private var unsupported = false

    /** Something worth looking for AirPods again for: one connecting or leaving, Bluetooth on, the permission, "connect". */
    private val nudges = Channel<Unit>(Channel.CONFLATED)

    /** The profiles that say what's connected, once Android has handed them over. */
    private val profiles = HashMap<Int, BluetoothProfile>()

    /** Bluetooth connect, a runtime permission since Android 12; before it, an install-time one. */
    val granted: Boolean
        get() = Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun value(): Value = state.value.value(granted, alias)

    private val shared: Flow<Value> =
        channelFlow {
            launch(Dispatchers.IO) { keep() }
            combine(state, allowed) { s, a -> s.value(a, alias) }.collect { send(it) }
        }.distinctUntilChanged()
            // A stale "connected" mustn't come back when a page shows again: the link is set up anew then.
            .shareIn(scope, SharingStarted.WhileSubscribed(LINGER_MS, 0), replay = 1)

    fun updates(): Flow<Value> = shared

    /** Looks for AirPods again now: after the permission is granted, or for airpods("connect"). */
    fun connect() {
        allowed.value = granted
        nudges.trySend(Unit)
    }

    /**
     * airpods("anc") and the other listening modes, airpods("next") for the next of the modes this pair has, and
     * airpods("conversation" / "oneBud" / "earDetection", on) and airpods("adaptiveLevel", 0 to 100) for the settings;
     * a setting without a value turns over. What the AirPods don't have, or a pair not connected, does nothing.
     */
    fun control(
        word: String,
        arg: Value?,
    ) {
        if (word == "connect") return connect()
        val s = state.value
        val current = s.controls[Aap.MODE]?.let { Aap.MODES.getOrNull(it - 1) }
        fun mode(m: String) = if (m in s.modes) Aap.control(Aap.MODE, Aap.MODES.indexOf(m) + 1) else null
        fun turn(control: Int) =
            if (!s.has(control)) {
                null
            } else {
                val on = if (arg == null || arg == Null) s.controls[control] != 1 else (arg as? Bool)?.v ?: arg.truthy
                Aap.control(control, if (on) 1 else 2)
            }
        val packet =
            when (word) {
                in Aap.MODES -> mode(word)
                "next" -> s.modes.takeIf { it.isNotEmpty() }?.let { m -> mode(m[(m.indexOf(current) + 1) % m.size]) }
                "conversation" -> turn(Aap.CONVERSATION)
                "oneBud" -> turn(Aap.ONE_BUD)
                "earDetection" -> turn(Aap.EAR_DETECTION)
                "adaptiveLevel" -> arg?.num()?.takeIf { s.has(Aap.ADAPTIVE_LEVEL) }?.let { Aap.control(Aap.ADAPTIVE_LEVEL, it.toInt().coerceIn(0, 100)) }
                else -> null
            } ?: return
        val socket = link ?: return
        // The AirPods answer a new listening mode with the same packet, but take the other settings without a word:
        // what they were sent stands for their answer, on the link it went out on.
        scope.launch(Dispatchers.IO) {
            runCatching { send(socket, packet) }.onSuccess { if (link === socket) state.update { it.read(packet) } }
        }
    }

    /** Keeps a link to the AirPods connected to the phone, while the source is read. */
    @SuppressLint("MissingPermission")
    private suspend fun keep() =
        coroutineScope {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        c: Context,
                        intent: Intent,
                    ) {
                        nudges.trySend(Unit)
                    }
                }
            val filter =
                IntentFilter().apply {
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                    addAction(A2DP_CONNECTION)
                    addAction(HEADSET_CONNECTION)
                }
            // Bluetooth's own process sends these, not the system's, so a receiver that isn't exported would miss them;
            // they're protected broadcasts, which no app can send.
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else context.registerReceiver(receiver, filter)
            val listener =
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(
                        profile: Int,
                        proxy: BluetoothProfile,
                    ) {
                        synchronized(profiles) { profiles[profile] = proxy }
                        nudges.trySend(Unit)
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        synchronized(profiles) { profiles.remove(profile) }
                    }
                }
            for (p in PROFILES) runCatching { adapter?.getProfileProxy(context, listener, p) }
            try {
                // How long to wait before trying a pair again whose link failed: longer each time, until something changes.
                var backoff = 0
                while (true) {
                    allowed.value = granted
                    val device = if (granted && !unsupported) find() else null
                    if (device != null) {
                        val heard =
                            try {
                                session(device)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "AirPods link: ${e.message}")
                                false
                            }
                        state.value = AapState()
                        backoff = if (heard) 0 else (backoff + 1).coerceAtMost(RETRY_MS.size)
                    }
                    val wait = if (device == null || backoff == 0) null else RETRY_MS[backoff - 1]
                    if (wait == null) nudges.receive() else withTimeoutOrNull(wait) { nudges.receive() }
                }
            } finally {
                context.unregisterReceiver(receiver)
                synchronized(profiles) {
                    for ((p, proxy) in profiles) runCatching { adapter?.closeProfileProxy(p, proxy) }
                    profiles.clear()
                }
            }
        }

    /** The AirPods (or Beats) among the devices connected for audio: the ones that list AAP among their services. */
    @SuppressLint("MissingPermission")
    private fun find(): BluetoothDevice? {
        if (adapter?.isEnabled != true) return null
        val connected = synchronized(profiles) { profiles.values.flatMap { runCatching { it.connectedDevices }.getOrDefault(emptyList()) } }
        // A device Android never looked up the services of has none listed: by its name, then.
        return connected.firstOrNull { d -> d.uuids?.any { it == AAP_UUID } ?: (d.name?.contains("AirPods") == true) }
    }

    /**
     * One link to [device], until it drops or the source is no longer read; true if the AirPods answered on it. Bluetooth
     * sockets don't heed interrupts: whatever blocks on one ends by its being closed, on a timeout or a cancel.
     */
    @SuppressLint("MissingPermission")
    private suspend fun session(device: BluetoothDevice): Boolean =
        coroutineScope {
            val socket = withContext(Dispatchers.IO) { open(device) }
            var heard = false
            try {
                var connected = false
                val watchdog =
                    launch {
                        try {
                            delay(CONNECT_MS)
                        } finally {
                            if (!connected) runCatching { socket.close() }
                        }
                    }
                withContext(Dispatchers.IO) { socket.connect() }
                connected = true
                watchdog.cancel()
                alias = (if (Build.VERSION.SDK_INT >= 30) device.alias else null) ?: device.name
                link = socket
                state.value = AapState(connected = true)
                val acked = CompletableDeferred<Unit>()
                val setup =
                    launch(Dispatchers.IO) {
                        try {
                            send(socket, Aap.HANDSHAKE)
                            withTimeoutOrNull(ACK_MS) { acked.await() }
                            send(socket, Aap.FEATURES)
                            delay(FEATURES_MS)
                            send(socket, Aap.NOTIFICATIONS)
                            // Some pairs miss the first request while they're still settling.
                            delay(NOTIFY_AGAIN_MS)
                            if (state.value.batteries.isEmpty()) send(socket, Aap.NOTIFICATIONS)
                        } catch (_: IOException) {
                            // The link dropped: the reader sees that too, and ends the session.
                        }
                    }
                val reader =
                    launch(Dispatchers.IO) {
                        val buf = ByteArray(socket.maxReceivePacketSize.coerceAtLeast(READ_BYTES))
                        try {
                            val input = socket.inputStream
                            while (true) {
                                // One packet per read, as long as the buffer holds the largest the link takes.
                                val n = input.read(buf)
                                if (n < 0) break
                                if (n == 0) continue
                                val packet = buf.copyOf(n)
                                trace("<", packet)
                                heard = true
                                if (Aap.isHandshakeAck(packet)) acked.complete(Unit) else state.update { it.read(packet) }
                            }
                        } catch (_: IOException) {
                            // The AirPods went away, were put in their case, or the socket was closed here.
                        }
                    }
                reader.join()
                setup.cancel()
                heard
            } finally {
                link = null
                runCatching { socket.close() }
            }
        }

    /**
     * A classic L2CAP socket to AAP's PSM. BluetoothSocket's constructor takes the adapter first since Android 16 QPR3,
     * and the device first before it; neither is public, which is why it's looked up.
     */
    private fun open(device: BluetoothDevice): BluetoothSocket {
        val loaded = exempted
        val uuid = ParcelUuid.fromString(Aap.UUID)
        val int = Int::class.javaPrimitiveType!!
        val bool = Boolean::class.javaPrimitiveType!!
        val shapes =
            listOf(
                arrayOf(BluetoothAdapter::class.java, BluetoothDevice::class.java, int, bool, bool, int, ParcelUuid::class.java) to
                    arrayOf<Any?>(adapter, device, TYPE_L2CAP, true, true, Aap.PSM, uuid),
                arrayOf(BluetoothDevice::class.java, int, bool, bool, int, ParcelUuid::class.java) to
                    arrayOf<Any?>(device, TYPE_L2CAP, true, true, Aap.PSM, uuid),
            )
        var failure: Throwable? = null
        for ((types, args) in shapes) {
            try {
                val constructor = BluetoothSocket::class.java.getDeclaredConstructor(*types)
                constructor.isAccessible = true
                return constructor.newInstance(*args) as BluetoothSocket
            } catch (e: ReflectiveOperationException) {
                failure = e.cause ?: e
            }
        }
        unsupported = true
        throw IOException(if (loaded) "this Android makes no L2CAP sockets for apps" else "aap.c didn't load", failure)
    }

    private fun send(
        socket: BluetoothSocket,
        packet: ByteArray,
    ) = synchronized(socket) {
        trace(">", packet)
        socket.outputStream.write(packet)
        socket.outputStream.flush()
    }

    /** Every packet both ways, in hex, after `adb shell setprop log.tag.AirPods DEBUG`. */
    private fun trace(
        way: String,
        packet: ByteArray,
    ) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, way + " " + packet.joinToString("") { "%02x".format(it) })
    }

    private companion object {
        const val TAG = "AirPods"

        /** Whether aap.c loaded, which opens BluetoothSocket's constructor to the launcher as it loads; tried once, before the first socket. */
        val exempted by lazy { runCatching { System.loadLibrary("aap") }.onFailure { Log.w(TAG, "aap: ${it.message}") }.isSuccess }

        val AAP_UUID: ParcelUuid = ParcelUuid.fromString(Aap.UUID)

        /** BluetoothSocket.TYPE_L2CAP, public only since Android 13. */
        const val TYPE_L2CAP = 3

        /** The profiles AirPods connect for audio; their lists say which devices are connected. */
        val PROFILES = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)

        /** BluetoothA2dp's and BluetoothHeadset's ACTION_CONNECTION_STATE_CHANGED. */
        const val A2DP_CONNECTION = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
        const val HEADSET_CONNECTION = "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED"

        /** How long the link stays up after the last widget stops reading it. */
        const val LINGER_MS = 60_000L
        const val CONNECT_MS = 10_000L
        const val ACK_MS = 2_000L
        const val FEATURES_MS = 300L
        const val NOTIFY_AGAIN_MS = 2_000L
        const val READ_BYTES = 1024

        /** Waits before trying again a pair whose link failed, the last one kept until something changes. */
        val RETRY_MS = listOf(5_000L, 30_000L, 120_000L, 600_000L)
    }
}
