package dev.omalauncher.terminal

import dev.omalauncher.HostNative
import dev.omalauncher.wayland.Keys
import dev.omalauncher.widgets.lang.KeyNames
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

/** Evdev presses and releases, as the key bar and the keyboard send them, into the bytes a built-in terminal sends. */
class TerminalKeyboardTest {
    private val vt = Vt(20, 4)
    private val keys = TerminalKeyboard()

    @After fun close() = vt.close()

    /** Presses and releases each of [codes] in turn, holding the ones before; what the last press sent. */
    private fun chord(vararg codes: Int): Any? {
        var last: TerminalKeyboard.Action? = null
        for (c in codes) last = keys.key(vt, c, true)
        for (c in codes.reversed()) keys.key(vt, c, false)
        return when (val a = last) {
            is TerminalKeyboard.Action.Send -> a.bytes.decodeToString()
            else -> a
        }
    }

    private fun combo(name: String): Any? {
        val c = KeyNames.combo(name)!!
        return chord(*(c.held + c.code).toIntArray())
    }

    @Test fun letters() {
        assertEquals("a", chord(30))
        assertEquals("A", chord(Keys.KEY_LEFTSHIFT, 30))
        assertEquals("!", chord(Keys.KEY_LEFTSHIFT, 2))
        assertEquals("|", chord(Keys.KEY_LEFTSHIFT, 43))
        assertEquals(" ", chord(57))
    }

    @Test fun capsLock() {
        chord(TerminalKeyboard.KEY_CAPSLOCK)
        assertEquals("A", chord(30))
        assertEquals("a", chord(Keys.KEY_LEFTSHIFT, 30))
        assertEquals("1", chord(2))
        chord(TerminalKeyboard.KEY_CAPSLOCK)
        assertEquals("a", chord(30))
    }

    @Test fun keyBarCombos() {
        assertEquals("\u0003", combo("ctrl+c"))
        assertEquals("\u001b", combo("esc"))
        assertEquals("\t", combo("tab"))
        assertEquals("\u001b[A", combo("up"))
        assertEquals("\u001b[1;5D", combo("ctrl+left"))
        assertEquals("\u001bx", combo("alt+x"))
        assertEquals("\u001b[Z", combo("shift+tab"))
        assertEquals("\u001b[6~", combo("pgdn"))
    }

    @Test fun shiftEnterUnderKittyProtocol() {
        vt.write("\u001b[>1u".encodeToByteArray())
        assertEquals("\u001b[13;2u", combo("shift+enter"))
        assertEquals("\r", combo("enter"))
    }

    @Test fun copyAndPaste() {
        assertEquals(TerminalKeyboard.Action.Copy, chord(Keys.KEY_LEFTCTRL, Keys.KEY_LEFTSHIFT, 46))
        assertEquals(TerminalKeyboard.Action.Paste, chord(Keys.KEY_LEFTCTRL, Keys.KEY_LEFTSHIFT, 47))
        assertEquals("\u0016", chord(Keys.KEY_LEFTCTRL, 47))
    }

    @Test fun releasesSendNothing() {
        assertEquals(null, keys.key(vt, 30, false))
        assertEquals(0, keys.mods)
        keys.key(vt, Keys.KEY_LEFTCTRL, true)
        assertEquals(Vt.CTRL, keys.mods)
        keys.reset()
        assertEquals(0, keys.mods)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun build() = HostNative.load("vt.c", extra = HostNative.ghosttyVt())
    }
}
