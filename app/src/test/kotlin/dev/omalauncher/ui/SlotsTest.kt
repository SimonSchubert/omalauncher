package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SlotsTest {
    // Faces left of page 1 (and the empty one waiting for one) are full pages; so is page 4 here, holding a face.
    private fun full(page: Int) = page <= 0 || page == 4

    @Test fun foldedEveryPageIsASlot() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), slotsOf(listOf(0, 1, 2), 1, ::full))
    }

    @Test fun unfoldedPageOneStartsAPair() {
        // As 0.8.1 paired them, (1, 2) and (3, 4), whatever faces stand left of page 1.
        assertEquals(listOf(listOf(-1), listOf(0), listOf(1, 2), listOf(3, 4)), slotsOf(listOf(-1, 0, 1, 2, 3), 2) { it <= 0 })
    }

    @Test fun unfoldedFaceTakesASlotOfItsOwn() {
        // Page 3 has a face after it: it stands alone. The last page gets an empty partner.
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4), listOf(5, 6)), slotsOf(listOf(0, 1, 2, 3, 4, 5), 2, ::full))
    }
}
