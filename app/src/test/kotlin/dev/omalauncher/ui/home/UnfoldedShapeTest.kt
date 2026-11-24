package dev.omalauncher.ui.home

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isUnfoldedShape] decides when home shows two pages side by side. The Fold8 numbers are from #246: its inner
 * screen's window is 2448x1848 px, so each half is 1224x1848, 0.662 wide for its height. The others are published
 * panel resolutions, each half's width given in dp at a typical density.
 */
class UnfoldedShapeTest {
    private fun half(window: IntSize, density: Float) = (window.width / 2 / density).dp

    @Test fun aGalaxyZFold8InnerScreenShowsTwoPages() {
        val fold8 = IntSize(2448, 1848)
        assertTrue(isUnfoldedShape(fold8, half(fold8, 2.625f), hinge = true))
        assertTrue(isUnfoldedShape(fold8, half(fold8, 3.0f), hinge = true))
    }

    @Test fun otherInnerScreensStillDo() {
        for (window in listOf(
            IntSize(2176, 1812), // Galaxy Z Fold5, 0.600
            IntSize(2208, 1840), // Pixel Fold, 0.600
            IntSize(2152, 2076), // Pixel 9 Pro Fold, 0.518
            IntSize(2440, 2268), // OnePlus Open, 0.538
        )) {
            assertTrue("$window", isUnfoldedShape(window, half(window, 2.625f), hinge = true))
            assertTrue("$window by shape alone", isUnfoldedShape(window, half(window, 2.625f), hinge = false))
        }
    }

    @Test fun tabletsInLandscapeStayOnOnePage() {
        for (hinge in listOf(false, true)) {
            assertFalse(isUnfoldedShape(IntSize(2560, 1600), half(IntSize(2560, 1600), 2.0f), hinge)) // 16:10, 0.8
            assertFalse(isUnfoldedShape(IntSize(2880, 1920), half(IntSize(2880, 1920), 2.0f), hinge)) // 3:2, 0.75
        }
    }

    @Test fun squarerTabletsWithoutAHingeStayOnOnePage() {
        // As phone-shaped as a Fold8's halves, so only the hinge tells them apart.
        for (window in listOf(
            IntSize(2048, 1536), // 4:3, 0.667
            IntSize(2800, 2000), // 7:5 (OnePlus Pad), 0.700
            IntSize(2448, 1848), // a Fold8-shaped screen without a hinge, 0.662
        )) {
            assertFalse("$window", isUnfoldedShape(window, half(window, 2.0f), hinge = false))
        }
    }

    @Test fun phonesInEitherOrientationStayOnOnePage() {
        val upright = IntSize(1080, 2400)
        for (hinge in listOf(false, true)) {
            assertFalse(isUnfoldedShape(upright, half(upright, 2.625f), hinge)) // 0.225
        }
        val sideways = IntSize(2400, 1080)
        for (hinge in listOf(false, true)) {
            assertFalse(isUnfoldedShape(sideways, half(sideways, 2.625f), hinge)) // 1.11
        }
    }

    @Test fun aFoldShapedScreenTooNarrowForTwoPagesDoesNot() {
        // The Fold8's shape at a density that leaves each half under 320 dp.
        val fold8 = IntSize(2448, 1848)
        assertFalse(isUnfoldedShape(fold8, half(fold8, 4.0f), hinge = true))
    }

    @Test fun anEmptyWindowDoesNot() {
        assertFalse(isUnfoldedShape(IntSize(0, 0), 0.dp, hinge = true))
    }
}
