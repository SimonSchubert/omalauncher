package dev.omalauncher.linux

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.omalauncher.wayland.Native
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

private const val TAG = "gamepads"

/** How long Guide is held to leave a window whose program has the pad. */
private const val LEAVE_HOLD_MS = 1000L

/**
 * Game controllers for Linux programs, without root. Each pad Android sees is a unix socket named eventN in [dir],
 * which the guest sees as /dev/input; the guest library libgamepads.so (patches/proot/gamepads) opens it in place of
 * a device, and answers evdev's ioctls from the description sent first ([PadLayout]). Programs find pads by watching
 * /dev/input: gilrs and SDL do when told not to ask udev (Proot's environment). Programs that only ask udev find
 * them in [udev]'s records.
 *
 * A pad's input goes to Linux only while a program has it open and a Linux window is in front (LinuxWindowActivity
 * asks); otherwise it moves around the launcher as before. Holding Guide leaves the window, as Back does.
 */
class Gamepads(
    context: Context,
    private val dir: File,
    private val udev: UdevRecords,
) : InputManager.InputDeviceListener {
    private val main = Handler(Looper.getMainLooper())
    private val inputManager = context.getSystemService(InputManager::class.java)

    /** By Android's device id; main thread only. */
    private val pads = HashMap<Int, Pad>()

    fun start() {
        dir.mkdirs()
        // Sockets of an earlier run, which nothing listens on, and their udev records.
        dir.listFiles()?.forEach { it.delete() }
        runCatching { udev.clear() }.onFailure { Log.w(TAG, "cannot clear udev records", it) }
        main.post {
            inputManager.registerInputDeviceListener(this, main)
            InputDevice.getDeviceIds().forEach(::onInputDeviceAdded)
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        if (deviceId in pads) return
        val device = InputDevice.getDevice(deviceId) ?: return
        if (!isPad(device)) return
        val number = generateSequence(0) { it + 1 }.first { n -> pads.values.none { it.number == n } }
        val pad = Pad(device, number)
        if (pad.listen()) pads[deviceId] = pad
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        pads.remove(deviceId)?.close()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId)
        if (device == null || !isPad(device)) onInputDeviceRemoved(deviceId) else onInputDeviceAdded(deviceId)
    }

    private fun isPad(device: InputDevice) =
        isPad(device.isVirtual, device.sources, device.keyboardType) { device.hasKeys(KeyEvent.KEYCODE_BUTTON_A)[0] }

    private val leave = Runnable { leaving?.invoke() }
    private var leaving: (() -> Unit)? = null

    /**
     * A key from a Linux window: true if it was a pad's and a program has that pad, so it went there. Held for a
     * second, Guide calls [onLeave].
     */
    fun key(
        event: KeyEvent,
        onLeave: () -> Unit,
    ): Boolean {
        val pad = pads[event.deviceId] ?: return false
        val down =
            when (event.action) {
                KeyEvent.ACTION_DOWN -> true
                KeyEvent.ACTION_UP -> false
                else -> return false
            }
        // A repeat changes nothing on the pad, but is still the program's.
        if (!pad.key(event.keyCode, down)) return false
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE && event.repeatCount == 0) {
            main.removeCallbacks(leave)
            leaving = onLeave.takeIf { down && pad.held }
            if (leaving != null) main.postDelayed(leave, LEAVE_HOLD_MS)
        }
        return pad.held
    }

    /** A stick, trigger or hat moving in a Linux window: true if a program has that pad, so it went there. */
    fun motion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val pad = pads[event.deviceId] ?: return false
        pad.motion(event)
        return pad.held
    }

    /** The Linux window went to the back: its program sees every button let go and every stick at rest. */
    fun releaseAll() {
        main.removeCallbacks(leave)
        pads.values.forEach { it.release() }
    }

    /** One pad: its socket, the programs connected to it, and its state, which a new connection starts from. */
    private inner class Pad(
        device: InputDevice,
        val number: Int,
    ) {
        private val name = device.name
        private val file = File(dir, "event$number")

        /** The right stick is AXIS_Z/AXIS_RZ, as on most pads, rather than AXIS_RX/AXIS_RY. */
        private val rightZ = device.getMotionRange(MotionEvent.AXIS_Z) != null

        /** Triggers that are axes; without, L2 and R2 are keys that move them all the way. */
        private val analogTriggers =
            listOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS)
                .any { device.getMotionRange(it) != null }

        /** A d-pad that's a hat; without, it's keys. */
        private val hatAxes = device.getMotionRange(MotionEvent.AXIS_HAT_X) != null

        // Guarded by this: the main thread changes them, the accept thread describes them to a new connection.
        private val connections = mutableListOf<Int>()
        /** The pad's buttons that are down. */
        private val down = HashSet<Int>()
        private val abs = IntArray(64)
        private val out = ByteBuffer.allocateDirect(PadLayout.EVENT_SIZE * 32).order(ByteOrder.LITTLE_ENDIAN)

        private var listenFd = -1

        @Volatile private var closed = false

        val held get() = synchronized(this) { connections.isNotEmpty() }

        fun listen(): Boolean {
            // Made under a name programs skip, then moved in place: one that sees eventN appear can connect at once.
            val staging = File(dir, ".event$number")
            val fd = Native.listen(staging.path)
            if (fd < 0) {
                Log.w(TAG, "cannot listen for $name: errno ${-fd}")
                return false
            }
            if (!staging.renameTo(file)) {
                Log.w(TAG, "cannot place $file")
                Native.close(fd)
                staging.delete()
                return false
            }
            listenFd = fd
            // A failed record only keeps the pad from programs that ask udev.
            runCatching { udev.add(number, name) }.onFailure { Log.w(TAG, "no udev record for $name", it) }
            Log.i(TAG, "$name is /dev/input/${file.name}")
            thread(name = "gamepad-$number", isDaemon = true) { accept(fd) }
            return true
        }

        private fun accept(fd: Int) {
            while (true) {
                val c = Native.accept(fd)
                if (c < 0) {
                    if (closed) break
                    SystemClock.sleep(100)
                    continue
                }
                val joined =
                    synchronized(this) {
                        val description = PadLayout.description(name, down, abs)
                        val buf = ByteBuffer.allocateDirect(description.size).put(description)
                        // Blocking for the description, which fits an empty socket; events after it don't wait (flush).
                        (Native.send(c, buf, description.size, NO_FDS, 0) == description.size).also { if (it) connections += c }
                    }
                if (joined) thread(name = "gamepad-$number-$c", isDaemon = true) { drain(c) } else Native.close(c)
            }
            Native.close(fd)
        }

        /** Reads what a program writes (rumble, which isn't offered) until it closes; only this thread closes [c]. */
        private fun drain(c: Int) {
            val buf = ByteBuffer.allocateDirect(1024)
            val fds = IntArray(29)
            while (Native.recv(c, buf, 0, fds) > 0) {
                for (i in 1..fds[0]) Native.close(fds[i])
            }
            synchronized(this) { connections -= c }
            Native.close(c)
        }

        /** Updates the pad for a key; false if the key isn't one of the pad's. */
        fun key(
            keyCode: Int,
            pressed: Boolean,
        ): Boolean =
            synchronized(this) {
                val button = PadLayout.button(keyCode)
                val dpad = PadLayout.dpad(keyCode)
                val trigger = PadLayout.triggerKey(keyCode)
                when {
                    button != null -> if (if (pressed) down.add(button) else down.remove(button)) put(PadLayout.EV_KEY, button, if (pressed) 1 else 0)
                    dpad != null -> if (!hatAxes) setAbs(dpad.first, if (pressed) dpad.second else 0)
                    trigger != null -> if (!analogTriggers) setAbs(trigger, if (pressed) 255 else 0)
                    else -> return false
                }
                flush()
                true
            }

        fun motion(event: MotionEvent) =
            synchronized(this) {
                val values = PadLayout.axes(event::getAxisValue, rightZ)
                PadLayout.AXES.forEachIndexed { i, code ->
                    // Keys move these on pads without them as axes; their axis would read 0 and undo that.
                    if (PadLayout.isTrigger(code) && !analogTriggers) return@forEachIndexed
                    if (PadLayout.isHat(code) && !hatAxes) return@forEachIndexed
                    setAbs(code, values[i])
                }
                flush()
            }

        fun release() =
            synchronized(this) {
                down.forEach { put(PadLayout.EV_KEY, it, 0) }
                down.clear()
                PadLayout.AXES.forEach { setAbs(it, 0) }
                flush()
            }

        private fun setAbs(
            code: Int,
            value: Int,
        ) {
            if (abs[code] == value) return
            abs[code] = value
            put(PadLayout.EV_ABS, code, value)
        }

        private fun put(
            type: Int,
            code: Int,
            value: Int,
        ) {
            // Room for the SYN_REPORT; a frame that big is sent in parts.
            if (out.remaining() < 2 * PadLayout.EVENT_SIZE) flush()
            PadLayout.event(out, System.currentTimeMillis() * 1000, type, code, value)
        }

        /** Ends the frame with SYN_REPORT and sends it to every program that has the pad. */
        private fun flush() {
            if (out.position() == 0) return
            PadLayout.event(out, System.currentTimeMillis() * 1000, PadLayout.EV_SYN, PadLayout.SYN_REPORT, 0)
            val length = out.position()
            out.clear()
            val iterator = connections.iterator()
            while (iterator.hasNext()) {
                val c = iterator.next()
                // A program that stopped reading: dropped rather than waited for. Its reader closes the socket.
                if (Native.sendNow(c, out, length) != length) {
                    iterator.remove()
                    Native.shutdown(c)
                }
            }
        }

        fun close() {
            closed = true
            // Programs watching /dev/input see it go, then their connections end.
            file.delete()
            runCatching { udev.remove(number) }
            Native.shutdown(listenFd)
            synchronized(this) { connections.forEach(Native::shutdown) }
        }
    }

    internal companion object {
        private val NO_FDS = IntArray(0)

        /**
         * Whether a device is a game controller: a joystick, or a gamepad with an A button. Never a keyboard with
         * letters, though some claim a joystick too: its arrows would become a pad's d-pad, and programs would see a
         * second controller.
         */
        fun isPad(
            virtual: Boolean,
            sources: Int,
            keyboardType: Int,
            hasButtonA: () -> Boolean,
        ): Boolean {
            if (virtual || keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
            fun has(source: Int) = sources and source == source
            return has(InputDevice.SOURCE_JOYSTICK) || (has(InputDevice.SOURCE_GAMEPAD) && hasButtonA())
        }
    }
}
