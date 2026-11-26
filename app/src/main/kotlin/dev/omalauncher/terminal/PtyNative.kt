package dev.omalauncher.terminal

import android.util.Log

/**
 * The built-in terminal's native library, libterminal (cpp/pty.c, cpp/vt.c with libghostty-vt). Built only where
 * scripts/fetch-ghostty-vt.sh has run; without it there is no built-in terminal, and nothing offers one.
 */
object TerminalLibrary {
    val available by lazy {
        try {
            System.loadLibrary("terminal")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w("terminal", "no built-in terminal", e)
            false
        }
    }
}

/** JNI bindings, see cpp/pty.c: a pseudo-terminal and the program on its other end. */
object PtyNative {
    /** {master fd, pid} of [argv] started on a new terminal, or null; see pty.c for [leader]. */
    @JvmStatic external fun start(
        argv: Array<String>,
        env: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int,
        leader: Boolean,
    ): IntArray?

    @JvmStatic external fun resize(
        fd: Int,
        rows: Int,
        cols: Int,
        width: Int,
        height: Int,
    )

    /** Bytes read into [buf]; 0 when [wake] was woken; -1 once the program's side is closed. */
    @JvmStatic external fun read(
        fd: Int,
        wake: Int,
        buf: ByteArray,
    ): Int

    /** Writes [buf], waiting for the program to take it; false once it's gone or [wake] was woken. */
    @JvmStatic external fun write(
        fd: Int,
        wake: Int,
        buf: ByteArray,
        off: Int,
        len: Int,
    ): Boolean

    /** {read end, write end} of a pipe for [read]'s and [write]'s wake. */
    @JvmStatic external fun wakePipe(): IntArray?

    @JvmStatic external fun wake(fd: Int)

    @JvmStatic external fun close(fd: Int)

    /** The exit code of [pid], or 128 + the signal that killed it. */
    @JvmStatic external fun waitFor(pid: Int): Int

    @JvmStatic external fun kill(
        pid: Int,
        signal: Int,
    )
}

/** JNI bindings, see cpp/vt.c: libghostty-vt's terminal state. [Vt] is the Kotlin side. */
object VtNative {
    @JvmStatic external fun create(
        cols: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    ): Long

    @JvmStatic external fun free(h: Long)

    @JvmStatic external fun write(
        h: Long,
        buf: ByteArray,
        len: Int,
    ): Int

    @JvmStatic external fun replies(h: Long): ByteArray?

    @JvmStatic external fun resize(
        h: Long,
        cols: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    )

    @JvmStatic external fun scroll(
        h: Long,
        delta: Int,
    )

    @JvmStatic external fun key(
        h: Long,
        key: Int,
        mods: Int,
        consumed: Int,
        text: String?,
        unshifted: Int,
    ): ByteArray?

    @JvmStatic external fun text(h: Long): ByteArray?

    @JvmStatic external fun cells(
        h: Long,
        codepoints: IntArray,
        fg: IntArray,
        bg: IntArray,
        flags: IntArray,
        more: IntArray,
        changed: BooleanArray,
        info: IntArray,
        all: Boolean,
        whileHeld: Boolean,
    ): Boolean

    @JvmStatic external fun colors(
        h: Long,
        fg: Int,
        bg: Int,
        cursor: Int,
        palette: IntArray?,
    )

    @JvmStatic external fun state(h: Long): Int

    @JvmStatic external fun scrollbar(h: Long): LongArray?

    @JvmStatic external fun scrollToBottom(h: Long)

    @JvmStatic external fun mouse(
        h: Long,
        action: Int,
        button: Int,
        mods: Int,
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        cellWidth: Int,
        cellHeight: Int,
        padLeft: Int,
        padTop: Int,
        pressed: Boolean,
    ): ByteArray?

    @JvmStatic external fun focus(
        h: Long,
        gained: Boolean,
    ): ByteArray?

    @JvmStatic external fun paste(
        h: Long,
        text: ByteArray,
    ): ByteArray?

    @JvmStatic external fun link(
        h: Long,
        x: Int,
        y: Int,
    ): ByteArray?

    @JvmStatic external fun select(
        h: Long,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        how: Int,
    ): Boolean

    @JvmStatic external fun clearSelection(h: Long)

    @JvmStatic external fun selection(h: Long): IntArray?

    @JvmStatic external fun selectedText(h: Long): ByteArray?

    @JvmStatic external fun events(h: Long): Array<String>?
}
