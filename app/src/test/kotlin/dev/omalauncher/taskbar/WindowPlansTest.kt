package dev.omalauncher.taskbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowPlansTest {
    @Test fun theAppsFirstWindowTakesThePlanOnce() {
        val plans = WindowPlans<String, String>(ttlMs = 10_000)
        plans.add("foot", "left", now = 0)
        assertNull(plans.take(now = 100) { it == "btop" })
        assertEquals("left", plans.take(now = 200) { it == "foot" })
        assertNull(plans.take(now = 300) { it == "foot" })
    }

    @Test fun aPlanExpires() {
        val plans = WindowPlans<String, String>(ttlMs = 10_000)
        plans.add("foot", "left", now = 0)
        assertEquals("left", plans.take(now = 9_999) { it == "foot" })
        plans.add("foot", "left", now = 0)
        assertNull(plans.take(now = 10_000) { it == "foot" })
    }

    @Test fun aNewerDropReplacesTheAppsPlan() {
        val plans = WindowPlans<String, String>(ttlMs = 10_000)
        plans.add("foot", "left", now = 0)
        plans.add("foot", "replace", now = 5_000)
        assertEquals("replace", plans.take(now = 12_000) { it == "foot" })
        assertNull(plans.take(now = 12_000) { it == "foot" })
    }

    @Test fun eachAppWaitsForItsOwnWindow() {
        val plans = WindowPlans<String, String>()
        plans.add("foot", "left", now = 0)
        plans.add("btop", "right", now = 0)
        assertEquals("right", plans.take(now = 1) { it == "btop" })
        assertEquals("left", plans.take(now = 1) { it == "foot" })
    }
}
