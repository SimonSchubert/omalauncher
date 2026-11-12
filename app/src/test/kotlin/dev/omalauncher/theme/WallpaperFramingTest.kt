package dev.omalauncher.theme

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperFramingTest {
    @Test fun defaultsKeepTheCenteredHalfScreenSlide() {
        val framing = WallpaperFraming()
        assertEquals(IntRect(750, 0, 1750, 2000), framing.crop(3000, 2000, 1000, 2000, 0f))
        assertEquals(IntRect(1000, 0, 2000, 2000), framing.crop(3000, 2000, 1000, 2000, 0.5f))
        assertEquals(IntRect(1250, 0, 2250, 2000), framing.crop(3000, 2000, 1000, 2000, 1f))
    }

    @Test fun fixedWallpaperKeepsItsChosenPositionOnEveryPage() {
        for (x in listOf(0f, 0.3f, 1f)) {
            val framing = WallpaperFraming(scroll = false, x = x)
            val crop = framing.crop(3000, 2000, 1000, 2000, 0f)
            assertEquals((2000 * x).toInt(), crop.left)
            for (page in listOf(-1f, 0.5f, 1f, 2f)) assertEquals(crop, framing.crop(3000, 2000, 1000, 2000, page))
        }
    }

    @Test fun verticalPositionReachesTheTopAndBottomOfTallPictures() {
        assertEquals(IntRect(0, 0, 1000, 2000), WallpaperFraming(y = 0f).crop(1000, 4000, 1000, 2000, 0.5f))
        assertEquals(IntRect(0, 2000, 1000, 4000), WallpaperFraming(y = 1f).crop(1000, 4000, 1000, 2000, 0.5f))
    }

    @Test fun zoomMagnifiesWithoutStretching() {
        val crop = WallpaperFraming(scroll = false, zoom = 2f).crop(3000, 2000, 1000, 2000, 0.5f)
        assertEquals(IntRect(1250, 500, 1750, 1500), crop)
        assertEquals(0.5f, crop.width.toFloat() / crop.height, 0f)
    }

    @Test fun cropsStayInsideThePictureInBothOrientationsAndOnFoldables() {
        for ((bw, bh) in listOf(4000 to 1000, 1000 to 4000, 1920 to 1080, 1 to 1)) {
            for ((w, h) in listOf(1080 to 2400, 2400 to 1080, 1800 to 1800)) {
                for (zoom in listOf(1f, 1.6f, 3f)) for (x in listOf(0f, 0.5f, 1f)) for (y in listOf(0f, 1f)) {
                    for (scroll in listOf(false, true)) for (offset in listOf(-1f, 0f, 1f, 2f)) {
                        val crop = WallpaperFraming(scroll, x, y, zoom).crop(bw, bh, w, h, offset)
                        assertTrue("$bw x $bh: $crop", crop.left >= 0 && crop.top >= 0 && crop.right <= bw && crop.bottom <= bh && crop.width > 0 && crop.height > 0)
                    }
                }
            }
        }
    }

    @Test fun glassUsesTheSameCropAtItsSmallerResolution() {
        val framing = WallpaperFraming(scroll = true, x = 0.2f, y = 0.8f, zoom = 1.7f)
        val home = framing.crop(3000f, 2000f, 1080f, 2400f, 0.7f)
        val glass = framing.crop(3000f / 8, 2000f / 8, 1080f, 2400f, 0.7f)
        assertEquals(home.left, glass.left * 8, 0.001f)
        assertEquals(home.top, glass.top * 8, 0.001f)
        assertEquals(home.width, glass.width * 8, 0.001f)
        assertEquals(home.height, glass.height * 8, 0.001f)
    }

    @Test fun emptyWindowsHaveNoCrop() {
        assertEquals(IntRect.Zero, WallpaperFraming().crop(1000, 2000, 0, 0, 0.5f))
        assertEquals(Rect.Zero, WallpaperFraming().crop(0f, 0f, 1000f, 2000f, 0.5f))
    }

    @Test fun matchingShapeHasNoPositionOrScrollToAdjust() {
        assertEquals(WallpaperFraming.Adjustments(), WallpaperFraming().adjustments(1000, 2000, 1000, 2000))
        assertEquals(WallpaperFraming.Adjustments(), WallpaperFraming().adjustments(1000, 2000, 0, 0))
    }

    @Test fun scrollingCanUseAllSpareWidthLeavingNoHorizontalAdjustment() {
        // This quarter-screen margin is all used by the page movement.
        assertEquals(WallpaperFraming.Adjustments(scrolling = true), WallpaperFraming().adjustments(1250, 2000, 1000, 2000))
        // Fixed can place that same margin wherever the user wants it.
        assertEquals(WallpaperFraming.Adjustments(horizontal = true), WallpaperFraming(scroll = false).adjustments(1250, 2000, 1000, 2000))
    }

    @Test fun widePicturesCanPositionTheirScrollRangeAndTallPicturesCanMoveVertically() {
        assertEquals(WallpaperFraming.Adjustments(horizontal = true, scrolling = true), WallpaperFraming().adjustments(3000, 2000, 1000, 2000))
        assertEquals(WallpaperFraming.Adjustments(vertical = true), WallpaperFraming().adjustments(1000, 4000, 1000, 2000))
    }

    @Test fun zoomMakesPositionControlsAvailableAgainWithoutLosingTheirValues() {
        val framing = WallpaperFraming(x = 0.2f, y = 0.8f)
        assertEquals(WallpaperFraming.Adjustments(), framing.adjustments(1000, 2000, 1000, 2000))
        val zoomed = framing.copy(zoom = 2f)
        assertEquals(WallpaperFraming.Adjustments(horizontal = true, vertical = true, scrolling = true), zoomed.adjustments(1000, 2000, 1000, 2000))
        assertEquals(0.2f, zoomed.x, 0f)
        assertEquals(0.8f, zoomed.y, 0f)
    }
}
