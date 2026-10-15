package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerTabsTest {
    private val all = drawerTabs(work = true)

    @Test fun tabsFollowTheProfiles() {
        assertEquals(listOf(DrawerTab.Personal), drawerTabs(work = false))
        assertEquals(listOf(DrawerTab.Personal, DrawerTab.Work), all)
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
