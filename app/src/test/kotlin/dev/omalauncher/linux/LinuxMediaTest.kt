package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Test

class LinuxMediaTest {
    @Test fun backgroundPauseKeepsElapsedPlaybackPosition() {
        assertEquals(42_000L, mediaPositionMs(12_000, 240_000, true, 30_000))
    }

    @Test fun pausedPositionDoesNotAdvance() {
        assertEquals(12_000L, mediaPositionMs(12_000, 240_000, false, 30_000))
    }

    @Test fun elapsedPositionStaysWithinTrackBounds() {
        assertEquals(240_000L, mediaPositionMs(230_000, 240_000, true, 30_000))
        assertEquals(12_000L, mediaPositionMs(12_000, 240_000, true, -100))
        assertEquals(Long.MAX_VALUE, mediaPositionMs(Long.MAX_VALUE - 1, 0, true, 10))
    }
}
