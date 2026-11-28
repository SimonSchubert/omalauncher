package dev.omalauncher.terminal

import dev.omalauncher.HostNative
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * cpp/vt.c over libghostty-vt's host build: what the screen holds after escape sequences, and the bytes keys send.
 * Skipped without a C compiler or scripts/fetch-ghostty-vt.sh's library.
 */
class VtTest {
    private val vt = Vt(20, 6)
    private val cells = Cells()

    @After fun close() = vt.close()

    private fun write(s: String) {
        vt.write(s.encodeToByteArray())
    }

    private fun screen(): Cells = cells.also { assertTrue(vt.cells(it)) }

    private fun key(
        key: VtKey,
        mods: Int = 0,
        text: String? = null,
        unshifted: Int = 0,
        consumed: Int = 0,
    ) = vt.key(key, mods, text, unshifted, consumed)?.decodeToString()

    @Test fun text() {
        write("hello\r\nworld")
        assertEquals("hello\nworld", vt.text())
    }

    @Test fun cursorMoves() {
        write("\u001b[3;5Hx\u001b[1;2Hy")
        val s = screen()
        assertEquals(" y", s.row(0))
        assertEquals("    x", s.row(2))
        assertEquals(2, s.cursorX)
        assertEquals(0, s.cursorY)
        assertTrue(s.cursorVisible)
        write("\u001b[?25l")
        assertFalse(screen().cursorVisible)
    }

    @Test fun colours() {
        write("\u001b[38;2;255;128;0mA\u001b[48;5;196mB\u001b[0mC\u001b[1;3;4;7mD")
        val s = screen()
        assertEquals(0xffff8000.toInt(), s.fg[0])
        assertEquals(0xffff0000.toInt(), s.bg[1])
        assertTrue(s.flags[1] and Cells.HAS_BG != 0)
        // C is back to the defaults: the screen's own colours, no background of its own.
        assertEquals(s.fg[5], s.fg[2])
        assertEquals(s.bg[5], s.bg[2])
        assertFalse(s.flags[2] and Cells.HAS_BG != 0)
        val d = s.flags[3]
        assertEquals(Cells.BOLD or Cells.ITALIC or Cells.UNDERLINE or Cells.INVERSE, d and (Cells.BOLD or Cells.ITALIC or Cells.UNDERLINE or Cells.INVERSE))
    }

    @Test fun wideCharacters() {
        write("日x🙂é|")
        val s = screen()
        assertEquals(0x65e5, s.codepoints[0])
        assertTrue(s.flags[0] and Cells.WIDE != 0)
        assertTrue(s.flags[1] and Cells.SPACER != 0)
        assertEquals('x'.code, s.codepoints[2])
        assertEquals(0x1f642, s.codepoints[3])
        assertTrue(s.flags[4] and Cells.SPACER != 0)
        // A combining accent joins the e before it: one cell.
        assertEquals('e'.code, s.codepoints[5])
        assertEquals('|'.code, s.codepoints[6])
        assertEquals(7, s.cursorX)
    }

    @Test fun alternateScreen() {
        write("main")
        write("\u001b[?1049h\u001b[Halt")
        assertEquals("alt", vt.text())
        write("\u001b[?1049l")
        assertEquals("main", vt.text())
    }

    @Test fun scrollRegion() {
        write("1\r\n2\r\n3\r\n4\r\n5\r\n6")
        // Rows 2 to 4 scroll; a line feed at its bottom moves only them.
        write("\u001b[2;4r\u001b[4;1H\n")
        val s = screen()
        assertEquals(listOf("1", "3", "4", "", "5", "6"), (0 until 6).map(s::row))
    }

    @Test fun scrollback() {
        write((1..20).joinToString("\r\n") { "line $it" })
        assertEquals("line 15", screen().row(0))
        vt.scroll(-5)
        assertEquals("line 10", screen().row(0))
        vt.scroll(5)
        assertEquals("line 15", screen().row(0))
    }

    @Test fun cellsGrowWithTheScreen() {
        assertTrue(vt.cells(cells))
        vt.resize(40, 10)
        write("\u001b[10;40Hz")
        val s = screen()
        assertEquals(40, s.cols)
        assertEquals(10, s.rows)
        assertEquals('z'.code, s.codepoints[s.index(39, 9)])
    }

    @Test fun repliesToTheProgram() {
        write("\u001b[2;3H\u001b[6n")
        assertEquals("\u001b[2;3R", vt.replies()?.decodeToString())
        assertEquals(null, vt.replies())
        write("\u001b[c")
        assertTrue(vt.replies()!!.decodeToString().startsWith("\u001b[?"))
    }

    @Test fun synchronizedOutputHoldsTheScreen() {
        assertFalse(vt.held)
        write("\u001b[?2026h")
        assertTrue(vt.held)
        write("\u001b[?2026l")
        assertFalse(vt.held)
    }

    @Test fun legacyKeys() {
        assertEquals("\r", key(VtKey.ENTER))
        assertEquals("a", key(VtKey.A, text = "a", unshifted = 'a'.code))
        // Shift that went into the text: sent as the text.
        assertEquals("A", key(VtKey.A, Vt.SHIFT, "A", 'a'.code, consumed = Vt.SHIFT))
        assertEquals(":", key(VtKey.SEMICOLON, Vt.SHIFT, ":", ';'.code, consumed = Vt.SHIFT))
        assertEquals("A", key(VtKey.A, Vt.SHIFT, text = "A", unshifted = 'a'.code))
        assertEquals("\u0003", key(VtKey.C, Vt.CTRL, unshifted = 'c'.code))
        assertEquals("\u007f", key(VtKey.BACKSPACE))
        assertEquals("\t", key(VtKey.TAB))
        assertEquals("\u001b", key(VtKey.ESCAPE))
        assertEquals("\u001b[A", key(VtKey.ARROW_UP))
        assertEquals("\u001b[1;5C", key(VtKey.ARROW_RIGHT, Vt.CTRL))
        assertEquals("\u001b[3~", key(VtKey.DELETE))
        assertEquals("\u001bOP", key(VtKey.F1))
        // Application cursor keys (vim, less).
        write("\u001b[?1h")
        assertEquals("\u001bOA", key(VtKey.ARROW_UP))
    }

    @Test fun kittyKeyboardProtocol() {
        // Claude Code turns on "disambiguate escape codes": Shift+Enter then differs from Enter.
        val plain = key(VtKey.ENTER, Vt.SHIFT)
        write("\u001b[>1u")
        assertEquals("\u001b[13;2u", key(VtKey.ENTER, Vt.SHIFT))
        assertNotEquals(plain, key(VtKey.ENTER, Vt.SHIFT))
        assertEquals("\r", key(VtKey.ENTER))
        assertEquals("\u001b[27u", key(VtKey.ESCAPE))
        assertEquals("\u001b[99;5u", key(VtKey.C, Vt.CTRL, unshifted = 'c'.code))
        assertEquals("a", key(VtKey.A, text = "a", unshifted = 'a'.code))
        // Shift that went into the text: sent as the text.
        assertEquals("A", key(VtKey.A, Vt.SHIFT, "A", 'a'.code, consumed = Vt.SHIFT))
        assertEquals(":", key(VtKey.SEMICOLON, Vt.SHIFT, ":", ';'.code, consumed = Vt.SHIFT))
        // Popping the flags goes back to the legacy encoding.
        write("\u001b[<u")
        assertEquals("\u001b", key(VtKey.ESCAPE))
    }

    @Test fun closedTerminalAnswersNothing() {
        vt.close()
        assertEquals("", vt.text())
        assertEquals(null, key(VtKey.ENTER))
        assertFalse(vt.cells(cells))
        vt.write("x".encodeToByteArray())
    }

    @Test fun utf8SplitAcrossWrites() {
        val bytes = "日本".encodeToByteArray()
        vt.write(bytes.copyOfRange(0, 2), 2)
        vt.write(bytes.copyOfRange(2, bytes.size))
        assertEquals("日本", vt.text())
        assertArrayEquals(intArrayOf(0x65e5, 0, 0x672c), screen().codepoints.copyOfRange(0, 3))
    }

    @Test fun themeColours() {
        val palette = IntArray(16) { 0xff000000.toInt() or (it * 0x101010) }
        vt.colors(0xffeeeeee.toInt(), 0xff111111.toInt(), 0xffff0000.toInt(), palette)
        write("a\u001b[31mb\u001b[38;5;196mc")
        val s = screen()
        assertEquals(0xffeeeeee.toInt(), s.defaultFg)
        assertEquals(0xff111111.toInt(), s.defaultBg)
        assertEquals(0xffff0000.toInt(), s.cursorColor)
        assertEquals(0xffeeeeee.toInt(), s.fg[0])
        assertEquals(palette[1], s.fg[1])
        // Past the 16 a theme sets, xterm's cube.
        assertEquals(0xffff0000.toInt(), s.fg[2])
    }

    @Test fun graphemeClusters() {
        // e with a combining acute accent; then a family emoji (ZWJ sequence), one character once the program turns
        // on grapheme clustering (mode 2027), as in Ghostty.
        write("e\u0301 \u001b[?2027h\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67.")
        val s = screen()
        assertTrue(s.isCluster(0))
        assertEquals("e\u0301", s.text(0))
        assertEquals(" ", s.text(1))
        assertEquals("", s.text(3))
        assertEquals("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", s.text(2))
        assertTrue(s.flags[2] and Cells.WIDE != 0)
        assertEquals(".", s.text(4))
    }

    @Test fun manyClustersGrowTheirArray() {
        val big = Vt(80, 24)
        try {
            repeat(23) { big.write("e\u0301".repeat(79).encodeToByteArray() + "\r\n".encodeToByteArray()) }
            val s = Cells()
            assertTrue(big.cells(s))
                assertEquals("e\u0301", s.text(s.index(78, 22)))
            assertEquals("e\u0301", s.row(22).substring(0, 2))
        } finally {
            big.close()
        }
    }

    @Test fun cursorStyle() {
        assertEquals(Vt.CURSOR_BLOCK, screen().cursorStyle)
        write("\u001b[6 q")
        assertEquals(Vt.CURSOR_BAR, screen().cursorStyle)
        write("\u001b[4 q")
        assertEquals(Vt.CURSOR_UNDERLINE, screen().cursorStyle)
    }

    @Test fun links() {
        write("go \u001b]8;;https://example.com/x\u001b\\here\u001b]8;;\u001b\\ now")
        val s = screen()
        assertTrue(s.flags[3] and Cells.LINK != 0)
        assertFalse(s.flags[0] and Cells.LINK != 0)
        assertEquals("https://example.com/x", vt.link(4, 0))
        assertEquals(null, vt.link(0, 0))
    }

    @Test fun selection() {
        write("one two three\r\nfour")
        assertEquals(null, vt.selection())
        assertTrue(vt.selectWord(5, 0))
        assertEquals("two", vt.selectedText())
        assertArrayEquals(intArrayOf(4, 0, 6, 0), vt.selection())
        assertTrue(screen().flags[4] and Cells.SELECTED != 0)
        assertTrue(vt.moveSelection(start = false, col = 3, row = 1))
        assertEquals("two three\nfour", vt.selectedText())
        assertTrue(vt.moveSelection(start = true, col = 0, row = 0))
        assertEquals("one two three\nfour", vt.selectedText())
        // Dragged before its start, the end becomes the start.
        assertTrue(vt.select(2, 1, 8, 0))
        assertEquals("three\nfou", vt.selectedText())
        vt.clearSelection()
        assertEquals(null, vt.selectedText())
    }

    @Test fun selectionJoinsWrappedLines() {
        write("a".repeat(25))
        assertTrue(vt.select(0, 0, 4, 1))
        assertEquals("a".repeat(25), vt.selectedText())
    }

    @Test fun paste() {
        assertEquals("ls\r", vt.paste("ls\n")?.decodeToString())
        write("\u001b[?2004h")
        assertTrue(vt.state and Vt.BRACKETED_PASTE != 0)
        assertEquals("\u001b[200~ls\nrm\u001b[201~", vt.paste("ls\nrm")?.decodeToString())
        // A paste can't end itself early.
        assertFalse(vt.paste("x\u001b[201~y")!!.decodeToString().contains("x\u001b[201~y"))
    }

    @Test fun focusReports() {
        assertEquals(null, vt.focus(true))
        write("\u001b[?1004h")
        assertEquals("\u001b[I", vt.focus(true)?.decodeToString())
        assertEquals("\u001b[O", vt.focus(false)?.decodeToString())
    }

    @Test fun mouseReports() {
        fun click(action: Int) = vt.mouse(action, Vt.LEFT, 0, 25f, 35f, 200, 120, 10, 20, 0, 0, false)?.decodeToString()
        assertEquals(null, click(Vt.PRESS))
        assertFalse(vt.state and Vt.MOUSE != 0)
        write("\u001b[?1000h\u001b[?1006h")
        assertTrue(vt.state and Vt.MOUSE != 0)
        // Column 3, row 2, counted from 1.
        assertEquals("\u001b[<0;3;2M", click(Vt.PRESS))
        assertEquals("\u001b[<0;3;2m", click(Vt.RELEASE))
        assertEquals("\u001b[<64;3;2M", vt.mouse(Vt.PRESS, Vt.WHEEL_UP, 0, 25f, 35f, 200, 120, 10, 20, 0, 0, false)?.decodeToString())
    }

    @Test fun state() {
        assertTrue(vt.state and Vt.AT_BOTTOM != 0)
        write("\u001b[?1049h\u001b[?1h")
        assertTrue(vt.state and Vt.ALTERNATE != 0)
        assertTrue(vt.state and Vt.CURSOR_KEYS != 0)
        write("\u001b[?1049l")
        repeat(20) { write("line $it\r\n") }
        vt.scroll(-3)
        assertFalse(vt.state and Vt.AT_BOTTOM != 0)
        val (total, offset, len) = vt.scrollbar()!!
        assertEquals(6L, len)
        assertEquals(total - len - 3, offset)
        vt.scrollToBottom()
        assertTrue(vt.state and Vt.AT_BOTTOM != 0)
    }

    @Test fun events() {
        assertEquals(emptyList<VtEvent>(), vt.events())
        write("\u0007\u001b]2;my title\u0007\u001b]52;c;aMOkbGxv\u0007\u001b]777;notify;Done;All passed\u0007")
        assertEquals(
            listOf(VtEvent.Bell, VtEvent.Copy("hällo"), VtEvent.Notify("Done", "All passed")),
            vt.events(),
        )
        assertEquals(emptyList<VtEvent>(), vt.events())
    }

    private fun changedRows(s: Cells) = (0 until s.rows).filter(s::changed)

    @Test fun onlyChangedRowsCountAsChanged() {
        write("one\r\ntwo\r\nthree")
        // A first read is of everything.
        assertEquals((0 until 6).toList(), changedRows(screen()))
        assertEquals(emptyList<Int>(), changedRows(screen()))
        write("\u001b[2;1Htoo")
        val s = screen()
        assertEquals(listOf(1), changedRows(s))
        assertEquals("one", s.row(0))
        assertEquals("too", s.row(1))
        assertEquals("three", s.row(2))
        // The same text written again changes nothing; moving the cursor neither.
        write("\u001b[2;1Htoo\u001b[3;2H")
        assertEquals(emptyList<Int>(), changedRows(screen()))
        assertEquals(1, s.cursorX)
        assertEquals(2, s.cursorY)
        // Other cells than the last read's get the whole screen.
        val other = Cells()
        assertTrue(vt.cells(other))
        assertEquals((0 until 6).toList(), changedRows(other))
        assertEquals("too", other.row(1))
        assertEquals((0 until 6).toList(), changedRows(screen()))
    }

    @Test fun whatChangesTheScreenBesidesOutputIsReadToo() {
        write("one two\r\nthree\r\n" + (1..10).joinToString("\r\n") { "line $it" })
        screen()
        // A selection, and taking it away.
        assertTrue(vt.select(0, 1, 2, 2))
        var s = screen()
        assertEquals(listOf(1, 2), changedRows(s))
        assertTrue(s.flags[s.index(0, 1)] and Cells.SELECTED != 0)
        assertTrue(s.flags[s.index(2, 2)] and Cells.SELECTED != 0)
        assertFalse(s.flags[s.index(3, 2)] and Cells.SELECTED != 0)
        vt.clearSelection()
        s = screen()
        assertEquals(listOf(1, 2), changedRows(s))
        assertFalse(s.flags[s.index(0, 1)] and Cells.SELECTED != 0)
        // The scrollback.
        vt.scroll(-2)
        s = screen()
        assertEquals("line 3", s.row(0))
        assertEquals(listOf(0, 1), changedRows(s))
        vt.scrollToBottom()
        assertEquals("line 5", screen().row(0))
        // The theme's colours.
        val before = s.fg[0]
        vt.colors(before xor 0x00ffffff, 0xff111111.toInt(), 0, IntArray(16) { 0xff000000.toInt() or (it * 0x101010) })
        s = screen()
        assertEquals(before xor 0x00ffffff, s.fg[0])
        assertEquals(0xff111111.toInt(), s.bg[0])
        assertEquals((0 until 6).toList(), changedRows(s))
        // Another size.
        vt.resize(10, 4)
        s = screen()
        assertEquals((0 until 4).toList(), changedRows(s))
        assertEquals("line 10", s.row(s.cursorY))
    }

    @Test fun aScreenThatScrollsMovesItsRows() {
        write((1..6).joinToString("\r\n") { "line $it" })
        assertEquals(0, screen().scrolled)
        // Output at the bottom: every row moves up one, and only the new one has changed.
        write("\r\nline 7")
        var s = screen()
        assertEquals(1, s.scrolled)
        assertEquals(listOf(5), changedRows(s))
        assertEquals((2..7).map { "line $it" }, (0 until 6).map(s::row))
        assertEquals(0, screen().scrolled)
        // Three more lines, one of them like the row it lands on: it still counts.
        write("\r\nline 8\r\nline 9\r\nline 7")
        s = screen()
        assertEquals(3, s.scrolled)
        assertEquals(listOf(3, 4, 5), changedRows(s))
        assertEquals(listOf("line 5", "line 6", "line 7", "line 8", "line 9", "line 7"), (0 until 6).map(s::row))
        // Back through the scrollback, and to the bottom again.
        vt.scroll(-2)
        s = screen()
        assertEquals(-2, s.scrolled)
        assertEquals(listOf(0, 1), changedRows(s))
        assertEquals(listOf("line 3", "line 4", "line 5", "line 6", "line 7", "line 8"), (0 until 6).map(s::row))
        vt.scrollToBottom()
        s = screen()
        assertEquals(2, s.scrolled)
        assertEquals(listOf(4, 5), changedRows(s))
        assertEquals("line 7", s.row(5))
        // A row that changes while it moves, and long characters that move with theirs.
        write("\u001b[3;1He\u0301")
        screen()
        write("\u001b[1;1HLINE\u001b[6;1H\r\nlast")
        s = screen()
        assertEquals(1, s.scrolled)
        assertEquals(listOf(5), changedRows(s))
        assertEquals("e\u0301ine 7", s.row(1))
        // Further than the screen is high, nothing is where it was.
        write("\r\n".repeat(7) + "far")
        s = screen()
        assertEquals(0, s.scrolled)
        assertEquals("far", s.row(5))
    }

    @Test fun aLongCharacterThatChangesAfterItsFirstCodePoint() {
        write("ab\r\ne\u0301")
        screen()
        write("\re\u0302")
        var s = screen()
        assertEquals(listOf(1), changedRows(s))
        assertEquals("e\u0302", s.text(s.index(0, 1)))
        write("\re")
        s = screen()
        assertEquals(listOf(1), changedRows(s))
        assertFalse(s.isCluster(s.index(0, 1)))
        assertEquals("e", s.text(s.index(0, 1)))
    }

    @Test fun aHeldScreenIsReadOnlyWhenAsked() {
        write("before")
        screen()
        write("\u001b[?2026h\rafter!")
        assertTrue(vt.cells(cells, whileHeld = false))
        assertTrue(cells.state and Vt.HELD != 0)
        assertEquals("before", cells.row(0))
        assertEquals(emptyList<Int>(), changedRows(cells))
        // Asked to, or once the program lets go, what it wrote meanwhile is there.
        assertTrue(vt.cells(cells, whileHeld = true))
        assertEquals("after!", cells.row(0))
        assertEquals(listOf(0), changedRows(cells))
        write("\u001b[2;1Hmore\u001b[?2026l")
        assertTrue(vt.cells(cells, whileHeld = false))
        assertFalse(cells.state and Vt.HELD != 0)
        assertEquals("more", cells.row(1))
        assertEquals(listOf(1), changedRows(cells))
    }

    @Test fun writeSaysWhatThereIsToFetch() {
        assertEquals(0, vt.write("plain".encodeToByteArray()))
        assertEquals(Vt.REPLIES, vt.write("\u001b[6n".encodeToByteArray()))
        assertEquals(Vt.REPLIES or Vt.EVENTS, vt.write("\u0007".encodeToByteArray()))
        vt.replies()
        assertEquals(Vt.EVENTS, vt.write("x".encodeToByteArray()))
        vt.events()
        assertEquals(0, vt.write("x".encodeToByteArray()))
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun build() = HostNative.load("vt.c", extra = HostNative.ghosttyVt())
    }
}
