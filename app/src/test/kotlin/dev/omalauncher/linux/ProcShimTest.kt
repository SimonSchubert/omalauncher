package dev.omalauncher.linux

import dev.omalauncher.linux.ProcShim.Companion.IDLE_UPDATE_MS
import dev.omalauncher.linux.ProcShim.Companion.READING_MS
import dev.omalauncher.linux.ProcShim.Companion.READING_UPDATE_MS
import dev.omalauncher.linux.ProcShim.Companion.nextUpdateMs
import org.junit.Test
import org.junit.Assert.assertEquals

class ProcShimTest {
    @Test
    fun nothingReadYetUpdatesOnceAMinute() = assertEquals(IDLE_UPDATE_MS, nextUpdateMs(now = 5_000, lastRead = 0))

    @Test
    fun aRecentReadUpdatesEverySecond() = assertEquals(READING_UPDATE_MS, nextUpdateMs(now = 50_000, lastRead = 49_000))

    @Test
    fun anOldReadFallsBackToOnceAMinute() = assertEquals(IDLE_UPDATE_MS, nextUpdateMs(now = 50_000 + READING_MS, lastRead = 50_000))
}
