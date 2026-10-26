package dev.omalauncher.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.wayland.Keys
import dev.omalauncher.wayland.Surface
import dev.omalauncher.wayland.SurfaceHit
import dev.omalauncher.wayland.WaylandServer
import dev.omalauncher.wayland.WindowManagerHooks
import dev.omalauncher.wayland.XdgToplevel
import kotlin.math.abs

/**
 * Window state for Linux toplevels. Every toplevel is shown as its own Android
 * task (LinuxWindowActivity), which draws it with a [dev.omalauncher.wayland.WindowRenderer],
 * except one with a parent that's on screen (a dialog, a torn-off toolbar): that one is
 * hosted in its parent's task, over it, at its own size, or filling it when it's as wide
 * or as tall as the view;
 * the home screen holds widgets, not windows, except the terminals widgets
 * embed, which [claim] takes instead (see [TerminalTiles]). Lives on the main thread;
 * protocol callbacks are posted over.
 */
class WindowManager : WindowManagerHooks {
    lateinit var server: WaylandServer
    private val main = Handler(Looper.getMainLooper())

    var focused by mutableStateOf<XdgToplevel?>(null)
        private set

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

    override var decorationHeight = 0
    override var decorationColors = super.decorationColors

    /**
     * A press on the launcher's bar over a floating window: the window follows the drag, and a press on the
     * close button that hasn't moved asks it to close. None of it reaches the app.
     */
    private var barPress: SurfaceHit? = null
    private var barPressAt = 0f to 0f

    /** The window an app asked to move (its own title bar dragged), and where the drag holds it. */
    private var moving: XdgToplevel? = null
    private var movingGrip = 0f to 0f

    override fun moveRequested(toplevel: XdgToplevel) {
        main.post {
            val at = server.input.dragPoint ?: return@post
            val pos = toplevel.position ?: return@post
            if (toplevel.host == null || toplevel.fullscreen) return@post
            moving = toplevel
            movingGrip = at.first - pos.first to at.second - pos.second
        }
    }

    override fun toplevelCreated(toplevel: XdgToplevel) {
        main.post {
            created(toplevel)
            if (claim(toplevel)) {
                embedded += toplevel
                return@post
            }
            // Desktop apps open these over the window they belong to; a task of its own would take the screen
            // from it, and end a drag that tore a toolbar off.
            // One a drag carries along (a torn-off toolbar) goes over the window the drag started in.
            val owner = toplevel.parent ?: synchronized(server) { toplevel.dragSource?.let { server.input.drag?.origin } }
            val host = owner?.let { it.host ?: it }?.takeIf { it in appWindows }
            if (host != null) {
                synchronized(server) {
                    toplevel.host = host
                    host.children += toplevel
                }
                focus(toplevel)
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
            toplevel.host?.let { host ->
                synchronized(server) {
                    host.children -= toplevel
                    toplevel.host = null
                }
                host.renderer?.requestFrame()
                if (focused === toplevel) focus(host)
                return@post
            }
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
        focused = toplevel
        synchronized(server) {
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
        server.postInput {
            key(Keys.KEY_LEFTCTRL, true)
            repeat(abs(steps)) {
                key(code, true)
                key(code, false)
            }
            key(Keys.KEY_LEFTCTRL, false)
        }
    }

    private fun configure(window: XdgToplevel) {
        val host = window.host
        if (host != null) {
            // Floating, it picks its own size; filling the view, it's told the view's.
            if (!window.fullscreen) return window.configure(0, 0, window === focused)
            val (w, h) = sizes[host] ?: return
            return window.configure(w / scale, h / scale, window === focused)
        }
        val (w, h) = sizes[window] ?: return
        window.configure(w / scale, h / scale, window === focused)
        window.children.forEach(::configure)
    }

    /** Closes the newest window hosted over [window], as Escape would; false if there's none. */
    fun closeTopChild(window: XdgToplevel): Boolean {
        val top = synchronized(server) { window.children.lastOrNull() } ?: return false
        close(top)
        return true
    }

    /**
     * A pointer button or finger is held at view pixel ([x], [y]) of [window]: a drag and drop under way draws
     * its icon and carries its window there.
     */
    fun dragTo(
        window: XdgToplevel,
        x: Float,
        y: Float,
    ) {
        server.input.dragPoint = x to y
        moving?.let { m ->
            synchronized(server) { m.position = x - movingGrip.first to y - movingGrip.second }
            window.renderer?.requestFrame()
        }
        if (server.input.drag != null) window.renderer?.requestFrame()
    }

    /** Nothing's held any more. */
    fun endDrag() {
        server.input.dragPoint = null
        moving = null
    }

    /** The surface a held button was pressed on: the pointer stays with it, wherever it goes, until release. */
    private var grabbed: Surface? = null
    private val heldButtons = HashSet<Int>()

    /** Moves the pointer to view pixel ([x], [y]) of [window]: to what's there, or on over the surface a held button was pressed on. */
    fun pointerAt(
        window: XdgToplevel,
        x: Float,
        y: Float,
    ) {
        pointerPoint = x to y
        val r = window.renderer ?: return pointerMove(null)
        if (heldButtons.isNotEmpty()) dragTo(window, x, y)
        val under = r.hitTest(x, y)
        pointerMove(grabbed?.let { r.locate(it, x, y) } ?: under, under)
    }

    /** Where the pointer was last sent; input runs on its own thread, so this is ahead of Input's own focus. */
    private var pointerSurface: Surface? = null

    /** The pointer's last position in view pixels, through [pointerAt], and what it was over. */
    private var pointerPoint: Pair<Float, Float>? = null
    private var pointerHit: SurfaceHit? = null

    /** Moves the pointer onto [hit]; [under] is what's under it, which a drag and drop goes by (see [pointerAt]). */
    fun pointerMove(
        hit: SurfaceHit?,
        under: SurfaceHit? = hit,
    ) {
        pointerHit = hit
        // Over the launcher's bar, or dragging it: the app hears the pointer left, and nothing more.
        if (barPress != null) return
        if (hit?.decoration != null) {
            pointerSurface = null
            server.postInput { pointerMotion(null, 0f, 0f) }
            return
        }
        pointerSurface = hit?.surface
        server.postInput { pointerMotion(hit?.surface, hit?.x ?: 0f, hit?.y ?: 0f, under) }
    }

    fun pointerButton(
        button: Int,
        pressed: Boolean,
    ) {
        if (pressed) {
            if (heldButtons.isEmpty()) {
                grabbed = pointerSurface
                server.input.dragPoint = pointerPoint
                val bar = pointerHit?.decoration
                val pos = bar?.position
                val at = pointerPoint
                if (bar != null && pos != null && at != null) {
                    barPress = pointerHit
                    barPressAt = at
                    moving = bar
                    movingGrip = at.first - pos.first to at.second - pos.second
                }
            }
            heldButtons += button
            if (barPress != null) return
        } else {
            heldButtons -= button
            barPress?.let { press ->
                if (heldButtons.isNotEmpty()) return
                barPress = null
                val at = pointerPoint ?: barPressAt
                val still = kotlin.math.abs(at.first - barPressAt.first) + kotlin.math.abs(at.second - barPressAt.second) < 24f
                if (press.onClose && still) press.decoration?.let(::close)
                grabbed = null
                endDrag()
                return
            }
            if (heldButtons.isEmpty()) {
                grabbed = null
                endDrag()
            }
        }
        server.postInput { pointerButton(button, pressed) }
    }

    fun click(
        hit: SurfaceHit,
        button: Int,
    ) {
        // A tap on the launcher's bar: its close button closes the window; the rest of it does nothing on a tap.
        hit.decoration?.let { if (hit.onClose) close(it) }
        if (hit.decoration == null) clickApp(hit, button)
    }

    private fun clickApp(
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

    /** A finger moved to ([x], [y]) on the surface it went down on; [under] is what it's over now, for a drag and drop. */
    fun touchMotion(
        id: Int,
        x: Float,
        y: Float,
        under: SurfaceHit? = null,
    ) = server.postInput { touchMotion(id, x, y, under) }

    fun touchUp(id: Int) = server.postInput { touchUp(id) }

    /** Whether [hit] is on the row where its client last said its text cursor is: a terminal's prompt line. */
    fun onTextCursorRow(hit: SurfaceHit) =
        synchronized(server) {
            val (_, y, _, height) = server.input.textCursor(hit.surface) ?: return@synchronized false
            hit.y >= y && hit.y < y + height
        }

    fun scrollStop() = server.postInput { pointerAxisStop() }

    /** Closes [window]'s popups, newest first. */
    private fun dismissPopups(window: XdgToplevel) =
        synchronized(server) {
            server.popups
                .filter { server.toplevelOf(it.surface).let { top -> top === window || top?.host === window } }
                .asReversed()
                .forEach { it.dismiss() }
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
        // The keyboard goes to the window pressed: the app's, or one over it.
        val target = hit?.let { synchronized(server) { server.toplevelOf(it.surface) } } ?: window
        if (target !== focused) focus(target)
        return hit != null
    }
}
