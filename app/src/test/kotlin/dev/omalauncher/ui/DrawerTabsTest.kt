package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerTabsTest {
    private val all = drawerTabs(work = true)

    @Test fun tabsFollowTheProfiles() {
        assertEquals(listOf(DrawerTab.Personal), drawerTabs(work = false))
        assertEquals(listOf(DrawerTab.Personal, DrawerTab.Work), all)
        assertEquals(listOf(DrawerTab.Personal, DrawerTab.Private), drawerTabs(work = false, privateSpace = true))
        assertEquals(listOf(DrawerTab.Personal, DrawerTab.Work, DrawerTab.Private), drawerTabs(work = true, privateSpace = true))
        assertEquals(listOf(DrawerTab.Personal), drawerTabs(work = false, privateSpace = true, privateShown = false))
    }

    @Test fun privateTabIsStableWhileLockedOrEmpty() {
        // Tab membership uses profile existence, not the current launchable-app count.
        val lockedWithNoApps = drawerTabs(work = false, privateSpace = true)
        assertEquals(DrawerTab.Private, swipedTab(lockedWithNoApps, DrawerTab.Personal, -80f, 50f))
    }

    @Test fun swipingLeftBringsTheNextTab() {
        assertEquals(DrawerTab.Work, swipedTab(all, DrawerTab.Personal, -80f, 50f))
    }

    @Test fun swipingRightGoesBack() {
        assertEquals(DrawerTab.Personal, swipedTab(all, DrawerTab.Work, 80f, 50f))
    }

    @Test fun shortSwipesAndEndsStayPut() {
        assertEquals(DrawerTab.Personal, swipedTab(all, DrawerTab.Personal, -30f, 50f))
        assertEquals(DrawerTab.Personal, swipedTab(all, DrawerTab.Personal, 80f, 50f))
        assertEquals(DrawerTab.Work, swipedTab(all, DrawerTab.Work, -80f, 50f))
    }
}
