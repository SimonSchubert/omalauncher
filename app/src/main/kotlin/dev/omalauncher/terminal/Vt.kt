package dev.omalauncher.terminal

import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * A terminal's state: what a program wrote, as a screen of cells with scrollback, and how keys are sent to it
 * (libghostty-vt, see cpp/vt.c). Safe to use from the session's reading thread and the UI thread at once; [cells]
 * from one thread only. Needs [TerminalLibrary.available].
 */
class Vt(
    cols: Int,
    rows: Int,
    cellWidth: Int = 0,
    cellHeight: Int = 0,
) : AutoCloseable {
    private var handle = VtNative.create(cols, rows, cellWidth, cellHeight).also { check(it != 0L) { "no terminal of $cols x $rows" } }

    /**
     * Keeps [close] out while a call runs, and no call out of another: vt.c has a lock of its own for the terminal,
     * which [cells] holds only while it takes what changed, so the program's output is read on while a frame is drawn.
     */
    private val closing = ReentrantReadWriteLock()

    private inline fun <T> live(block: (Long) -> T): T? {
        val running = closing.readLock()
        running.lock()
        try {
            return if (handle == 0L) null else block(handle)
        } finally {
            running.unlock()
        }
    }

    /**
     * Feeds [len] bytes the program wrote. Returns what that left to fetch: [REPLIES] and [EVENTS], so a reader
     * asks for them only when there are any.
     */
    fun write(
        bytes: ByteArray,
        len: Int = bytes.size,
    ): Int = live { VtNative.write(it, bytes, len) } ?: 0

    /** What the terminal answered the program since the last call (cursor reports, device attributes), or null. */
    fun replies(): ByteArray? = live { VtNative.replies(it) }

    /** The program asked for the screen not to be drawn until it's done changing it (synchronized output). */
    val held get() = state and HELD != 0

    fun resize(
        cols: Int,
        rows: Int,
        cellWidth: Int = 0,
        cellHeight: Int = 0,
    ) {
        live { VtNative.resize(it, cols, rows, cellWidth, cellHeight) }
    }

    /** Moves the view [rows] through the scrollback, up when negative. */
    fun scroll(rows: Int) {
        live { VtNative.scroll(it, rows) }
    }

    /**
     * The bytes pressing [key] with [mods] sends, as the program asked for them: plain, application cursor keys,
     * or the kitty keyboard protocol. [text] is what the key types ("A" with Shift), [unshifted] the character it
     * types without modifiers, [consumed] the [mods] that went into [text] (Shift for "A" or ":"): the kitty protocol
     * sends those keys as their text. Null when it sends nothing.
     */
    fun key(
        key: VtKey,
        mods: Int = 0,
        text: String? = null,
        unshifted: Int = 0,
        consumed: Int = 0,
    ): ByteArray? = live { VtNative.key(it, key.code, mods, consumed, text, unshifted) }

    /** The default colours, as foot.ini's: ARGB, [cursor] 0 for the text's own; [palette] the first of the 256. */
    fun colors(
        fg: Int,
        bg: Int,
        cursor: Int,
        palette: IntArray,
    ) {
        live { VtNative.colors(it, fg, bg, cursor, palette) }
    }

    /** What the program asked of the terminal: [MOUSE], [ALTERNATE] and the others. [Cells.state] has a frame's. */
    val state get() = live { VtNative.state(it) } ?: 0

    /** {total rows, the view's first row, rows in view}, scrollback included. */
    fun scrollbar(): LongArray? = live { VtNative.scrollbar(it) }

    fun scrollToBottom() {
        live { VtNative.scrollToBottom(it) }
    }

    /**
     * A mouse event at [x], [y] pixels in a screen of [width] x [height] with cells of [cellWidth] x [cellHeight]
     * after [padLeft], [padTop], as the program asked for them; null when it didn't. [action] is [PRESS], [RELEASE]
     * or [MOTION], [button] [LEFT] and the others or 0, [pressed] whether one is held during motion.
     */
    fun mouse(
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
    ): ByteArray? = live { VtNative.mouse(it, action, button, mods, x, y, width, height, cellWidth, cellHeight, padLeft, padTop, pressed) }

    /** What focus in or out sends: null unless the program asked for focus reports. */
    fun focus(gained: Boolean): ByteArray? = live { VtNative.focus(it, gained) }

    /** [text] pasted, as the program takes it: bracketed if it asked, made safe either way. */
    fun paste(text: String): ByteArray? = live { VtNative.paste(it, text.encodeToByteArray()) }

    /** The link (OSC 8) at a cell of the view. */
    fun link(
        col: Int,
        row: Int,
    ): String? = live { VtNative.link(it, col, row)?.decodeToString() }

    /** Selects from one cell of the view to another, both included. */
    fun select(
        col1: Int,
        row1: Int,
        col2: Int,
        row2: Int,
    ) = live { VtNative.select(it, col1, row1, col2, row2, 0) } ?: false

    /** Selects the word at a cell of the view; false where there is none. */
    fun selectWord(
        col: Int,
        row: Int,
    ) = live { VtNative.select(it, col, row, 0, 0, 1) } ?: false

    /** Moves the selection's first ([start]) or last end to a cell of the view, the other where it is. */
    fun moveSelection(
        start: Boolean,
        col: Int,
        row: Int,
    ) = live { VtNative.select(it, col, row, 0, 0, if (start) 2 else 3) } ?: false

    fun clearSelection() {
        live { VtNative.clearSelection(it) }
    }

    /** The selection's ends in order, as cells of the view {col1, row1, col2, row2}, -1 for one off it; null for none. */
    fun selection(): IntArray? = live { VtNative.selection(it) }

    /** The selected text, wrapped lines joined, trailing blanks trimmed. */
    fun selectedText(): String? = live { VtNative.selectedText(it)?.decodeToString() }

    /** What the program did besides drawing since the last call, oldest first; empty for nothing. */
    fun events(): List<VtEvent> {
        val raw = live { VtNative.events(it) } ?: return emptyList()
        return raw.toList().chunked(2).mapNotNull { (tag, value) ->
            when (tag) {
                "bell" -> VtEvent.Bell
                "clipboard" -> VtEvent.Copy(value)
                "notify" -> VtEvent.Notify(value.substringBefore('\n'), value.substringAfter('\n'))
                else -> null
            }
        }
    }

    /** The screen as plain text, trailing blanks trimmed. */
    fun text(): String = live { VtNative.text(it)?.decodeToString() }.orEmpty()

    /** The [Cells] that [cells] last filled, which then hold every row the terminal hasn't changed since. */
    private var read: Cells? = null
    private val info = IntArray(INFO_COUNT)

    /**
     * The screen into [into], which grows to fit; false when the terminal is closed. Only rows the program wrote to
     * since the last call are read, and [Cells.changed] says which of them differ now: a renderer redraws those,
     * after moving what it kept by [Cells.scrolled] rows.
     * (Other [Cells] than the last call's are filled whole.) While the program holds its screen ([held]) nothing is
     * read unless [whileHeld]: [into] stays as it was, with [Cells.state] saying so. Call from one thread.
     */
    fun cells(
        into: Cells,
        whileHeld: Boolean = true,
    ): Boolean {
        var all = read !== into || into.from !== this
        read = null
        // The screen grew, or holds more long characters: read it again into arrays that fit. Twice more at most, as the
        // first read of a screen too big says nothing about its characters.
        repeat(3) {
            val ok = live { VtNative.cells(it, into.codepoints, into.fg, into.bg, into.flags, into.more, into.changes, info, all, whileHeld) }
            if (ok != true) return false
            when (info[INFO_RESULT]) {
                RESULT_HELD -> {
                    into.state = info[INFO_STATE]
                    into.scrolled = 0
                    // Nothing was read, so nothing was lost: the next call goes on from what [into] holds.
                    if (!all) read = into
                    return true
                }
                RESULT_READ -> {
                    into.take(info)
                    into.from = this
                    read = into
                    return true
                }
                RESULT_SMALL -> into.ensure(info[INFO_COLS], info[INFO_ROWS])
                else -> into.ensure(info[INFO_COLS], info[INFO_ROWS], more = info[INFO_MORE])
            }
            all = true
        }
        return false
    }

    override fun close() {
        val alone = closing.writeLock()
        alone.lock()
        try {
            if (handle != 0L) VtNative.free(handle)
            handle = 0L
        } finally {
            alone.unlock()
        }
    }

    companion object {
        const val SHIFT = 1
        const val CTRL = 2
        const val ALT = 4
        const val SUPER = 8

        /** [state]'s bits: the program reports the mouse; draws on the alternate screen; application cursor keys; the
         * view is at the bottom; bracketed paste; it holds its screen (synchronized output). */
        const val MOUSE = 1
        const val ALTERNATE = 2
        const val CURSOR_KEYS = 8
        const val AT_BOTTOM = 16
        const val BRACKETED_PASTE = 32
        const val HELD = 128

        /** [write]'s bits: there is something for [replies], for [events]. */
        const val REPLIES = 1
        const val EVENTS = 2

        /** What vt.c's cells() reports, as it numbers them. */
        internal const val INFO_RESULT = 0
        internal const val INFO_COLS = 1
        internal const val INFO_ROWS = 2
        internal const val INFO_CURSOR_X = 3
        internal const val INFO_CURSOR_Y = 4
        internal const val INFO_CURSOR_VISIBLE = 5
        internal const val INFO_CURSOR_STYLE = 6
        internal const val INFO_CURSOR_COLOR = 7
        internal const val INFO_CURSOR_WIDE_TAIL = 8
        internal const val INFO_FG = 9
        internal const val INFO_BG = 10
        internal const val INFO_MORE = 11
        internal const val INFO_STATE = 12
        internal const val INFO_ALL = 13
        internal const val INFO_SCROLLED = 14
        internal const val INFO_COUNT = 15
        private const val RESULT_HELD = 0
        private const val RESULT_READ = 1
        private const val RESULT_SMALL = 2

        /** [mouse]'s actions and buttons, as libghostty-vt numbers them. */
        const val PRESS = 0
        const val RELEASE = 1
        const val MOTION = 2
        const val LEFT = 1
        const val RIGHT = 2
        const val MIDDLE = 3
        const val WHEEL_UP = 4
        const val WHEEL_DOWN = 5

        /** [Cells.cursorStyle]'s. */
        const val CURSOR_BAR = 0
        const val CURSOR_BLOCK = 1
        const val CURSOR_UNDERLINE = 2
        const val CURSOR_HOLLOW = 3
    }
}

/** Something a program did besides drawing: see [Vt.events]. */
sealed interface VtEvent {
    data object Bell : VtEvent

    /** It copied [text] (OSC 52). */
    data class Copy(
        val text: String,
    ) : VtEvent

    /** OSC 9 or 777. */
    data class Notify(
        val title: String,
        val body: String,
    ) : VtEvent
}

/** A screen's cells, row by row: see [Vt.cells]. */
class Cells {
    var cols = 0
        private set
    var rows = 0
        private set

    /** The cursor's cell, -1 when it's scrolled out of view. */
    var cursorX = -1
        private set
    var cursorY = -1
        private set
    var cursorVisible = false
        private set

    /** [Vt.CURSOR_BLOCK] and the others. */
    var cursorStyle = Vt.CURSOR_BLOCK
        private set

    /** The cursor is on the second cell of a wide character. */
    var cursorWideTail = false
        private set

    /** The colours cells without their own get, and the cursor's (0 for the text's under it), as ARGB. */
    var defaultFg = 0
        private set
    var defaultBg = 0
        private set
    var cursorColor = 0
        private set

    /** [Vt.state] as it was when these cells were asked for. */
    var state = 0
        internal set

    /**
     * How many rows the screen moved up between the last [Vt.cells] and the one before (down when negative): output
     * that scrolled it, or the view moving through the scrollback. Rows that only moved haven't [changed], so a
     * renderer that keeps something per row moves that by as much first; the rows that came into view have.
     */
    var scrolled = 0
        internal set

    /** The first code point of each cell; 0 for an empty one. */
    var codepoints = IntArray(0)
        private set

    /** Colours as ARGB, the terminal's defaults where a cell sets none. */
    var fg = IntArray(0)
        private set
    var bg = IntArray(0)
        private set

    /** [BOLD], [ITALIC] and the others. */
    var flags = IntArray(0)
        private set

    /** Per row, whether the last [Vt.cells] changed any of its cells; see [changed]. */
    internal var changes = BooleanArray(0)
        private set

    /** The last read's cells of more than one code point, as {cell index, count, code points...}. */
    internal var more = IntArray(256)
        private set

    /** The text of every [CLUSTER] cell, by cell index. */
    private val clusters = HashMap<Int, String>()

    /** The terminal these are of, for [Vt.cells] to tell cells it didn't fill. */
    internal var from: Vt? = null

    /** Room for a screen of [cols] by [rows], and [more] ints of long characters. What the arrays held is lost. */
    internal fun ensure(
        cols: Int,
        rows: Int,
        more: Int = 0,
    ) {
        if (more > this.more.size) this.more = IntArray(more * 2)
        if (changes.size < rows) changes = BooleanArray(rows)
        val size = cols * rows
        if (codepoints.size >= size) return
        codepoints = IntArray(size)
        fg = IntArray(size)
        bg = IntArray(size)
        flags = IntArray(size)
    }

    /** What vt.c's cells() reported beside the arrays it filled. */
    internal fun take(info: IntArray) {
        cols = info[Vt.INFO_COLS]
        rows = info[Vt.INFO_ROWS]
        cursorX = info[Vt.INFO_CURSOR_X]
        cursorY = info[Vt.INFO_CURSOR_Y]
        cursorVisible = info[Vt.INFO_CURSOR_VISIBLE] != 0
        cursorStyle = info[Vt.INFO_CURSOR_STYLE]
        cursorColor = info[Vt.INFO_CURSOR_COLOR]
        cursorWideTail = info[Vt.INFO_CURSOR_WIDE_TAIL] != 0
        defaultFg = info[Vt.INFO_FG]
        defaultBg = info[Vt.INFO_BG]
        state = info[Vt.INFO_STATE]
        scrolled = info[Vt.INFO_SCROLLED]
        if (info[Vt.INFO_ALL] != 0) clusters.clear()
        if (scrolled != 0 && clusters.isNotEmpty()) {
            // The long characters move with their rows; those of rows that left the screen go.
            val moved = clusters.entries.map { it.key - scrolled * cols to it.value }
            clusters.clear()
            for ((cell, text) in moved) if (cell >= 0 && cell < cols * rows) clusters[cell] = text
        }
        // The long characters of the rows read. One that only changed after its first code point shows in no array.
        var i = 0
        val used = info[Vt.INFO_MORE]
        while (i + 1 < used) {
            val cell = more[i]
            val text = String(more, i + 2, more[i + 1])
            if (clusters.put(cell, text) != text) changes[cell / cols] = true
            i += 2 + more[i + 1]
        }
        // Cells that were long characters and no longer are: their flags changed, so their rows count as changed.
        if (clusters.isNotEmpty()) clusters.keys.removeIf { flags[it] and CLUSTER == 0 }
    }

    /**
     * Whether the last [Vt.cells] changed row [row]: what a renderer has to draw again. A row the program wrote
     * the same cells to again hasn't changed.
     */
    fun changed(row: Int) = changes[row]

    /** Whether cell [index] holds more than one code point. */
    fun isCluster(index: Int) = flags[index] and CLUSTER != 0

    /** Cell [index]'s text: its whole grapheme cluster, empty for an empty cell. */
    fun text(index: Int): String {
        if (isCluster(index)) clusters[index]?.let { return it }
        val c = codepoints[index]
        return if (c == 0) "" else String(Character.toChars(c))
    }

    fun index(
        col: Int,
        row: Int,
    ) = row * cols + col

    /** The text of row [row], one character per cell, wide characters once. */
    fun row(row: Int): String =
        buildString {
            for (x in 0 until cols) {
                val i = index(x, row)
                if (flags[i] and SPACER != 0) continue
                append(text(i).ifEmpty { " " })
            }
        }.trimEnd()

    companion object {
        const val BOLD = 1 shl 0
        const val ITALIC = 1 shl 1
        const val UNDERLINE = 1 shl 2
        const val INVERSE = 1 shl 3

        /** The first of the two cells of a wide character. */
        const val WIDE = 1 shl 4

        /** The second cell of a wide character, or the end of a line it didn't fit on: draws nothing. */
        const val SPACER = 1 shl 5
        const val FAINT = 1 shl 6

        /** The cell has a background colour of its own. */
        const val HAS_BG = 1 shl 7
        const val STRIKETHROUGH = 1 shl 8
        const val SELECTED = 1 shl 9

        /** The cell is part of a link (OSC 8): see [Vt.link]. */
        const val LINK = 1 shl 10
        const val INVISIBLE = 1 shl 11

        /** The cell holds more than one code point (an emoji sequence, combining marks): see [text]. */
        const val CLUSTER = 1 shl 12
    }
}

/** The keys the terminal sends, as libghostty-vt numbers them (GhosttyKey, include/ghostty/vt/key/event.h). */
enum class VtKey(
    val code: Int,
) {
    BACKQUOTE(1),
    BACKSLASH(2),
    BRACKET_LEFT(3),
    BRACKET_RIGHT(4),
    COMMA(5),
    DIGIT_0(6),
    DIGIT_1(7),
    DIGIT_2(8),
    DIGIT_3(9),
    DIGIT_4(10),
    DIGIT_5(11),
    DIGIT_6(12),
    DIGIT_7(13),
    DIGIT_8(14),
    DIGIT_9(15),
    EQUAL(16),
    A(20),
    B(21),
    C(22),
    D(23),
    E(24),
    F(25),
    G(26),
    H(27),
    I(28),
    J(29),
    K(30),
    L(31),
    M(32),
    N(33),
    O(34),
    P(35),
    Q(36),
    R(37),
    S(38),
    T(39),
    U(40),
    V(41),
    W(42),
    X(43),
    Y(44),
    Z(45),
    MINUS(46),
    PERIOD(47),
    QUOTE(48),
    SEMICOLON(49),
    SLASH(50),
    BACKSPACE(53),
    ENTER(58),
    SPACE(63),
    TAB(64),
    DELETE(68),
    END(69),
    HOME(71),
    INSERT(72),
    PAGE_DOWN(73),
    PAGE_UP(74),
    ARROW_DOWN(75),
    ARROW_LEFT(76),
    ARROW_RIGHT(77),
    ARROW_UP(78),
    ESCAPE(120),
    F1(121),
    F2(122),
    F3(123),
    F4(124),
    F5(125),
    F6(126),
    F7(127),
    F8(128),
    F9(129),
    F10(130),
    F11(131),
    F12(132),
}
