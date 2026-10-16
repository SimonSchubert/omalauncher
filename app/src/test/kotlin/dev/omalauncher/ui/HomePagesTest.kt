package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePagesTest {
    @Test fun noEmptyPageWhenNotArranging() {
        assertEquals(listOf(1, 2), homePageNumbers(listOf(1, 2), true, false, 1))
    }

    @Test fun arrangingAddsOneDropPage() {
        assertEquals(listOf(1, 2, 3), homePageNumbers(listOf(1, 2), true, true, 1))
    }

    @Test fun anEmptyLastPageIsNotDoubled() {
        assertEquals(listOf(1), homePageNumbers(listOf(1), false, true, 1))
        assertEquals(listOf(1), homePageNumbers(listOf(1), false, false, 1))
    }

    @Test fun freshHomeKeepsItsOnePage() {
        assertEquals(listOf(1), homePageNumbers(listOf(1), false, false, 1))
    }

    @Test fun unfoldedPagesPairUp() {
        assertEquals(listOf(1, 2), homePageNumbers(listOf(1), true, true, 2))
        assertEquals(listOf(1, 2), homePageNumbers(listOf(1, 2), true, false, 2))
        assertEquals(listOf(1, 2, 3, 4), homePageNumbers(listOf(1, 2), true, true, 2))
        assertEquals(listOf(1, 2), homePageNumbers(listOf(1), true, false, 2))
    }

    @Test fun gapsInPageNumbersFollowTheLast() {
        assertEquals(listOf(1, 4, 5), homePageNumbers(listOf(1, 4), true, true, 1))
    }
}
