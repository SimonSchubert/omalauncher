package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a Linux app's xdg-open target is to the launcher: a guest path, or nothing it opens as a file. */
class OpenBridgeTest {
    @Test fun anAbsolutePathIsAsItIs() {
        assertEquals("/root/notes.txt", xdgOpenPath("/root/notes.txt"))
        assertEquals("/tmp/out.json", xdgOpenPath("/tmp/out.json"))
    }

    @Test fun aFileUriIsItsPathWithEscapesRead() {
        assertEquals("/root/My File.pdf", xdgOpenPath("file:///root/My%20File.pdf"))
        // A run of escapes is bytes in UTF-8: a name in another script opens as itself.
        assertEquals("/root/の.pdf", xdgOpenPath("file:///root/%E3%81%AE.pdf"))
        assertEquals("/root/ä.pdf", xdgOpenPath("file:///root/%C3%A4.pdf"))
    }

    @Test fun anIncompleteEscapeStaysAsItIs() {
        assertEquals("/root/50%.pdf", xdgOpenPath("file:///root/50%.pdf"))
        assertEquals("/root/a%2.pdf", xdgOpenPath("file:///root/a%2.pdf"))
    }

    @Test fun aFileUriWithOneSlashIsItsPathToo() {
        assertEquals("/root/notes.txt", xdgOpenPath("file:/root/notes.txt"))
    }

    @Test fun aFileUriLosesItsFragmentAndQuery() {
        assertEquals("/root/notes.txt", xdgOpenPath("file:///root/notes.txt#page=3"))
        assertEquals("/root/notes.txt", xdgOpenPath("file:///root/notes.txt?x=1"))
        // In a plain path a # is part of the name, as in Linux.
        assertEquals("/root/a#b.txt", xdgOpenPath("/root/a#b.txt"))
    }

    @Test fun anythingElseIsNull() {
        assertNull(xdgOpenPath("https://example.com"))
        assertNull(xdgOpenPath("http://localhost:8080/x"))
        assertNull(xdgOpenPath("relative/notes.txt"))
        assertNull(xdgOpenPath("notes.txt"))
        assertNull(xdgOpenPath(""))
        // A host named in the URI keeps it from being a guest path.
        assertNull(xdgOpenPath("file://localhost/root/notes.txt"))
    }
}
