package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilePickTest {
    @Test fun parsesEachMode() {
        assertEquals(FilePick(FilePick.Mode.Open, multiple = true, title = "Open G-code") to "/root/prints", FilePick.parse(listOf("open", "1", "/root/prints", "", "Open G-code")))
        assertEquals(FilePick(FilePick.Mode.Save, name = "benchy.gcode") to "/root", FilePick.parse(listOf("save", "0", "/root", "benchy.gcode", "")))
        assertEquals(FilePick.Mode.Folder, FilePick.parse(listOf("folder", "0", "/tmp", "", ""))?.first?.mode)
    }

    @Test fun aMissingOrRelativeFolderStartsAtHome() {
        assertEquals("/root", FilePick.parse(listOf("open", "0", "", "", ""))?.second)
        assertEquals("/root", FilePick.parse(listOf("open", "0", "prints", "", ""))?.second)
    }

    @Test fun anythingElseIsRefused() {
        assertNull(FilePick.parse(listOf("delete", "0", "/root", "", "")))
        assertNull(FilePick.parse(listOf("open", "0")))
    }

    @Test fun headingFallsBackToTheMode() {
        assertEquals("save as", FilePick(FilePick.Mode.Save).heading)
        assertEquals("Export", FilePick(FilePick.Mode.Save, title = "Export").heading)
    }
}
