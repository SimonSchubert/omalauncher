package dev.omalauncher.wayland

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BufferDamageTest {
    private val buffers = BufferDamage()
    private val full = intArrayOf(0, 0, W, H)

    /** Draws a frame that changed [dirty] into buffer [id]; returns what had to be repainted. */
    private fun frame(
        id: Long,
        vararg dirty: Int,
        canary: Long = id * 31,
    ): IntArray {
        val rect = dirty.copyOf()
        buffers.repaint(id, canary, rect, W, H)
        buffers.drawn(id, id * 31)
        return rect
    }

    @Test fun aBufferSeenForTheFirstTimeIsRepaintedWhole() {
        assertArrayEquals(full, frame(1, 10, 10, 20, 20))
        assertArrayEquals(full, frame(2, 10, 10, 20, 20))
    }

    @Test fun aBufferCatchesUpOnTheFramesItSatOut() {
        frame(1, 0, 0, W, H)
        frame(2, 10, 10, 20, 20)
        frame(3, 30, 40, 50, 60)
        // Buffer 1 missed both of those, and gets this frame's change too.
        assertArrayEquals(intArrayOf(5, 10, 50, 70), frame(1, 5, 65, 8, 70))
        // Buffer 2 has its own frame already: only what came after.
        assertArrayEquals(intArrayOf(5, 40, 50, 70), frame(2, 6, 66, 7, 67))
    }

    @Test fun theSameSmallChangeEachFrameStaysSmall() {
        for (id in 1L..3L) frame(id, 0, 0, W, H)
        // The first round still catches up on the others' first, whole frames.
        for (id in 1L..3L) frame(id, 8, 62, 428, 89)
        repeat(9) { assertArrayEquals(intArrayOf(8, 62, 428, 89), frame(it % 3 + 1L, 8, 62, 428, 89)) }
    }

    @Test fun aFullRedrawReachesEveryBuffer() {
        for (id in 1L..3L) frame(id, 10, 10, 20, 20)
        assertArrayEquals(full, frame(1, 0, 0, W, H))
        assertArrayEquals(full, frame(2, 10, 10, 20, 20))
        assertArrayEquals(full, frame(3, 10, 10, 20, 20))
        assertArrayEquals(intArrayOf(10, 10, 20, 20), frame(1, 10, 10, 20, 20))
    }

    @Test fun aBufferThatLostOurPixelsIsRepaintedWhole() {
        for (id in 1L..3L) frame(id, 0, 0, W, H)
        // Another buffer at buffer 1's address.
        assertArrayEquals(full, frame(1, 10, 10, 20, 20, canary = 0))
        frame(2, 10, 10, 20, 20)
        frame(3, 10, 10, 20, 20)
        assertArrayEquals(intArrayOf(10, 10, 20, 20), frame(1, 10, 10, 20, 20))
    }

    @Test fun moreBuffersThanPlacesForgetsTheOneUnusedLongest() {
        for (id in 1L..8L) frame(id, 0, 0, W, H)
        for (id in 1L..8L) frame(id, 10, 10, 20, 20)
        // A ninth takes the place of 1; 2 is still known.
        assertArrayEquals(full, frame(9, 10, 10, 20, 20))
        assertArrayEquals(intArrayOf(10, 10, 20, 20), frame(2, 10, 10, 20, 20))
        assertArrayEquals(full, frame(1, 10, 10, 20, 20))
    }

    private companion object {
        const val W = 1080
        const val H = 2400
    }
}
