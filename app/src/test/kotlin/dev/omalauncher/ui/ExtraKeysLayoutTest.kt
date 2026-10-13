package dev.omalauncher.ui

import dev.omalauncher.wayland.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExtraKeysLayoutTest {
    private val ctrl = Keys.KEY_LEFTCTRL
    private val alt = Keys.KEY_LEFTALT
    private val shift = Keys.KEY_LEFTSHIFT

    private fun parse(spec: String) = ExtraKeysLayout.parse(spec)

    private fun plain(
        spec: String,
        row: Int = 0,
        key: Int = 0,
    ) = parse(spec)[row][key] as Plain

    private fun problem(spec: String): String {
        try {
            parse(spec)
        } catch (e: IllegalArgumentException) {
            return e.message!!
        }
        fail("parsed: $spec")
        throw AssertionError()
    }

    @Test fun theDefaultIsTermuxsTwoRowsWithTheClipboardKeys() {
        assertEquals(ExtraKeysLayout.Default, parse("[[ESC, /, -, HOME, UP, END, PGUP, COPY], [TAB, CTRL, ALT, LEFT, DOWN, RIGHT, PGDN, PASTE]]"))
        assertSame(ExtraKeysLayout.Default, ExtraKeysLayout.of(null))
    }

    @Test fun emptyHidesTheBar() {
        assertEquals(emptyList<List<ExtraKey>>(), parse("[]"))
        assertEquals(emptyList<List<ExtraKey>>(), parse("[[]]"))
        assertEquals(emptyList<List<ExtraKey>>(), ExtraKeysLayout.of("[]"))
    }

    @Test fun namesQuotedEitherWayOrBareInAnyCase() {
        val row = parse("""[['ESC', "tab", Home, pgdn, F5, F12, del, '"']]""")[0]
        assertEquals(listOf("esc", "tab", "home", "pgdn", "f5", "f12", "del", "\""), row.map { it.label })
        assertEquals(listOf(Keys.KEY_ESC, Keys.KEY_TAB, Keys.KEY_HOME, Keys.KEY_PAGEDOWN, 63, 88, Keys.KEY_DELETE), row.take(7).map { (it as Plain).strokes.single().code })
        assertEquals(listOf(shift), (row[7] as Plain).strokes.single().held)
    }

    @Test fun modifiersLatchAndArrowsRepeat() {
        val row = parse("[[CTRL, ALT, SHIFT, UP, LEFT, a, PGUP]]")[0]
        assertEquals(listOf(Mod.Ctrl, Mod.Alt, Mod.Shift), row.take(3).map { (it as Sticky).mod })
        assertEquals(0f, (row[3] as Plain).arrow)
        assertEquals(listOf(true, true, false, true), row.drop(3).map { (it as Plain).repeats })
    }

    @Test fun displayAndPopups() {
        val esc = plain("""[[{key: ESC, display: "⎋", popup: {macro: "CTRL f d", display: "tmux exit"}}]]""")
        assertEquals("⎋", esc.label)
        assertNull("a custom label isn't drawn as a chevron", plain("""[[{key: UP, display: "↑"}]]""").arrow)
        val popup = esc.popup!!
        assertEquals("tmux exit", popup.label)
        // A sequence: Ctrl+F, then D on its own.
        assertEquals(listOf(Keys.forChar('f')!!.first to listOf(ctrl), Keys.forChar('d')!!.first to emptyList<Int>()), popup.strokes.map { it.code to it.held })
        // A key with a popup waits for its release, so it doesn't repeat.
        assertTrue(!plain("[[{key: LEFT, popup: HOME}]]").repeats)
        assertEquals("home", plain("[[{key: LEFT, popup: HOME}]]").popup!!.label.lowercase())
        assertEquals("CTRL LEFT", plain("""[[{key: LEFT, popup: {macro: "CTRL LEFT"}}]]""").popup!!.label)
    }

    @Test fun macrosChordModifiersOnTheNextKey() {
        val strokes = plain("""[[{macro: "ALT SHIFT LEFT CTRL A CTRL SHIFT A ESC [ Z", display: "m"}]]""").strokes
        assertEquals(listOf(alt, shift), strokes[0].held)
        assertEquals(Keys.KEY_LEFT, strokes[0].code)
        // "CTRL A" is Ctrl+A, not Ctrl+Shift+A; "CTRL SHIFT A" asks for the shift.
        assertEquals(listOf(ctrl), strokes[1].held)
        assertEquals(listOf(ctrl, shift), strokes[2].held)
        assertEquals(listOf(Keys.KEY_ESC, Keys.forChar('[')!!.first, Keys.forChar('Z')!!.first), strokes.drop(3).map { it.code })
        // A bare Z is the shifted key, as typed.
        assertEquals(listOf(shift), strokes[5].held)
    }

    @Test fun otherTextIsTyped() {
        val row = parse("""[['ls -la', é]]""")[0]
        assertEquals(listOf("ls -la", "é"), row.map { (it as Plain).strokes.single().text })
        assertEquals("ls -la", row[0].label)
    }

    @Test fun saysWhatsWrong() {
        assertTrue(problem("[[a], [b], [c], [d]]"), "at most 3" in problem("[[a], [b], [c], [d]]"))
        assertTrue("row 1 has 11 keys" in problem("[[1, 2, 3, 4, 5, 6, 7, 8, 9, 0, a]]"))
        assertTrue("row 1, key 2: KEYBOARD" in problem("[[ESC, KEYBOARD]]"))
        assertTrue("row 2 isn't a list" in problem("[[ESC], ESC]"))
        assertTrue("ends on a modifier" in problem("""[[{macro: "CTRL"}]]"""))
        assertTrue("can't latch" in problem("[[{key: ESC, popup: CTRL}]]"))
        assertTrue("closing" in problem("[['ESC]]"))
        assertTrue("Termux's extra-keys format" in problem("[[ESC"))
        assertTrue("takes a key or a macro" in problem("[[{display: x}]]"))
    }

    @Test fun anUnreadableSpecFallsBackToTheDefault() {
        assertSame(ExtraKeysLayout.Default, ExtraKeysLayout.of("[[ESC"))
    }
}
