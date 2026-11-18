package dev.omalauncher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageZoomTest {
    // A theme's 16:9 screenshot on a square screen: it fills the width, with bands above and below.
    private val picture = Size(1200f, 675f)
    private val box = Size(1000f, 1000f)

    private fun assertOffset(
        expected: Offset,
        actual: Offset,
    ) {
        assertEquals(expected.x, actual.x, 0.01f)
        assertEquals(expected.y, actual.y, 0.01f)
    }

    @Test fun aPictureNotZoomedInStaysPut() {
        assertOffset(Offset.Zero, keepInView(Offset(300f, -200f), 1f, picture, box))
    }

    @Test fun aZoomedPictureSlidesOnlyUntilItsEdgeMeetsTheScreens() {
        // 2x: 2000 x 1125 on screen, so 500 to spare on each side and 62.5 above and below.
        assertOffset(Offset(500f, -62.5f), keepInView(Offset(900f, -900f), 2f, picture, box))
        assertOffset(Offset(-120f, 40f), keepInView(Offset(-120f, 40f), 2f, picture, box))
    }

    @Test fun aSideThatDoesntFillTheScreenDoesntSlide() {
        // 1.5x: 843.75 high, still inside the 1000 of the screen.
        assertOffset(Offset(200f, 0f), keepInView(Offset(200f, 100f), 1.5f, picture, box))
    }

    @Test fun nothingSlidesBeforeThePictureHasLoaded() {
        assertOffset(Offset.Zero, keepInView(Offset(50f, 50f), 3f, Size.Zero, box))
    }

    @Test fun zoomingKeepsWhatsUnderTheFingersUnderThem() {
        val at = Offset(-300f, 120f)
        val pan = Offset(40f, -10f)
        val next = zoomAround(pan, at, 1.5f, 4f)
        // The picture's point under `at` before zooming is still under it after.
        val point = (at - pan) / 1.5f
        assertOffset(at, point * 4f + next)
    }

    @Test fun zoomingAroundTheCenterOfAnUnmovedPictureLeavesItCentered() {
        assertOffset(Offset.Zero, zoomAround(Offset.Zero, Offset.Zero, 1f, 2.5f))
    }
}
