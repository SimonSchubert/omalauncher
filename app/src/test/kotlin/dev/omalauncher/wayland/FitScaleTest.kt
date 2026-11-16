package dev.omalauncher.wayland

import org.junit.Assert.assertEquals
import org.junit.Test

class FitScaleTest {
    @Test fun windowsThatFitStayFullSize() {
        assertEquals(1f, fitScale(1080, 2000, 1080, 2200), 0f)
        assertEquals(1f, fitScale(500, 500, 1080, 2200), 0f)
    }

    @Test fun tooWideOrTallShrinksByTheTighterSide() {
        assertEquals(0.5f, fitScale(2160, 1000, 1080, 2200), 1e-6f)
        assertEquals(0.5f, fitScale(1000, 2000, 1080, 1000), 1e-6f)
        assertEquals(0.5f, fitScale(2160, 2000, 1080, 1100), 1e-6f)
    }

    @Test fun noSizeYetMeansNoShrinking() {
        assertEquals(1f, fitScale(0, 0, 1080, 2200), 0f)
        assertEquals(1f, fitScale(2000, 2000, 0, 0), 0f)
    }
}
