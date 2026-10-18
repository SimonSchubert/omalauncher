package dev.omalauncher.linux

import dev.omalauncher.linux.LauncherFont.Face
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherFontTest {
    @Test
    fun staticFaceTakesTheNearestWeight() {
        assertEquals(listOf(Face(400, false, "/usr/share/fonts/a.ttf")), LauncherFont.parse("80\t0\t/usr/share/fonts/a.ttf"))
        assertEquals(listOf(Face(700, true, "/b.otf")), LauncherFont.parse("200\t100\t/b.otf"))
        assertEquals(listOf(Face(400, false, "/c.ttf")), LauncherFont.parse("75\t0\t/c.ttf"))
    }

    @Test
    fun variableFaceServesEveryWeightInItsRange() {
        val faces = LauncherFont.parse("[40 200]\t0\t/v.ttf")!!
        assertEquals(listOf(200, 300, 400, 500, 600, 700), faces.map { it.weight })
    }

    @Test
    fun bitmapFontsAndJunkAreLeftOut() {
        assertNull(LauncherFont.parse("80\t0\t/usr/share/fonts/misc/6x13.pcf.gz"))
        assertNull(LauncherFont.parse("fc-list: not found"))
    }

    @Test
    fun faceSurvivesTheCache() {
        val face = Face(500, true, "/usr/share/fonts/TTF/a b.ttf")
        assertEquals(face, Face.decode(face.encode()))
    }

    @Test
    fun patternCharactersInANameStayLiteral() {
        assertEquals("Iosevka\\-Term\\, x\\:y", LauncherFont.escape("Iosevka-Term, x:y"))
    }
}
