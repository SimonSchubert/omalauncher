package dev.omalauncher.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.wayland.AXIS_SOURCE_FINGER
import dev.omalauncher.wayland.BTN_LEFT
import dev.omalauncher.wayland.Input
import dev.omalauncher.wayland.Keys
import dev.omalauncher.wayland.Surface
import dev.omalauncher.wayland.SurfaceHit
import dev.omalauncher.wayland.WaylandServer
import dev.omalauncher.wayland.WindowManagerHooks
import dev.omalauncher.wayland.XdgToplevel
import kotlin.math.abs

/**
 * Window state for Linux toplevels. Every toplevel is shown as its own Android
 * task (LinuxWindowActivity), which draws it with a [dev.omalauncher.wayland.WindowRenderer];
 * the home screen holds widgets, not windows, except the terminals widgets
 * embed, which [claim] takes instead (see [TerminalTiles]). Lives on the main thread;
 * protocol callbacks are posted over.
 */
class WindowManager : WindowManagerHooks {
    lateinit var server: WaylandServer
    private val main = Handler(Looper.getMainLooper())

    var focused by mutableStateOf<XdgToplevel?>(null)
        private set

    /** Sessions stay registered until their final queued input has completed. Guarded by [server]. */
    private val ternSessions = HashSet<TernGestureSession>()

    /** Toplevels that have (or are getting) their own Android task. */
    val appWindows = mutableStateListOf<XdgToplevel>()

    /** Each window's size in pixels, as its activity lays it out. */
    private val sizes = HashMap<XdgToplevel, Pair<Int, Int>>()

    /** Starts the activity for an app window; set by the runtime. */
    var openAppWindow: (handle: String) -> Unit = {}

    /** Takes a new toplevel for a home-screen tile instead of its own task; see [TerminalTiles]. */
    var claim: (XdgToplevel) -> Boolean = { false }

    /** A claimed toplevel went away. */
    var release: (XdgToplevel) -> Unit = {}

    /** A toplevel showed up, before it's claimed or opened. */
    var created: (XdgToplevel) -> Unit = {}

    /** A window got the keyboard (again); set by the runtime, which catches terminals up on the text size. */
    var focusGained: (XdgToplevel) -> Unit = {}

    /** Toplevels shown inside tiles. */
    private val embedded = HashSet<XdgToplevel>()

    /**
     * Handles tie Recents cards to windows. Cards outlive this process, so handles
     * carry a per-process session prefix: a stale card must never resolve to a
     * different window that happens to reuse its number after a restart.
     */
    private val handles = HashMap<String, XdgToplevel>()
    private val session = System.currentTimeMillis().toString(36)
    private var nextHandle = 1

    fun windowByHandle(handle: String?) = handle?.let(handles::get)

    fun handleOf(window: XdgToplevel) = handles.entries.firstOrNull { it.value === window }?.key

    /** Pixels per logical pixel (the wl_output scale). */
    val scale get() = server.output.scale

    override fun toplevelCreated(toplevel: XdgToplevel) {
        main.post {
            created(toplevel)
            if (claim(toplevel)) {
                embedded += toplevel
                return@post
            }
            appWindows += toplevel
            val handle = "$session-${nextHandle++}"
            handles[handle] = toplevel
            openAppWindow(handle)
        }
    }

    override fun toplevelDestroyed(toplevel: XdgToplevel) {
        main.post {
            if (embedded.remove(toplevel)) release(toplevel)
            appWindows -= toplevel
            handles.values.remove(toplevel)
            sizes -= toplevel
            if (focused === toplevel) focus(null)
        }
    }

    /** Sizes an app window to its activity; called when that activity's view gets its size. */
    fun applyAppLayout(
        window: XdgToplevel,
        width: Int,
        height: Int,
    ) {
        sizes[window] = width to height
        synchronized(server) { configure(window) }
    }

    fun close(window: XdgToplevel) = synchronized(server) { window.close() }

    fun focus(toplevel: XdgToplevel?) {
        val previous = focused
        synchronized(server) {
            // Release any in-flight Tern gesture synchronously, before the keyboard focus
            // moves, so it cannot hold a button down or enqueue actions for the new window.
            if (previous !== toplevel) cancelTernGestures()
            focused = toplevel
            server.input.setKeyboardFocus(toplevel?.surface)
            // Refresh the "activated" state on both windows.
            listOfNotNull(previous, toplevel).distinct().forEach { configure(it) }
        }
        toplevel?.let(focusGained)
    }

    /**
     * Zooms the focused [window]'s text [steps] times with Ctrl+= or Ctrl+-,
     * foot's font-increase and font-decrease. Only the focused window gets keys.
     */
    fun zoom(
        window: XdgToplevel,
        steps: Int,
    ) {
        if (window !== focused || steps == 0) return
        val code = if (steps > 0) Keys.KEY_EQUAL else Keys.KEY_MINUS
        val times = abs(steps)
        server.postInput {
            if (!acceptsKeys(window)) return@postInput
            ctrlChord(code, times)
        }
    }

    /** On the server thread: [window] is live, focused and still owns keyboard focus. */
    private fun Input.acceptsKeys(window: XdgToplevel): Boolean =
        focused === window &&
            window.xdg.toplevel === window &&
            !window.surface.destroyed &&
            keyboardFocus === window.surface

    private fun configure(window: XdgToplevel) {
        val (w, h) = sizes[window] ?: return
        window.configure(w / scale, h / scale, window === focused)
    }

    fun pointerMove(hit: SurfaceHit?) = server.postInput { pointerMotion(hit?.surface, hit?.x ?: 0f, hit?.y ?: 0f) }

    fun pointerButton(
        button: Int,
        pressed: Boolean,
    ) = server.postInput { pointerButton(button, pressed) }

    fun click(
        hit: SurfaceHit,
        button: Int,
    ) = server.postInput {
        pointerMotion(hit.surface, hit.x, hit.y)
        pointerButton(button, true)
        pointerButton(button, false)
    }

    fun scroll(
        dx: Float,
        dy: Float,
        source: Int,
        steps: Int = 0,
    ) = server.postInput { pointerAxis(dx, dy, source, steps) }

    fun wantsTouch(surface: Surface) = synchronized(server) { server.input.wantsTouch(surface) }

    fun touchDown(
        hit: SurfaceHit,
        id: Int,
    ) = server.postInput { touchDown(hit.surface, id, hit.x, hit.y) }

    fun touchMotion(
        id: Int,
        x: Float,
        y: Float,
    ) = server.postInput { touchMotion(id, x, y) }

    fun touchUp(id: Int) = server.postInput { touchUp(id) }

    fun scrollStop() = server.postInput { pointerAxisStop() }

    /** Closes [window]'s popups, newest first. */
    private fun dismissPopups(window: XdgToplevel) =
        synchronized(server) {
            server.popups.filter { server.toplevelOf(it.surface) === window }.asReversed().forEach { it.dismiss() }
        }

    /** Focus handling for a press at [hit] in [window]; returns false if the press only dismissed popups. */
    fun onPress(
        window: XdgToplevel,
        hit: SurfaceHit?,
    ): Boolean {
        if (window.renderer?.hasPopups == true && hit?.inPopup != true) {
            dismissPopups(window)
            return false
        }
        if (window !== focused) focus(window)
        return hit != null
    }

    /** Sink for a Tern touch gesture that started at [origin], in physical view pixels. */
    internal fun ternGestureSession(
        window: XdgToplevel,
        origin: SurfaceHit,
        startX: Float,
        startY: Float,
    ): TernGestureSession {
        val session = TernGestureSession(this, window, origin, startX, startY)
        synchronized(server) {
            ternSessions += session
            session.activate()
        }
        return session
    }

    /** Called with [server] locked; removal is scoped to this exact gesture. */
    internal fun unregisterTernSession(session: TernGestureSession) {
        ternSessions -= session
    }

    /** Ends emitted touch state before a focus switch or physical mouse event. */
    internal fun cancelTernGestures() = synchronized(server) {
        val sessions = ternSessions.iterator()
        while (sessions.hasNext()) {
            val session = sessions.next()
            sessions.remove()
            session.invalidate()
        }
    }
}

/**
 * Dispatches a [TernTouchGesture]'s callbacks to [window]'s original surface.
 * Every action re-checks, on the input thread, that the session is still active,
 * the toplevel is still the focused, live owner of [origin], before it touches
 * the seat, so a queued gesture can never leak clicks, selection, scroll or keys
 * into another window. Selection coordinates are absolute view pixels,
 * translated onto the surface hit when the touch began; the initial surface stays
 * grabbed while dragging.
 *
 * Lifecycle: [activate] registers, [finish] closes a normally all-lifted gesture
 * FIFO after its already-enqueued actions, and [invalidate] synchronously drops
 * queued/future actions and releases what was emitted (focus loss, mouse, cancel).
 */
internal class TernGestureSession(
    private val wm: WindowManager,
    private val window: XdgToplevel,
    private val origin: SurfaceHit,
    private val startX: Float,
    private val startY: Float,
) : TernTouchGesture.Sink {
    private val server get() = wm.server
    private val scale get() = wm.scale.toFloat()

    // Guarded by synchronized(server): postInput blocks and WindowManager.focus()
    // both hold that lock, so there is no separate synchronisation here.
    private var active = false
    private var selectionHeld = false
    private var scrollStarted = false
    private var lastX = origin.x
    private var lastY = origin.y

    /** Owns the seat: actions are live only while this session is [active]. */
    fun activate() {
        active = true
    }

    /**
     * Synchronously ends this session: all future and already-queued actions drop
     * (the [active] flag is cleared first), and whatever it actually emitted is
     * released now. Called before a focus switch, so it cannot touch the new window.
     */
    fun invalidate() {
        synchronized(server) {
            if (active) {
                active = false
                releaseSelectionLocked()
                releaseScrollLocked()
            }
            wm.unregisterTernSession(this)
        }
    }

    /**
     * Normal all-lift completion. Already-enqueued actions (the final tap/tab/
     * release) run first; cleanup is enqueued behind them, so it never invalidates
     * a completed gesture's own click. A no-op if already invalidated.
     */
    fun finish() = server.postInput { invalidate() }

    private fun toSurfaceX(x: Float) = origin.x + (x - startX) / scale

    private fun toSurfaceY(y: Float) = origin.y + (y - startY) / scale

    /** The toplevel, its root surface and the grabbed surface are all still alive and related. */
    private fun live(): Boolean =
        window.xdg.toplevel === window &&
            !window.surface.destroyed &&
            !origin.surface.destroyed &&
            server.toplevelOf(origin.surface) === window

    /** [live], this session active, and still the focused keyboard-focus owner. */
    private fun targeted(): Boolean =
        active && wm.focused === window && live() && server.input.keyboardFocus === window.surface

    override fun click(
        x: Float,
        y: Float,
    ) = server.postInput {
        if (!targeted()) return@postInput
        pointerMotion(origin.surface, toSurfaceX(x), toSurfaceY(y))
        pointerButton(BTN_LEFT, true)
        pointerButton(BTN_LEFT, false)
    }

    override fun selectStart(
        x: Float,
        y: Float,
    ) = server.postInput {
        if (!targeted()) return@postInput
        lastX = toSurfaceX(x)
        lastY = toSurfaceY(y)
        pointerMotion(origin.surface, lastX, lastY)
        pointerButton(BTN_LEFT, true)
        selectionHeld = true
    }

    override fun selectMove(
        x: Float,
        y: Float,
    ) = server.postInput {
        if (!targeted() || !selectionHeld) return@postInput
        lastX = toSurfaceX(x)
        lastY = toSurfaceY(y)
        pointerMotion(origin.surface, lastX, lastY)
    }

    override fun selectEnd() = server.postInput { releaseSelectionLocked() }

    override fun scroll(
        dx: Float,
        dy: Float,
    ) = server.postInput {
        if (!targeted()) return@postInput
        if (pointerFocus !== origin.surface) pointerMotion(origin.surface, lastX, lastY)
        if (pointerFocus === origin.surface && (dx != 0f || dy != 0f)) {
            pointerAxis(dx / scale, dy / scale, AXIS_SOURCE_FINGER)
        }
        scrollStarted = true
    }

    override fun scrollStop() = server.postInput { releaseScrollLocked() }

    /** A cancelled session must not enqueue keys for a window that has since regained focus. */
    override fun zoom(steps: Int) = server.postInput {
        if (!targeted()) return@postInput
        ctrlChord(if (steps > 0) Keys.KEY_EQUAL else Keys.KEY_MINUS, abs(steps), withShift = true)
    }

    override fun tab(direction: Int) = server.postInput {
        if (!targeted()) return@postInput
        ctrlChord(if (direction > 0) Keys.KEY_RIGHT else Keys.KEY_LEFT, withShift = true)
    }

    /** Releases the grabbed button only if we pressed it, on our still-live surface. */
    private fun releaseSelectionLocked() {
        if (!selectionHeld) return
        selectionHeld = false
        if (!live()) return
        if (server.input.pointerFocus === origin.surface) server.input.pointerButton(BTN_LEFT, false)
    }

    /**
     * Ends an emitted scroll. Only ever releases on the original still-live surface;
     * it never retargets the pointer, so it cannot steal a later physical mouse focus.
     */
    private fun releaseScrollLocked() {
        if (!scrollStarted) return
        scrollStarted = false
        if (!live()) return
        if (server.input.pointerFocus === origin.surface) server.input.pointerAxisStop()
    }
}
