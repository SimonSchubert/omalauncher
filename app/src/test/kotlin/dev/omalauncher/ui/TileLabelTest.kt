package dev.omalauncher.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileLabelTest {
    private val tokyoNight = 0.011f

    @Test fun contrastIsWcags() {
        assertEquals(21f, contrast(0f, 1f), 0.001f)
        assertEquals(21f, contrast(1f, 0f), 0.001f)
        assertEquals(1f, contrast(0.3f, 0.3f), 0.001f)
    }

    @Test fun darkSkyGetsLightNames() {
        assertEquals(LabelInk(LabelTone.Light), pickInk(0.01f..0.05f, tokyoNight))
    }

    @Test fun aBrightCoreGetsDarkNames() {
        assertEquals(LabelInk(LabelTone.Dark), pickInk(0.8f..0.95f, tokyoNight))
    }

    @Test fun midGreyTakesTheBetterToneWithAPlainHaloOnceReadable() {
        assertEquals(LabelInk(LabelTone.Dark), pickInk(0.25f..0.3f, tokyoNight))
    }

    @Test fun marginalContrastStrengthensTheHalo() {
        assertEquals(LabelInk(LabelTone.Light, strongHalo = true), pickInk(0.18f..0.2f, tokyoNight))
        assertEquals(LabelInk(LabelTone.Dark, strongHalo = true), pickInk(0.02f..0.9f, tokyoNight))
    }

    private val grid =
        LumaGrid(
            cols = 4,
            luma = floatArrayOf(0.0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.9f, 0.7f),
            imageWidth = 400,
            imageHeight = 200,
        )
    private val screen = IntSize(100, 200)
    private val label = Rect(0f, 150f, 50f, 170f)

    @Test fun aLabelReadsTheCellsBehindItOnTheFirstPage() {
        assertEquals(0.5f..0.5f, grid.behind(label, screen, 0f))
    }

    @Test fun theSlideOnLaterPagesMovesWhatIsBehindIt() {
        assertEquals(0.5f..0.9f, grid.behind(label, screen, 1f))
    }

    @Test fun aLabelAcrossBothRowsSpansTheirRange() {
        assertEquals(0.1f..0.9f, grid.behind(Rect(0f, 50f, 50f, 150f), screen, 1f))
    }

    @Test fun offScreenLabelsReadTheEdgeCells() {
        assertEquals(0.7f..0.7f, grid.behind(Rect(500f, 150f, 550f, 170f), screen, 1f))
    }

    @Test fun beforeLayoutThereIsNothingToRead() {
        assertNull(grid.behind(Rect.Zero, screen, 0f))
    }
}
