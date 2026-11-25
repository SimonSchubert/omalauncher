package dev.omalauncher.linux

import dev.omalauncher.linux.ProcShim.Companion.IDLE_SCAN_MS
import dev.omalauncher.linux.ProcShim.Companion.READING_MS
import dev.omalauncher.linux.ProcShim.Companion.padded
import dev.omalauncher.linux.ProcShim.Companion.scanDue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcShimTest {
    @Test
    fun theFirstUpdateScans() = assertTrue(scanDue(now = 5_000, lastRead = 0, lastScan = 0))

    @Test
    fun nothingReadScansOnceAMinute() {
        assertFalse(scanDue(now = 30_000, lastRead = 0, lastScan = 5_000))
        assertTrue(scanDue(now = 5_000 + IDLE_SCAN_MS, lastRead = 0, lastScan = 5_000))
    }

    @Test
    fun aRecentReadScansEachTime() = assertTrue(scanDue(now = 50_000, lastRead = 49_000, lastScan = 49_500))

    @Test
    fun anOldReadFallsBackToOnceAMinute() =
        assertFalse(scanDue(now = 50_000 + READING_MS, lastRead = 50_000, lastScan = 50_000 + READING_MS - 1_000))

    @Test
    fun aShorterTextIsFilledBeforeItsLastNewline() = assertEquals("9.50 38.00   \n", padded("9.50 38.00\n", 14))

    @Test
    fun aLongerTextIsLeftAsItIs() = assertEquals("10.50 42.00\n", padded("10.50 42.00\n", 5))
}
