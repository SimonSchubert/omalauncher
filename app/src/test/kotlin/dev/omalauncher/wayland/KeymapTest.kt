package dev.omalauncher.wayland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KeymapTest {
    @get:Rule val tmp = TemporaryFolder()

    private val base =
        """
        xkb_keymap {
        xkb_keycodes "(unnamed)" {
        	minimum = 8;
        	maximum = 255;
        	<AC01> = 38;
        };

        xkb_types "(unnamed)" {
        };

        xkb_symbols "(unnamed)" {
        	key <AC01> { [ a, A ] };
        };

        };
        """.trimIndent()

    private val dir by lazy { File(tmp.root, "keymaps") }

    private fun Keymap.text() = file.readText()

    @Test fun startsWithTheBaseKeymap() {
        val keymap = Keymap(base, dir)
        assertEquals(base + "\u0000", keymap.text())
    }

    @Test fun bindsACharacterToASlotPastKeyMax() {
        val keymap = Keymap(base, dir)
        val (code, changed) = keymap.slotFor('æ'.code)
        assertEquals(0x300, code)
        assertTrue(changed)
        val text = keymap.text()
        assertTrue(text.endsWith("\u0000"))
        val keycodes = text.substringBefore("xkb_types")
        val symbols = text.substringAfter("xkb_symbols")
        assertTrue(keycodes.contains("maximum = ${0x300 + 31 + 8};"))
        assertTrue(keycodes.contains("<OW0> = ${0x300 + 8};"))
        assertTrue(symbols.contains("key <OW0> { [ U00E6 ] };"))
    }

    @Test fun reusesASlotWithoutANewKeymap() {
        val keymap = Keymap(base, dir)
        keymap.slotFor('你'.code)
        val file = keymap.file
        assertEquals(0x300 to false, keymap.slotFor('你'.code))
        assertEquals(file, keymap.file)
    }

    @Test fun bindsCharactersBeyondTheBmp() {
        val keymap = Keymap(base, dir)
        keymap.slotFor(0x1F600)
        assertTrue(keymap.text().contains("[ U1F600 ]"))
    }

    @Test fun evictsTheLeastRecentlyUsedSlot() {
        val keymap = Keymap(base, dir)
        for (i in 0 until 32) keymap.slotFor(0x4E00 + i)
        keymap.slotFor(0x4E00) // used again, so 0x4E01 is now the oldest
        assertEquals(0x301 to true, keymap.slotFor(0x5000))
        val text = keymap.text()
        assertTrue(text.contains("key <OW1> { [ U5000 ] };"))
        assertFalse(text.contains("U4E01"))
        assertTrue(text.contains("U4E00"))
    }

    @Test fun keepsOnlyTheCurrentFile() {
        val keymap = Keymap(base, dir)
        for (c in "æøå") keymap.slotFor(c.code)
        assertEquals(setOf("keymap-3.xkb"), dir.list()!!.toSet())
    }
}
