package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowInputTest {
    @Test fun simultaneousComposeIdsRemainDistinctAfterNarrowingToWaylandIds() {
        val ids = TouchPointerIds()
        val stream = Any()
        val firstPointer = 17L
        val secondPointer = firstPointer + (1L shl 32)
        val thirdPointer = firstPointer + (2L shl 32)

        val first = ids.idFor(stream, firstPointer)
        val second = ids.idFor(stream, secondPointer)

        assertEquals(17, first)
        assertTrue(first != second)
        assertEquals(first, ids.idFor(stream, firstPointer))
        assertEquals(second, ids.idFor(stream, secondPointer))

        ids.release(stream, firstPointer)
        assertEquals(17, ids.idFor(stream, thirdPointer))
        assertTrue(ids.idFor(stream, thirdPointer) != second)
        ids.release(stream, secondPointer)
        ids.release(stream, thirdPointer)
    }

    @Test fun equalPointerIdsFromSeparateOverlaysStayDistinctUntilReleased() {
        val ids = TouchPointerIds()
        val firstOverlay = Any()
        val secondOverlay = Any()
        val pointerId = 17L

        val first = ids.idFor(firstOverlay, pointerId)
        val second = ids.idFor(secondOverlay, pointerId)

        assertTrue(first != second)
        assertEquals(first, ids.idFor(firstOverlay, pointerId))
        assertEquals(second, ids.idFor(secondOverlay, pointerId))

        ids.release(firstOverlay, pointerId)
        val replacementOverlay = Any()
        assertEquals(first, ids.idFor(replacementOverlay, pointerId))
        assertEquals(second, ids.idFor(secondOverlay, pointerId))
        ids.release(secondOverlay, pointerId)
        ids.release(replacementOverlay, pointerId)
    }
}
