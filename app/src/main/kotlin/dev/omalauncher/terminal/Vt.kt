package dev.omalauncher.terminal

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

    private inline fun <T> live(block: (Long) -> T): T? = synchronized(this) { if (handle == 0L) null else block(handle) }

    /** Feeds [len] bytes the program wrote. */
    fun write(
        bytes: ByteArray,
        len: Int = bytes.size,
    ) {
        live { VtNative.write(it, bytes, len) }
    }

    /** What the terminal answered the program since the last call (cursor reports, device attributes), or null. */
    fun replies(): ByteArray? = live { VtNative.replies(it) }

    /** The program asked for the screen not to be drawn until it's done changing it (synchronized output). */
    val held get() = live { VtNative.held(it) } ?: false

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
     * types without modifiers. Null when it sends nothing.
     */
    fun key(
        key: VtKey,
        mods: Int = 0,
        text: String? = null,
        unshifted: Int = 0,
    ): ByteArray? = live { VtNative.key(it, key.code, mods, text, unshifted) }

    /** The default colours, as foot.ini's: ARGB, [cursor] 0 for the text's own; [palette] the first of the 256. */
    fun colors(
        fg: Int,
        bg: Int,
        cursor: Int,
        palette: IntArray,
    ) {
        live { VtNative.colors(it, fg, bg, cursor, palette) }
    }

    /** What the program asked of the terminal: [MOUSE], [ALTERNATE] and the others. */
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
                "title" -> VtEvent.Title(value)
                "clipboard" -> VtEvent.Copy(value)
                "notify" -> VtEvent.Notify(value.substringBefore('\n'), value.substringAfter('\n'))
                else -> null
            }
        }
    }

    /** The screen as plain text, trailing blanks trimmed. */
    fun text(): String = live { VtNative.text(it)?.decodeToString() }.orEmpty()

    /**
     * The cells on screen into [into], which grows to fit; false when the terminal is closed. Call from one thread.
     */
    fun cells(into: Cells): Boolean {
        var info = live { VtNative.cells(it, into.codepoints, into.fg, into.bg, into.flags, into.more) } ?: return false
        // The screen grew, or holds more long characters: read it again into arrays that fit. Twice at most, as the
        // first read of a screen too big says nothing about its characters.
        var tries = 0
        while ((info[5] == 0 || info[7] < 0) && tries++ < 2) {
            into.ensure(info[0] * info[1], more = -info[7])
            info = live { VtNative.cells(it, into.codepoints, into.fg, into.bg, into.flags, into.more) } ?: return false
        }
        if (info[5] == 0) return false
        into.cols = info[0]
        into.rows = info[1]
        into.cursorX = info[2]
        into.cursorY = info[3]
        into.cursorVisible = info[4] != 0
        into.cursorStyle = info[6]
        into.indexMore(info[7].coerceAtLeast(0))
        into.defaultFg = info[8]
        into.defaultBg = info[9]
        into.cursorColor = info[10]
        into.cursorWideTail = info[11] != 0
        return true
    }

    override fun close() {
        synchronized(this) {
            if (handle != 0L) VtNative.free(handle)
            handle = 0L
        }
    }

    companion object {
        const val SHIFT = 1
        const val CTRL = 2
        const val ALT = 4
        const val SUPER = 8

        /** [state]'s bits: the program reports the mouse; draws on the alternate screen; wants the wheel as arrow keys
         * there (1007); application cursor keys; the view is at the bottom; bracketed paste; focus reports. */
        const val MOUSE = 1
        const val ALTERNATE = 2
        const val ALTERNATE_SCROLL = 4
        const val CURSOR_KEYS = 8
        const val AT_BOTTOM = 16
        const val BRACKETED_PASTE = 32
        const val FOCUS_REPORTS = 64

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

    data class Title(
        val title: String,
    ) : VtEvent

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
    var rows = 0

    /** The cursor's cell, -1 when it's scrolled out of view. */
    var cursorX = -1
    var cursorY = -1
    var cursorVisible = false

    /** [Vt.CURSOR_BLOCK] and the others. */
    var cursorStyle = Vt.CURSOR_BLOCK

    /** The cursor is on the second cell of a wide character. */
    var cursorWideTail = false

    /** The colours cells without their own get, and the cursor's (0 for the text's under it), as ARGB. */
    var defaultFg = 0
    var defaultBg = 0
    var cursorColor = 0

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

    /** Cells of more than one code point, as {cell index, count, code points...}; see [text]. */
    var more = IntArray(256)
        private set

    /** Where each cell of [more] starts in it, by cell index. */
    private val moreAt = HashMap<Int, Int>()

    fun ensure(
        size: Int,
        more: Int = 0,
    ) {
        if (more > this.more.size) this.more = IntArray(more * 2)
        if (codepoints.size >= size) return
        codepoints = IntArray(size)
        fg = IntArray(size)
        bg = IntArray(size)
        flags = IntArray(size)
    }

    internal fun indexMore(used: Int) {
        moreAt.clear()
        var i = 0
        while (i + 1 < used) {
            moreAt[more[i]] = i
            i += 2 + more[i + 1]
        }
    }

    /** Whether cell [index] holds more than one code point. */
    fun isCluster(index: Int) = index in moreAt

    /** Cell [index]'s text: its whole grapheme cluster, empty for an empty cell. */
    fun text(index: Int): String {
        val at = moreAt[index]
        if (at != null) return String(more, at + 2, more[at + 1])
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
