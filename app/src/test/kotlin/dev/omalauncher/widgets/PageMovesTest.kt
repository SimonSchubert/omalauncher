package dev.omalauncher.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class PageMovesTest {
    @Test fun swappingTwoScreensMovesTheirItemsBothWays() {
        val items = listOf("clock" to 1, "notes" to 2, "music" to 2)
        assertEquals(listOf("clock" to 2, "notes" to 1, "music" to 1), pageMoves(listOf(2, 1), items))
    }

    @Test fun itemsAlreadyInPlaceDoNotMove() {
        val items = listOf("clock" to 1, "notes" to 2, "music" to 3)
        assertEquals(listOf("music" to 2, "notes" to 3), pageMoves(listOf(1, 3, 2), items).sortedBy { it.second })
    }

    @Test fun aRemovedEmptyScreenClosesTheGap() {
        // Page 2 was empty and removed: page 3 becomes page 2.
        assertEquals(listOf("music" to 2), pageMoves(listOf(1, 3), listOf("clock" to 1, "music" to 3)))
    }

    @Test fun itemsOnUnlistedPagesStay() {
        assertEquals(emptyList<Pair<String, Int>>(), pageMoves(listOf(1, 2), listOf("far" to 7)))
    }
}
