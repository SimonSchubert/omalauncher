package dev.omalauncher.wayland

import android.os.SystemClock
import android.util.Log
import java.io.File

const val BTN_LEFT = 0x110
const val BTN_RIGHT = 0x111
const val BTN_MIDDLE = 0x112

/** wl_pointer.axis_source values. */
const val AXIS_SOURCE_WHEEL = 0
const val AXIS_SOURCE_FINGER = 1

internal const val MOD_SHIFT = 1
internal const val MOD_LOCK = 2
internal const val MOD_CTRL = 4
internal const val MOD_ALT = 8
internal const val MOD_SUPER = 64

/** Seat state: pointer and keyboard focus. All methods must be called with the server locked. */
class Input(
    private val server: WaylandServer,
) {
    private val keyboards = mutableListOf<KeyboardResource>()
    private var keymap: Keymap? = null

    var pointerFocus: Surface? = null
        private set
    var keyboardFocus: Surface? = null
        private set

    private val pressedKeys = linkedSetOf<Int>()
    private var depressedMods = 0
    private var lockedMods = 0

    private fun now() = SystemClock.uptimeMillis().toInt()

    /** Uses a NUL-terminated XKB keymap file, extended with slots for other characters; see [Keymap]. */
    fun setKeymap(path: String) {
        val file = File(path)
        keymap =
            try {
                Keymap(file.readText().trimEnd('\u0000'), file.resolveSibling("keymaps"))
            } catch (e: Exception) {
                Log.w("wl.input", "cannot load keymap $path", e)
                return
            }
        keyboards.forEach(::sendKeymap)
    }

    /** Sends a fresh fd each time, so a keymap that changes doesn't close one a queued event holds. */
    private fun sendKeymap(k: KeyboardResource) {
        val file = keymap?.file ?: return
        val fd = Native.openReadOnly(file.path)
        if (fd < 0) {
            Log.w("wl.input", "cannot open keymap $file: $fd")
            return
        }
        k.send(k.event(0).uint(1).fd(fd, close = true).uint(file.length().toInt()))
    }

    /** Types a character no US key has, by binding it to a spare key first. */
    fun typeCodePoint(codePoint: Int) {
        val map = keymap ?: return
        val (code, changed) = map.slotFor(codePoint)
        if (changed) keyboards.forEach(::sendKeymap)
        key(code, true)
        key(code, false)
    }

    fun addKeyboard(k: KeyboardResource) {
        keyboards += k
        sendKeymap(k)
        // Rate 0 disables client-side repeat. Under proot a client can stall long enough for
        // its repeat timer to fire many times before it sees the release; Android's own
        // repeat events are forwarded instead (see KeyboardBridge).
        if (k.version >= 4) k.send(k.event(5).int(0).int(0))
        val focus = keyboardFocus
        if (focus != null && focus.client === k.client) {
            k.send(k.event(1).uint(server.nextSerial()).obj(focus.id).ints(IntArray(0)))
            sendModifiers(k)
        }
    }

    fun removeKeyboard(k: KeyboardResource) {
        keyboards -= k
    }

    private fun keyboardsOf(client: Client) = client.all(KeyboardResource::class.java)

    private fun textInputsOf(client: Client) = client.all(TextInput::class.java)

    /** A text input made while its client has keyboard focus enters that surface right away, as a keyboard does. */
    fun addTextInput(t: TextInput) {
        val focus = keyboardFocus
        if (focus != null && focus.client === t.client) t.enter(focus)
    }

    /** Where [surface]'s client last said its text cursor is, while [surface] has the keyboard: x, y, width, height. */
    fun textCursor(surface: Surface): IntArray? {
        if (keyboardFocus !== surface) return null
        return textInputsOf(surface.client).firstNotNullOfOrNull { it.cursor }
    }

    private fun pointersOf(client: Client) = client.all(PointerResource::class.java)

    fun setKeyboardFocus(surface: Surface?) {
        if (surface === keyboardFocus || surface?.destroyed == true) return
        try {
            moveKeyboardFocus(surface)
        } finally {
            // A lock only holds in the window that has the keys.
            updateLock()
        }
    }

    private fun moveKeyboardFocus(surface: Surface?) {
        // Android doesn't tell a window about keys released after it lost focus; start clean
        // so a key held across the switch isn't swallowed as "already down" next time.
        pressedKeys.clear()
        depressedMods = 0
        keyboardFocus?.let { old ->
            textInputsOf(old.client).forEach { it.leave(old) }
            keyboardsOf(old.client).forEach { it.send(it.event(2).uint(server.nextSerial()).obj(old.id)) }
        }
        val previous = keyboardFocus
        keyboardFocus = surface
        surface?.let { s ->
            // The selection goes to a client before its keyboard focus, so a paste right away works.
            if (s.client !== previous?.client) server.selection.focusChanged(s.client)
            keyboardsOf(s.client).forEach {
                it.send(it.event(1).uint(server.nextSerial()).obj(s.id).ints(IntArray(0)))
                sendModifiers(it)
            }
            // After the keyboard's enter: foot expects it first.
            textInputsOf(s.client).forEach { it.enter(s) }
        }
    }

    fun key(
        code: Int,
        pressed: Boolean,
    ) {
        if (pressed && !pressedKeys.add(code)) return
        if (!pressed && !pressedKeys.remove(code)) return
        val focus = keyboardFocus ?: return
        val serial = server.nextSerial()
        val time = now()
        keyboardsOf(focus.client).forEach {
            it.send(it.event(3).uint(serial).uint(time).uint(code).uint(if (pressed) 1 else 0))
        }
        val bit = Keys.modifierBit(code)
        if (bit != 0) {
            if (bit == MOD_LOCK) {
                if (pressed) lockedMods = lockedMods xor MOD_LOCK
            } else {
                depressedMods = pressedKeys.fold(0) { acc, k -> acc or (Keys.modifierBit(k).takeIf { it != MOD_LOCK } ?: 0) }
            }
            keyboardsOf(focus.client).forEach(::sendModifiers)
        }
    }

    private fun sendModifiers(k: KeyboardResource) {
        k.send(k.event(4).uint(server.nextSerial()).uint(depressedMods).uint(0).uint(lockedMods).uint(0))
    }


    /** Moves the pointer to surface-local ([x], [y]) on [surface], or off all surfaces. */
    fun pointerMotion(
        surface: Surface?,
        x: Float,
        y: Float,
    ) {
        // A locked pointer stays put on its surface. Android's cursor leaving the window as the mouse is captured
        // mustn't take it away: winit only unlocks a pointer that is over its window, so the lock would outlive the
        // game's grab and keep the mouse captured over its menus.
        if (locks.any { it.active }) return
        // Hit testing ran on the UI thread; the surface may have gone since.
        if (surface?.destroyed == true) return pointerMotion(null, 0f, 0f)
        if (surface !== pointerFocus) {
            pointerFocus?.let { old ->
                pointersOf(old.client).forEach {
                    it.send(it.event(1).uint(server.nextSerial()).obj(old.id))
                    it.frame()
                }
            }
            pointerFocus = surface
            surface?.let { s ->
                pointersOf(s.client).forEach {
                    it.send(it.event(0).uint(server.nextSerial()).obj(s.id).fixed(x).fixed(y))
                    it.frame()
                }
            }
            return
        }
        val s = surface ?: return
        val time = now()
        pointersOf(s.client).forEach {
            it.send(it.event(2).uint(time).fixed(x).fixed(y))
            it.frame()
        }
    }

    fun pointerButton(
        button: Int,
        pressed: Boolean,
    ) {
        val s = pointerFocus ?: return
        val serial = server.nextSerial()
        val time = now()
        pointersOf(s.client).forEach {
            it.send(it.event(3).uint(serial).uint(time).uint(button).uint(if (pressed) 1 else 0))
            it.frame()
        }
    }

    /** Scroll by logical pixels; positive is down/right. [steps] is the wheel click count, if any. */
    fun pointerAxis(
        dx: Float,
        dy: Float,
        source: Int,
        steps: Int = 0,
    ) {
        val s = pointerFocus ?: return
        val time = now()
        pointersOf(s.client).forEach { p ->
            if (p.version >= 5) p.send(p.event(6).uint(source))
            if (dy != 0f) {
                if (p.version >= 5 && steps != 0) p.send(p.event(8).uint(0).int(steps))
                p.send(p.event(4).uint(time).uint(0).fixed(dy))
            }
            if (dx != 0f) p.send(p.event(4).uint(time).uint(1).fixed(dx))
            p.frame()
        }
    }

    fun pointerAxisStop() {
        val s = pointerFocus ?: return
        val time = now()
        pointersOf(s.client).filter { it.version >= 5 }.forEach { p ->
            p.send(p.event(7).uint(time).uint(0))
            p.frame()
        }
    }

    /** Surfaces that received each active touch point's down event; later events go there. */
    private val touchFocus = HashMap<Int, Surface>()

    private fun touchesOf(client: Client) = client.all(TouchResource::class.java)

    /** Whether the surface's client listens for touch; otherwise touch is emulated with the pointer. */
    fun wantsTouch(surface: Surface) = touchesOf(surface.client).isNotEmpty()

    fun touchDown(
        surface: Surface,
        id: Int,
        x: Float,
        y: Float,
    ) {
        if (surface.destroyed) return
        touchFocus[id] = surface
        val serial = server.nextSerial()
        val time = now()
        touchesOf(surface.client).forEach {
            it.send(it.event(0).uint(serial).uint(time).obj(surface.id).int(id).fixed(x).fixed(y))
            it.send(it.event(3))
        }
    }

    fun touchMotion(
        id: Int,
        x: Float,
        y: Float,
    ) {
        val surface = touchFocus[id] ?: return
        val time = now()
        touchesOf(surface.client).forEach {
            it.send(it.event(2).uint(time).int(id).fixed(x).fixed(y))
            it.send(it.event(3))
        }
    }

    fun touchUp(id: Int) {
        val surface = touchFocus.remove(id) ?: return
        val serial = server.nextSerial()
        val time = now()
        touchesOf(surface.client).forEach {
            it.send(it.event(1).uint(serial).uint(time).int(id))
            it.send(it.event(3))
        }
    }

    /** Pointer locks clients asked for, oldest first; see [PointerConstraints]. */
    private val locks = mutableListOf<LockedPointer>()

    /** The window Android captured the mouse for, if any. */
    private var capturedFor: XdgToplevel? = null

    /** The window whose lock wants the mouse captured; [onCaptureWanted] hears of each change. */
    private var captureWanted: XdgToplevel? = null

    /** Told with the server locked when a different window (or none) wants Android to capture the mouse. */
    var onCaptureWanted: (XdgToplevel?) -> Unit = {}

    fun addLock(lock: LockedPointer) {
        locks += lock
        updateLock()
    }

    fun removeLock(lock: LockedPointer) {
        locks -= lock
        updateLock()
    }

    /** Android captured the mouse for [window], or let it go: the lock there starts or ends. */
    fun pointerCaptured(
        window: XdgToplevel,
        on: Boolean,
    ) {
        if (on) {
            capturedFor = window
        } else if (capturedFor === window) {
            capturedFor = null
        }
        updateLock()
    }

    /** The lock that applies: one on a surface of the window that has the keys, not used up. */
    private fun wantedLock(): LockedPointer? {
        val window = keyboardFocus?.let(server::toplevelOf) ?: return null
        return locks.firstOrNull { !it.spent && !it.surface.destroyed && server.toplevelOf(it.surface) === window }
    }

    private fun updateLock() {
        val wanted = wantedLock()
        val window = wanted?.let { server.toplevelOf(it.surface) }
        val holds = wanted != null && capturedFor === window
        locks.forEach { if (it !== wanted || !holds) it.unlock() }
        if (window !== captureWanted) {
            captureWanted = window
            onCaptureWanted(window)
        }
        if (wanted != null && holds && !wanted.active) {
            // Buttons and the wheel go where the pointer is; Android's cursor may have been elsewhere.
            if (pointerFocus !== wanted.surface) pointerMotion(wanted.surface, wanted.hintX, wanted.hintY)
            wanted.lock()
        }
    }

    /** The captured mouse moved: to the relative pointers of the client whose lock is on. */
    fun relativeMotion(
        dx: Float,
        dy: Float,
    ) {
        val lock = locks.firstOrNull { it.active } ?: return
        val timeUs = SystemClock.uptimeMillis() * 1000
        lock.client.all(RelativePointer::class.java).forEach { it.motion(timeUs, dx, dy) }
        pointersOf(lock.client).forEach { it.frame() }
    }

    fun surfaceDestroyed(surface: Surface) {
        if (pointerFocus === surface) pointerFocus = null
        if (keyboardFocus === surface) keyboardFocus = null
        touchFocus.values.removeAll { it === surface }
        if (locks.any { it.surface === surface }) updateLock()
    }
}
