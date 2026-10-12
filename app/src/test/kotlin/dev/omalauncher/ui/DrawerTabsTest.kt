package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerTabsTest {
    private val all = drawerTabs(work = true, private = true)

    @Test fun tabsFollowTheProfiles() {
        assertEquals(listOf(DrawerTab.Personal), drawerTabs(work = false))
        assertEquals(listOf(DrawerTab.Personal, DrawerTab.Work), drawerTabs(work = true))
        assertEquals(listOf(DrawerTab.Personal, DrawerTab.Private), drawerTabs(work = false, private = true))
        assertEquals(listOf(DrawerTab.Personal, DrawerTab.Work, DrawerTab.Private), all)
    }

    @Test fun swipingLeftBringsTheNextTab() {
        assertEquals(DrawerTab.Work, swipedTab(all, DrawerTab.Personal, -80f, 50f))
        assertEquals(DrawerTab.Private, swipedTab(all, DrawerTab.Work, -80f, 50f))
    }

    @Test fun swipingRightGoesBack() {
        assertEquals(DrawerTab.Work, swipedTab(all, DrawerTab.Private, 80f, 50f))
    }

    @Test fun shortSwipesAndEndsStayPut() {
        assertEquals(DrawerTab.Personal, swipedTab(all, DrawerTab.Personal, -30f, 50f))
        assertEquals(DrawerTab.Personal, swipedTab(all, DrawerTab.Personal, 80f, 50f))
        assertEquals(DrawerTab.Private, swipedTab(all, DrawerTab.Private, -80f, 50f))
    }

    @Test fun barAsksForThePrivateSpace() {
        assertTrue(asksForPrivate("priv"))
        assertTrue(asksForPrivate("Private"))
        assertTrue(asksForPrivate("private sp"))
        assertFalse(asksForPrivate("pri"))
        assertFalse(asksForPrivate("privacy"))
        assertFalse(asksForPrivate("private spaces"))
    }
}
