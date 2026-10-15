package dev.omalauncher.ui

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperCropTest {
    @Test fun aWideImageSlidesHalfAScreenAcrossThePages() {
        assertEquals(IntRect(750, 0, 1750, 2000), wallpaperCrop(3000, 2000, 1000, 2000, 0f))
        assertEquals(IntRect(1000, 0, 2000, 2000), wallpaperCrop(3000, 2000, 1000, 2000, 0.5f))
        assertEquals(IntRect(1250, 0, 2250, 2000), wallpaperCrop(3000, 2000, 1000, 2000, 1f))
    }

    @Test fun anImageTheScreensShapeStaysPut() {
        assertEquals(IntRect(0, 0, 500, 1000), wallpaperCrop(500, 1000, 1000, 2000, 1f))
    }

    @Test fun aTallImageIsCroppedTopAndBottom() {
        assertEquals(IntRect(0, 500, 1000, 2500), wallpaperCrop(1000, 3000, 1000, 2000, 0.5f))
    }
}
