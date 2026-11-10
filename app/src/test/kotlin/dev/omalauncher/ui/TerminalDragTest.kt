package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalDragTest {
    private fun drag(dx: Float, dy: Float, held: Long = 10) =
        terminalDrag(dx, dy, slop = 10f, heldMillis = held, longPressMillis = 500)

    @Test fun aTapAndSmallMovementAreNotTaken() {
        assertEquals(TerminalDrag.Waiting, drag(0f, 0f))
        assertEquals(TerminalDrag.Waiting, drag(6f, 8f))
    }

    @Test fun verticalDragsInBothDirectionsScroll() {
        assertEquals(TerminalDrag.Scroll, drag(2f, -20f))
        assertEquals(TerminalDrag.Scroll, drag(-2f, 20f))
    }

    @Test fun sidewaysDragsInBothDirectionsBelongToThePager() {
        assertEquals(TerminalDrag.PassThrough, drag(20f, 2f))
        assertEquals(TerminalDrag.PassThrough, drag(-20f, -2f))
    }

    @Test fun diagonalTiesDoNotStealThePageSwipe() {
        assertEquals(TerminalDrag.PassThrough, drag(20f, 20f))
    }

    @Test fun aLongPressAndItsDragRemainAvailableForArrangement() {
        assertEquals(TerminalDrag.PassThrough, drag(0f, 0f, held = 500))
        assertEquals(TerminalDrag.PassThrough, drag(0f, 40f, held = 600))
        assertEquals(TerminalDrag.Scroll, drag(0f, 40f, held = 499))
    }
}
