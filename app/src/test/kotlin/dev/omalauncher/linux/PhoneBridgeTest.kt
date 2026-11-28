package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [allowedCall] and [cappedCallMap]: the rate-limit arithmetic behind PhoneBridge's `allow()`, which guards
 * `location`/`calendar`/`link` against a tight polling loop from an unauthenticated guest process.
 */
class PhoneBridgeTest {
    @Test fun allowsTheFirstCallAndDeniesAnEarlyRepeat() {
        val calls = HashMap<String, Long>()
        assertTrue(allowedCall(calls, "location", 2_000L, 1_000L))
        assertFalse(allowedCall(calls, "location", 2_000L, 2_000L))
        assertFalse(allowedCall(calls, "location", 2_000L, 2_999L))
        assertTrue(allowedCall(calls, "location", 2_000L, 3_000L))
    }

    @Test fun eachKeyIsRateLimitedOnItsOwn() {
        val calls = HashMap<String, Long>()
        assertTrue(allowedCall(calls, "link:alice", 500L, 1_000L))
        assertTrue(allowedCall(calls, "link:bob", 500L, 1_000L))
        assertFalse(allowedCall(calls, "link:alice", 500L, 1_200L))
        // bob's own clock is unaffected by alice's calls.
        assertTrue(allowedCall(calls, "link:bob", 500L, 1_600L))
    }

    @Test fun aDeniedCallDoesNotResetTheClock() {
        val calls = HashMap<String, Long>()
        assertTrue(allowedCall(calls, "calendar", 2_000L, 1_000L))
        assertFalse(allowedCall(calls, "calendar", 2_000L, 1_500L))
        // Still measured from the 1_000L call that was actually allowed, not the denied one at 1_500L.
        assertFalse(allowedCall(calls, "calendar", 2_000L, 2_900L))
        assertTrue(allowedCall(calls, "calendar", 2_000L, 3_000L))
    }

    @Test fun cappedCallMapDropsTheLeastRecentlyUsedKeyFirst() {
        val calls = cappedCallMap()
        repeat(MAX_LAST_CALL_KEYS) { allowedCall(calls, "link:$it", 0L, it.toLong()) }
        assertEquals(MAX_LAST_CALL_KEYS, calls.size)
        // One more, made-up key: the least-recently-used one (link:0, never touched again) is dropped, not a
        // recently-used one.
        assertTrue(allowedCall(calls, "link:new", 0L, MAX_LAST_CALL_KEYS.toLong()))
        assertEquals(MAX_LAST_CALL_KEYS, calls.size)
        assertFalse(calls.containsKey("link:0"))
        assertTrue(calls.containsKey("link:new"))
    }

    @Test fun cappedCallMapNeverGrowsPastItsCapNoMatterHowManyDistinctKeysFlood() {
        // The scenario this guards: a guest process floods `link` with a different fabricated target id each time,
        // which without a cap would grow this map by one permanent entry per request for the life of the process.
        val calls = cappedCallMap()
        repeat(MAX_LAST_CALL_KEYS * 50) { allowedCall(calls, "link:$it", 0L, it.toLong()) }
        assertEquals(MAX_LAST_CALL_KEYS, calls.size)
    }
}
