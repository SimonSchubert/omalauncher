package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BarShiftTest {
    // The feed page, two tile pages, a page widget, then the empty page after the last.
    private val full = listOf(true, false, false, true, false)

    private fun at(position: Float) = barShift(full, position)

    @Test fun staysOverTilePages() {
        assertEquals(0f, at(1f), 0f)
        assertEquals(0f, at(1.5f), 0f)
        assertEquals(0f, at(2f), 0f)
        assertEquals(0f, at(4f), 0f)
    }

    @Test fun slidesWithTheTilePageBesideAFullOne() {
        // From page 1 toward the feed on its left, the bar goes right with page 1.
        assertEquals(0.25f, at(0.75f), 1e-6f)
        assertEquals(1f, at(0f), 0f)
        // From the second tile page toward the page widget on its right, it goes left.
        assertEquals(-0.25f, at(2.25f), 1e-6f)
        // And back in from the right past it.
        assertEquals(0.5f, at(3.5f), 1e-6f)
    }

    @Test fun awayBetweenFullPages() {
        assertEquals(1f, barShift(listOf(true, true), 0.5f), 0f)
        assertEquals(1f, barShift(listOf(true), 0f), 0f)
    }
}
