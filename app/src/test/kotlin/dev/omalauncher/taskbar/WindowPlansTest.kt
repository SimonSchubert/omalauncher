package dev.omalauncher.taskbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun clearingDropsEveryPlan() {
        val plans = WindowPlans<String, String>()
        plans.add("foot", "left", now = 0)
        plans.add("btop", "right", now = 0)
        plans.clear()
        assertNull(plans.take(now = 1) { true })
    }

    @Test fun plansWaitAboutSixSeconds() {
        val plans = WindowPlans<String, String>()
        plans.add("foot", "left", now = 0)
        assertNull(plans.take(now = WINDOW_PLAN_MS) { it == "foot" })
        assertEquals(6_000L, WINDOW_PLAN_MS)
    }

    @Test fun aPlanFitsWhileThePartnerIsStillOnScreen() {
        assertTrue(planStillFits(ScreenState.Single("maps"), ScreenState.Single("maps")))
        assertFalse(planStillFits(ScreenState.Single("maps"), ScreenState.Single("mail")))
        assertFalse(planStillFits(ScreenState.Single("maps"), ScreenState.Home))
        assertFalse(planStillFits(ScreenState.Single("maps"), ScreenState.Split("maps", "mail", sideBySide = true)))
    }

    @Test fun aReplaceFitsWhileTheSplitIsStillThere() {
        val split = ScreenState.Split("maps", "mail", sideBySide = true)
        assertTrue(planStillFits(split, split))
        assertTrue(planStillFits(split, ScreenState.Split("maps", "notes", sideBySide = true)))
        assertFalse(planStillFits(split, ScreenState.Single("maps")))
        assertFalse(planStillFits(split, ScreenState.Unknown))
    }
}
