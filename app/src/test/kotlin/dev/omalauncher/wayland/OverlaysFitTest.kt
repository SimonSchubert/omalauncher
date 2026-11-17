package dev.omalauncher.wayland

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaysFitTest {
    private fun buffer(
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) = Stacked(l, t, r, b, ahb = true)

    private fun pixels(
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) = Stacked(l, t, r, b, ahb = false)

    @Test fun aGpuWindowAloneFits() {
        assertTrue(overlaysFit(listOf(buffer(0, 0, 1080, 2000))))
        assertTrue(overlaysFit(emptyList()))
    }

    @Test fun pixelsBeneathABufferAreFine() {
        assertTrue(overlaysFit(listOf(pixels(0, 0, 1080, 2000), buffer(0, 100, 1080, 1900))))
    }

    @Test fun buffersOverBuffersFit() {
        assertTrue(overlaysFit(listOf(buffer(0, 0, 1080, 2000), buffer(100, 100, 300, 300))))
    }

    @Test fun pixelsOverABufferDont() {
        // A popup menu over a GPU app's surface would end up beneath it.
        assertFalse(overlaysFit(listOf(buffer(0, 0, 1080, 2000), pixels(100, 100, 400, 600))))
    }

    @Test fun pixelsBesideABufferDo() {
        // Touching edges don't overlap.
        assertTrue(overlaysFit(listOf(buffer(0, 0, 1080, 1000), pixels(0, 1000, 1080, 2000))))
    }
}
