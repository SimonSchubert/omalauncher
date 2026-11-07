package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenInLinuxTest {
    private val mime =
        GuestMime(
            listOf("*.stl" to "model/stl", "*.3mf" to "model/3mf", "*.gcode" to "text/x.gcode").sortedByDescending { it.first.length },
            mapOf("application/vnd.ms-3mfdocument" to "model/3mf"),
        )

    @Test fun typesComeFromTheNameAndAliasesFold() {
        assertEquals("model/stl", mime.forName("Benchy.STL"))
        assertTrue(mime.opens(listOf("model/stl", "application/vnd.ms-3mfdocument"), "model/3mf"))
        assertTrue(mime.opens(listOf("image/*"), "image/png"))
        assertFalse(mime.opens(listOf("model/stl"), "text/x.gcode"))
    }

    @Test fun theFileGoesWhereTheEntrySays() {
        val app = LinuxApp("PrusaSlicer", "prusa-slicer", false, "PrusaSlicer", null, execTemplate = "prusa-slicer %F")
        assertEquals("prusa-slicer '/root/Downloads/a b.stl'", app.commandFor(listOf("/root/Downloads/a b.stl")))
        val viewer = LinuxApp("Viewer", "viewer", false, "viewer", null, execTemplate = "viewer --open %u")
        assertEquals("viewer --open 'file:///root/x%20y.png'", viewer.commandFor(listOf("/root/x y.png")))
        val plain = LinuxApp("Plain", "plain", false, "plain", null)
        assertEquals("plain '/root/f.txt'", plain.commandFor(listOf("/root/f.txt")))
    }
}
