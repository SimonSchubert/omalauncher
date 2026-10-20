package dev.omalauncher.ui

import org.junit.Assert.*
import org.junit.Test

class InputLeaseTest {
    @Test fun cancellationDropsPendingMotionAndClosesAnExecutedGestureExactlyOnce() {
        val queue = ArrayDeque<() -> Unit>()
        val delivered = mutableListOf<String>()
        val lease = InputLease({ queue.addLast(it) }) { delivered += "cancel touch; release mouse; stop axes" }
        lease.post { delivered += "down" }
        queue.removeFirst()()
        lease.post { delivered += "stale motion" }
        lease.close()
        lease.close()
        lease.post { delivered += "late up" }
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(listOf("down", "cancel touch; release mouse; stop axes"), delivered)
    }

    @Test fun queuedPressCancelledBeforeDeliveryNeverStartsAGesture() {
        val queue = ArrayDeque<() -> Unit>()
        var pressed = false
        val lease = InputLease({ queue.addLast(it) }) { pressed = false }
        lease.post { pressed = true }
        lease.close()
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertFalse(pressed)
    }

    @Test fun losingFocusCleansThePreviousOwnerBeforeAnotherWindowGetsInput() {
        val queue = ArrayDeque<() -> Unit>()
        val delivered = mutableListOf<String>()
        fun lease(owner: String) = InputLease({ queue.addLast(it) }) { delivered += "release $owner" }
        val old = lease("a")
        old.post { delivered += "down a" }
        queue.removeFirst()()
        old.close()
        val next = lease("b")
        next.post { delivered += "down b" }
        old.close() // A replaced view can dispose after the new owner has started.
        old.post { delivered += "motion a" }
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(listOf("down a", "release a", "down b"), delivered)
        assertTrue(next.open)
        assertFalse(old.open)
    }
}
