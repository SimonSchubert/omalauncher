package dev.omalauncher.wayland

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionTest {
    @Test fun emptyRegionTakesNothing() {
        assertFalse(regionContains(emptyList(), 0f, 0f))
    }

    @Test fun addedRectangleHoldsItsInsideOnly() {
        val ops = listOf(intArrayOf(1, 10, 20, 30, 40))
        assertTrue(regionContains(ops, 10f, 20f))
        assertTrue(regionContains(ops, 39.5f, 59.5f))
        assertFalse(regionContains(ops, 40f, 30f))
        assertFalse(regionContains(ops, 9.9f, 30f))
    }

    @Test fun laterRectanglesWin() {
        val ops = listOf(intArrayOf(1, 0, 0, 100, 100), intArrayOf(0, 25, 25, 50, 50), intArrayOf(1, 40, 40, 10, 10))
        assertTrue(regionContains(ops, 10f, 10f))
        assertFalse(regionContains(ops, 30f, 30f))
        assertTrue(regionContains(ops, 45f, 45f))
    }
}
