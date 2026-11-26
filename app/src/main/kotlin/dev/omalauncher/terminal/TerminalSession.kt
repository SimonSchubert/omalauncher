package dev.omalauncher.terminal

import dev.omalauncher.linux.Proot
import dev.omalauncher.linux.shellQuote
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * A program on a pseudo-terminal, with [vt] the screen it draws: what it writes is read on a thread of the
 * session's own into [vt] ([onOutput] after each read), keys and text go to it with [press] and [type] (written
 * by a second thread, so a program that isn't reading holds up neither the caller nor the reading), and
 * [hangUp] closes the terminal as closing foot's window does. [onExit] gets its exit code, on the reading thread.
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

    /** Whether [send] still takes anything, and whether the files are closed: [lock] guards the second. */
    @Volatile private var open = true
    private var closed = false

    /** What [send] was given, for [writer] to write in that order; [END] stops it. */
    private val outbox = LinkedBlockingQueue<ByteArray>()
    private val writer: Thread

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
        writer = thread(name = "terminal-$pid-keys", isDaemon = true) { write() }
        thread(name = "terminal-$pid", isDaemon = true) { pump() }
    }

    private fun write() {
        while (true) {
            val bytes = outbox.take()
            if (bytes === END || !PtyNative.write(fd, wakeRead, bytes, 0, bytes.size)) break
        }
    }

    private fun pump() {
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = PtyNative.read(fd, wakeRead, buf)
            if (n <= 0) break
            val left = vt.write(buf, n)
            if (left and Vt.REPLIES != 0) vt.replies()?.let(::send)
            if (left and Vt.EVENTS != 0) for (e in vt.events()) onEvent(e)
            onOutput()
        }
        // The writer first, out of whatever it waits in, as it uses the same files.
        open = false
        outbox.put(END)
        synchronized(lock) { PtyNative.wake(wakeWrite) }
        writer.join()
        // Closing the master hangs the terminal up: its session leader gets SIGHUP, as when foot's window closes.
        synchronized(lock) {
            PtyNative.close(fd)
            PtyNative.close(wakeWrite)
            closed = true
        }
        PtyNative.close(wakeRead)
        val code = PtyNative.waitFor(pid)
        exitCode = code
        onExit(code)
    }

    /**
     * Sends [bytes] to the program as typed, after what was sent before; false once the terminal is closed. Doesn't
     * wait for the program to take them.
     */
    fun send(bytes: ByteArray): Boolean {
        if (!open) return false
        outbox.put(bytes)
        return true
    }

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
        synchronized(lock) { if (!closed) PtyNative.resize(fd, rows, cols, cols * cellWidth, rows * cellHeight) }
    }

    /**
     * Closes the terminal. Its shell gets SIGHUP and passes it on to its jobs; what ignores it (nohup) runs on,
     * as after closing foot. [onExit] follows once the program has ended.
     */
    fun hangUp() {
        synchronized(lock) { if (!closed) PtyNative.wake(wakeWrite) }
    }

    /** Hangs up and frees [vt]. */
    override fun close() {
        hangUp()
        vt.close()
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private val END = ByteArray(0)

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
