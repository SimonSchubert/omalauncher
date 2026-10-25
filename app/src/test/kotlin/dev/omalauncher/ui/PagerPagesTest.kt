package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PagerPagesTest {
    @Test fun edgesAddAnEmptyPageAtBothEnds() {
        assertEquals(listOf(0, 1, 2, 3), pagerPages(listOf(1, 2), lastEmpty = false, facePage = true, tilePage = true))
    }

    @Test fun anEmptyLastPageIsTheOneAfter() {
        assertEquals(listOf(0, 1, 2), pagerPages(listOf(1, 2), lastEmpty = true, facePage = true, tilePage = true))
    }

    @Test fun theFacePageGoesLeftOfTheLeftmostFace() {
        assertEquals(listOf(-2, -1, 0, 1, 2), pagerPages(listOf(-1, 0, 1), lastEmpty = false, facePage = true, tilePage = true))
    }

    @Test fun withoutEdgesOnlyHomesOwnPages() {
        assertEquals(listOf(1, 2), pagerPages(listOf(1, 2), lastEmpty = false, facePage = false, tilePage = false))
        assertEquals(listOf(0, 1), pagerPages(listOf(0, 1), lastEmpty = false, facePage = false, tilePage = false))
    }

    // Carrying a tile with the setting off: a page to drop it on after the last, and nothing added before page 1.
    @Test fun carryingAddsOnlyThePageAfterTheLast() {
        assertEquals(listOf(1, 2, 3), pagerPages(listOf(1, 2), lastEmpty = false, facePage = false, tilePage = true))
    }

    @Test fun anEmptyHomeKeepsItsOnePage() {
        assertEquals(listOf(1), pagerPages(listOf(1), lastEmpty = true, facePage = false, tilePage = false))
    }
}
