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
}
