package dev.omalauncher.taskbar

import dev.omalauncher.ui.splitdrag.LaunchStep
import dev.omalauncher.ui.splitdrag.ZoneBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrampolineTest {
    private val right = ZoneBox(928f, 0f, 1856f, 2160f)

    @Test fun middleInsideTheHalfLandsInIt() {
        assertTrue(landsIn(right, left = 1000f, top = 100f, width = 200f, height = 200f))
    }

    @Test fun middleOutsideTheHalfDoesNot() {
        assertFalse(landsIn(right, left = 0f, top = 100f, width = 200f, height = 200f))
    }

    @Test fun middleOnTheEdgeCountsForTheHalfItStartsNot() {
        // The middle at x = 928 is the right half's left edge: in it.
        assertTrue(landsIn(right, left = 828f, top = 0f, width = 200f, height = 200f))
        // At the right half's right edge: out of it.
        assertFalse(landsIn(right, left = 1756f, top = 0f, width = 200f, height = 200f))
    }

    @Test fun aSplitsStepsOpenTheirApps() {
        assertEquals(TrampolineStep.Open("maps", adjacent = true, delayMs = 450), LaunchStep("maps", adjacent = true, delayMs = 450).asStep())
    }
}
