package dev.omalauncher.ui.terminal

import android.os.Handler
import android.os.Looper
import dev.omalauncher.linux.Agent
import dev.omalauncher.linux.Proot
import dev.omalauncher.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/**
 * The built-in terminal windows that are open, each shown by a LinuxWindowActivity task of its own
 * (`omarchy://terminal/<id>`), as a foot window is by its handle. A window outlives its activity being made again
 * (Android may do that while it's in Recents) and ends with its program or when its task is closed. Main thread.
 */
class TerminalWindows(
    private val proot: Proot,
    private val scope: CoroutineScope,
) {
    /** One terminal window: [command] (a shell if null) named [name] in Recents, with [agent]'s logo if it runs one. */
    class Window internal constructor(
        val id: String,
        val command: String?,
        val name: String,
        val agent: Agent?,
        /** A point size of its own (btop's, to fit its layout), which the text size doesn't change; null follows it. */
        val ownSize: Double?,
    ) {
        /** The program, once the window had a grid to start it at; it stays when the activity is made again. */
        var session: TerminalSession? = null
            internal set

        /** Whether the program has ended; the window is then gone from [TerminalWindows]. */
        var ended = false
            internal set

        /**
         * Called once the program has ended, by the activity showing the window, which then closes and calls [close];
         * without one the terminal is freed at once.
         */
        var onEnd: (() -> Unit)? = null
    }

    private val open = HashMap<String, Window>()

    /** Ids are per process, as WindowManager's handles: a Recents card from before a restart finds no window. */
    private val process = System.currentTimeMillis().toString(36)
    private var next = 0

    /** A new window, not started yet: its activity starts the program once it knows how many columns fit. */
    fun add(
        command: String?,
        name: String,
        agent: Agent?,
        ownSize: Double?,
    ): Window = Window("$process-${next++}", command, name, agent, ownSize).also { open[it.id] = it }

    operator fun get(id: String?): Window? = open[id]

    /**
     * Starts [window]'s program on a terminal of [cols] by [rows] cells. Throws IOException where it can't (Linux
     * stopped, no library). When it ends the window goes and [Window.onEnd] runs, on the main thread.
     */
    fun start(
        window: Window,
        cols: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    ): TerminalSession =
        TerminalSession
            .guest(proot, window.command, cols, rows, cellWidth, cellHeight, onExit = { main.post { end(window) } })
            .also { window.session = it }

    private val main = Handler(Looper.getMainLooper())

    /** The window's task was closed: hangs its terminal up, as closing foot's window does, and frees it. */
    fun close(window: Window) {
        open.remove(window.id)
        window.session?.close()
    }

    private fun end(window: Window) {
        if (window.ended) return
        window.ended = true
        open.remove(window.id)
        window.onEnd?.invoke() ?: window.session?.close()
    }

    private var faces: Pair<String, Deferred<TerminalFaces>>? = null

    /**
     * [family]'s faces, asked of the guest's fontconfig once per family rather than once per window. [TerminalFaces.Fallback]
     * (Linux not running yet) isn't kept, so the next window asks again.
     */
    suspend fun faces(family: String): TerminalFaces {
        val known = faces?.takeIf { it.first == family }
        val loading = known ?: (family to scope.async(Dispatchers.IO) { TerminalFaces.load(proot, family) }).also { faces = it }
        return loading.second.await().also { if (it === TerminalFaces.Fallback && faces === loading) faces = null }
    }

    companion object {
        /** The host of a built-in terminal window's task data, `omarchy://terminal/<id>`; foot windows' is "window". */
        const val HOST = "terminal"
    }
}
