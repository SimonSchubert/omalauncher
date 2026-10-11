package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalFontTest {
    /** A typical phone: density 2.625, so foot's scale is 2. */
    @Test fun phoneTextIsBiggerThanTheOldFixedSize() {
        val size = TerminalFont.pointSize(2.625f, 1f, 2)
        assertEquals(12.0, size, 0.0)
        assertTrue(size > 8.0)
    }

    /** A tablet at 1.875 gets scale 1: the old size 8 drew a 6dp em there. */
    @Test fun tabletMakesUpForTheRoundedDownScale() {
        assertEquals(17.0, TerminalFont.pointSize(1.875f, 1f, 1), 0.0)
    }

    @Test fun followsAndroidsFontSize() {
        assertEquals(15.5, TerminalFont.pointSize(2.625f, 1.3f, 2), 0.0)
    }

    /** 1080 px wide at scale 2 is the phone foot.ini's old size 8 was tuned for. */
    @Test fun btopFitsEightyColumnsInPortrait() {
        val size = TerminalFont.fitting(80, 1080, 2, 12.0)
        assertEquals(8.0, size, 0.0)
        val cell = 0.6 * size * 96 / 72 * 2
        assertTrue(80 * Math.ceil(cell) + 2 * 8 * 2 <= 1080)
    }

    @Test fun fittingNeverGrowsTheSize() {
        assertEquals(12.0, TerminalFont.fitting(80, 2400, 2, 12.0), 0.0)
    }

    /** Measured with tput on a 1080 px phone: 65 columns at 10pt, 55 at 12. */
    @Test fun countsColumnsLikeFoot() {
        assertEquals(65, TerminalFont.columns(1080, 2, 10.0))
        assertEquals(55, TerminalFont.columns(1080, 2, 12.0))
    }

    @Test fun textSizesStepTheEm() {
        assertEquals(10.0, TerminalFont.pointSize(2.625f, 1f, 2, TerminalText.Smaller.em), 0.0)
        assertEquals(14.0, TerminalFont.pointSize(2.625f, 1f, 2, TerminalText.Larger.em), 0.0)
    }

    /** What fc-list finds in a fresh guest: the icon-only Nerd Font is foot's fallback, not a choice. */
    @Test fun listsTextFontsOnly() {
        val found = listOf("DejaVu Sans Mono", "JetBrains Mono", "JetBrains Mono NL", "Symbols Nerd Font Mono", "Noto Color Emoji", "")
        assertEquals(listOf("DejaVu Sans Mono", "JetBrains Mono", "JetBrains Mono NL"), found.filter(TerminalFont::isTextFont))
    }
}
