package dev.omalauncher.terminal

import dev.omalauncher.linux.Proot
import dev.omalauncher.linux.shellQuote
import java.io.IOException
import kotlin.concurrent.thread

/**
 * A program on a pseudo-terminal, with [vt] the screen it draws: what it writes is read on a thread of the
 * session's own into [vt] ([onOutput] after each read), keys and text go to it with [press] and [type], and
 * [hangUp] closes the terminal as closing foot's window does. [onExit] gets its exit code, on that thread.
 * [vt] stays readable after the program ends, until [close]. Needs [TerminalLibrary.available].
 */
class TerminalSession(
    argv: List<String>,
    env: List<String>,
    cwd: String,
    cols: Int,
    rows: Int,
    cellWidth: Int = 0,
    cellHeight: Int = 0,
    /** Whether the program leads the terminal's session itself; see cpp/pty.c. */
    leader: Boolean = true,
    /** After each read: the screen changed. On the reading thread; a view showing [vt] sets it. */
    @Volatile var onOutput: () -> Unit = {},
    private val onExit: (Int) -> Unit = {},
) : AutoCloseable {
    /** What the program did besides drawing (bell, title, copy, notification), on the reading thread. */
    @Volatile var onEvent: (VtEvent) -> Unit = {}

    val vt = Vt(cols, rows, cellWidth, cellHeight)

    private val fd: Int
    private val pid: Int
    private val wakeRead: Int
    private val wakeWrite: Int

    /** Guards [fd] and [wakeWrite]: once the reading thread closes them, their numbers may belong to other files. */
    private val lock = Any()
    private var open = true

    /** The program's exit code, once it has ended. */
    @Volatile var exitCode: Int? = null
        private set

    init {
        val wake = PtyNative.wakePipe()
        val started = wake?.let { PtyNative.start(argv.toTypedArray(), env.toTypedArray(), cwd, rows, cols, leader) }
        if (wake == null || started == null) {
            wake?.forEach(PtyNative::close)
            vt.close()
            throw IOException("can't start ${argv.firstOrNull()} on a terminal")
        }
        fd = started[0]
        pid = started[1]
        wakeRead = wake[0]
        wakeWrite = wake[1]
        if (cellWidth > 0 && cellHeight > 0) PtyNative.resize(fd, rows, cols, cols * cellWidth, rows * cellHeight)
        thread(name = "terminal-$pid", isDaemon = true) { pump() }
    }

    private fun pump() {
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = PtyNative.read(fd, wakeRead, buf)
            if (n <= 0) break
            vt.write(buf, n)
            vt.replies()?.let(::send)
            for (e in vt.events()) onEvent(e)
            onOutput()
        }
        // Closing the master hangs the terminal up: its session leader gets SIGHUP, as when foot's window closes.
        synchronized(lock) {
            open = false
            PtyNative.close(fd)
            PtyNative.close(wakeWrite)
        }
        PtyNative.close(wakeRead)
        val code = PtyNative.waitFor(pid)
        exitCode = code
        onExit(code)
    }

    /** Sends [bytes] to the program as typed; false once the terminal is closed. */
    fun send(bytes: ByteArray): Boolean = synchronized(lock) { open && PtyNative.write(fd, bytes, 0, bytes.size) }

    /** Sends [text] as typed: characters, not keys, so the program's key protocol doesn't apply. */
    fun type(text: String) = send(text.encodeToByteArray())

    /** Presses [key]: see [Vt.key]. False when it sends nothing or the terminal is closed. */
    fun press(
        key: VtKey,
        mods: Int = 0,
        text: String? = null,
        unshifted: Int = 0,
    ): Boolean = vt.key(key, mods, text, unshifted)?.let(::send) ?: false

    /** Pastes [text]: bracketed when the program asked for it. */
    fun paste(text: String) = vt.paste(text)?.let(::send) ?: false

    /** Tells the program the terminal gained or lost focus, if it asked to know (Grok does). */
    fun focus(gained: Boolean) = vt.focus(gained)?.let(::send) ?: false

    /** The terminal's new size; the program gets SIGWINCH. */
    fun resize(
        cols: Int,
        rows: Int,
        cellWidth: Int = 0,
        cellHeight: Int = 0,
    ) {
        vt.resize(cols, rows, cellWidth, cellHeight)
        synchronized(lock) { if (open) PtyNative.resize(fd, rows, cols, cols * cellWidth, rows * cellHeight) }
    }

    /**
     * Closes the terminal. Its shell gets SIGHUP and passes it on to its jobs; what ignores it (nohup) runs on,
     * as after closing foot. [onExit] follows once the program has ended.
     */
    fun hangUp() {
        synchronized(lock) { if (open) PtyNative.wake(wakeWrite) }
    }

    /** Hangs up and frees [vt]. */
    override fun close() {
        hangUp()
        vt.close()
    }

    companion object {
        private const val BUFFER = 64 * 1024

        /**
         * A terminal in the guest, as foot opens one: the guest's login shell for root, or `bash -c` [command].
         * The shell leads the terminal's session (`setsid -c`), not proot: proot ignores the hangup that ends a
         * shell when its terminal closes. Throws IOException, also while Linux is stopped or without the library.
         */
        fun guest(
            proot: Proot,
            command: String?,
            cols: Int,
            rows: Int,
            cellWidth: Int = 0,
            cellHeight: Int = 0,
            onOutput: () -> Unit = {},
            onExit: (Int) -> Unit = {},
        ): TerminalSession {
            if (!TerminalLibrary.available) throw IOException("no built-in terminal in this build")
            val (argv, env) = proot.ptyCommand(guestScript(command))
            return TerminalSession(argv, env, proot.filesDir.path, cols, rows, cellWidth, cellHeight, false, onOutput, onExit)
        }

        /** The guest script [guest] runs. foot also sets COLORTERM. */
        fun guestScript(command: String?): String {
            val program = command?.let { "bash -c ${shellQuote(it)}" } ?: "\"\${shell:-/bin/bash}\""
            return "shell=\$(getent passwd \"\$(id -u)\" | cut -d: -f7); exec env COLORTERM=truecolor setsid -c $program"
        }
    }
}
