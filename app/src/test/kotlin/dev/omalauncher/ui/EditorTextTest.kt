package dev.omalauncher.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditorTextTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun missingFileIsEmpty() {
        assertEquals(EditorFile.Text("", crlf = false), readEditorFile(tmp.root.resolve("new.txt")))
    }

    @Test fun crlfComesBackOnSave() {
        val file = tmp.newFile("dos.ini").apply { writeText("a=1\r\nb=2\r\n") }
        val read = readEditorFile(file) as EditorFile.Text
        assertEquals("a=1\nb=2\n", read.text)
        assertArrayEquals("a=1\r\nb=2\r\n".toByteArray(), encodeEditorText(read.text, read.crlf))
    }

    @Test fun binaryAndBigFilesAreLeftToMicro() {
        assertEquals(EditorFile.Binary, readEditorFile(tmp.newFile("nul").apply { writeBytes(byteArrayOf(65, 0, 66)) }))
        assertEquals(EditorFile.Binary, readEditorFile(tmp.newFile("latin1").apply { writeBytes(byteArrayOf(0x63, 0xe9.toByte())) }))
        assertEquals(EditorFile.TooBig, readEditorFile(tmp.newFile("big").apply { writeBytes(ByteArray(EDITOR_MAX_BYTES.toInt() + 1) { 65 }) }))
    }

    @Test fun indentFollowsTheFile() {
        assertEquals("\t", indentUnit("a {\n\tb\n}"))
        assertEquals("    ", indentUnit("a:\n    b:\n        c"))
        assertEquals("  ", indentUnit("no indent here"))
        // A blank line of spaces says nothing about the indent.
        assertEquals("    ", indentUnit("a\n  \n    b"))
    }

    @Test fun lineStartsIncludeTheLastLine() {
        assertArrayEquals(intArrayOf(0), lineStarts(""))
        assertArrayEquals(intArrayOf(0, 2, 3), lineStarts("a\n\nb"))
        assertArrayEquals(intArrayOf(0, 2), lineStarts("a\n"))
    }

    @Test fun findWrapsAround() {
        val text = "foo bar Foo"
        assertEquals(0, findMatch(text, "foo", 0))
        assertEquals(8, findMatch(text, "foo", 1))
        assertEquals(0, findMatch(text, "foo", 9))
        assertEquals(0, findMatch(text, "foo", 8, backward = true))
        assertEquals(8, findMatch(text, "foo", 0, backward = true))
        assertNull(findMatch(text, "baz", 0))
        assertNull(findMatch(text, "", 0))
    }

    @Test fun gitMessagesAreProse() {
        assertTrue(isProse("COMMIT_EDITMSG"))
        assertTrue(isProse("notes.md"))
        assertFalse(isProse("hyprland.conf"))
    }
}
