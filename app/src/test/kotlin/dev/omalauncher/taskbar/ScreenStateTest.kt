package dev.omalauncher.taskbar

import dev.omalauncher.ui.splitdrag.ZoneBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStateTest {
    private val home = setOf("dev.omalauncher.debug")
    private fun w(pkg: String, l: Float, t: Float, r: Float, b: Float, title: String? = null) = AppWindow(pkg, ZoneBox(l, t, r, b), title)

    @Test fun oneFullScreenAppIsSingle() {
        assertEquals(ScreenState.Single("maps"), screenState(listOf(w("maps", 0f, 0f, 1856f, 2160f)), 1856f, 2160f, home))
    }

    @Test fun theLauncherInFrontIsHome() {
        assertEquals(ScreenState.Home, screenState(listOf(w("dev.omalauncher.debug", 0f, 0f, 1856f, 2160f)), 1856f, 2160f, home))
    }

    @Test fun unfoldedSplitIsLeftThenRight() {
        // topmost first: the right half is focused
        val wins = listOf(w("mail", 935f, 0f, 1856f, 2160f), w("maps", 0f, 0f, 921f, 2160f))
        assertEquals(ScreenState.Split("maps", "mail", sideBySide = true), screenState(wins, 1856f, 2160f, home))
    }

    @Test fun foldedSplitIsTopThenBottom() {
        val wins = listOf(w("maps", 0f, 0f, 968f, 1180f), w("mail", 0f, 1196f, 968f, 2376f))
        assertEquals(ScreenState.Split("maps", "mail", sideBySide = false), screenState(wins, 968f, 2376f, home))
    }

    @Test fun aPopUpOverAFullScreenAppKeepsItSingle() {
        val wins = listOf(w("notes", 400f, 600f, 1200f, 1400f), w("maps", 0f, 0f, 1856f, 2160f))
        assertEquals(ScreenState.Single("maps"), screenState(wins, 1856f, 2160f, home))
    }

    @Test fun aPopUpOverASplitKeepsTheSplit() {
        val wins = listOf(w("notes", 400f, 600f, 1200f, 1400f), w("mail", 935f, 0f, 1856f, 2160f), w("maps", 0f, 0f, 921f, 2160f))
        assertEquals(ScreenState.Split("maps", "mail", sideBySide = true), screenState(wins, 1856f, 2160f, home))
    }

    @Test fun noWindowsIsUnknown() {
        assertEquals(ScreenState.Unknown, screenState(emptyList(), 1856f, 2160f, home))
    }

    @Test fun anUnevenSplitIsStillASplit() {
        val wins = listOf(w("maps", 0f, 0f, 600f, 2160f), w("mail", 614f, 0f, 1856f, 2160f))
        assertEquals(ScreenState.Split("maps", "mail", sideBySide = true), screenState(wins, 1856f, 2160f, home))
    }

    @Test fun aLetterboxedAppIsSingle() {
        assertEquals(ScreenState.Single("maps"), screenState(listOf(w("maps", 320f, 0f, 1536f, 2160f)), 1856f, 2160f, home))
    }

    @Test fun aLargePopUpOverAnAppKeepsItSingle() {
        val wins = listOf(w("notes", 200f, 300f, 1600f, 1800f), w("maps", 0f, 0f, 1856f, 2160f))
        assertEquals(ScreenState.Single("maps"), screenState(wins, 1856f, 2160f, home))
    }

    @Test fun aPopUpOverOneHalfKeepsTheSplit() {
        val wins = listOf(w("notes", 100f, 300f, 800f, 1200f), w("maps", 0f, 0f, 921f, 2160f), w("mail", 935f, 0f, 1856f, 2160f))
        assertEquals(ScreenState.Split("maps", "mail", sideBySide = true), screenState(wins, 1856f, 2160f, home))
    }

    @Test fun aPopUpAcrossBothHalvesKeepsTheSplit() {
        val wins = listOf(w("notes", 600f, 300f, 1300f, 1200f), w("maps", 0f, 0f, 921f, 2160f), w("mail", 935f, 0f, 1856f, 2160f))
        assertEquals(ScreenState.Split("maps", "mail", sideBySide = true), screenState(wins, 1856f, 2160f, home))
    }

    @Test fun ownWindowsOtherThanHomeAreUnknown() {
        val own = "dev.omalauncher.debug"
        assertEquals(ScreenState.Unknown, screenState(listOf(w(own, 0f, 0f, 1856f, 2160f)), 1856f, 2160f, home = emptySet(), opaque = setOf(own)))
    }

    @Test fun aSplitWithAnOpaqueHalfIsUnknown() {
        val own = "dev.omalauncher.debug"
        val wins = listOf(w(own, 935f, 0f, 1856f, 2160f), w("maps", 0f, 0f, 921f, 2160f))
        assertEquals(ScreenState.Unknown, screenState(wins, 1856f, 2160f, home = emptySet(), opaque = setOf(own)))
    }

    @Test fun anAppsOwnDialogInsideItsHalfKeepsTheSplit() {
        val wins = listOf(w("photos", 300f, 300f, 1600f, 900f), w("photos", 0f, 0f, 2076f, 1068f), w("contacts", 0f, 1084f, 2076f, 2152f))
        assertEquals(ScreenState.Split("photos", "contacts", sideBySide = false), screenState(wins, 2076f, 2152f, home))
    }

    private val own = "dev.omalauncher.debug"
    private val terminal = LinuxWindow("s-1", "Terminal")
    private val btop = LinuxWindow("s-2", "btop")

    @Test fun ourLinuxWindowInFrontIsSingle() {
        val state = screenState(listOf(w(own, 0f, 0f, 1856f, 2160f)), 1856f, 2160f, home = emptySet(), opaque = setOf(own), own = own, linux = listOf(terminal))
        assertEquals(ScreenState.Single(terminal.id), state)
        assertTrue(isLinuxWindow(terminal.id))
        assertFalse(isLinuxWindow("maps"))
    }

    @Test fun ourLinuxWindowInASplitIsAHalf() {
        val wins = listOf(w(own, 935f, 0f, 1856f, 2160f), w("maps", 0f, 0f, 921f, 2160f))
        val state = screenState(wins, 1856f, 2160f, home = emptySet(), opaque = setOf(own), own = own, linux = listOf(terminal))
        assertEquals(ScreenState.Split("maps", terminal.id, sideBySide = true), state)
    }

    @Test fun anotherOfOurWindowsInASplitIsAHalfToo() {
        // Replacing a half relaunches neither app, so even Files can be one.
        val wins = listOf(w(own, 935f, 0f, 1856f, 2160f), w("maps", 0f, 0f, 921f, 2160f))
        assertEquals(ScreenState.Split("maps", own, sideBySide = true), screenState(wins, 1856f, 2160f, home = emptySet(), opaque = setOf(own), own = own))
    }

    @Test fun anotherLaunchersWindowInASplitStaysUnknown() {
        val wins = listOf(w("dev.omalauncher", 935f, 0f, 1856f, 2160f), w("maps", 0f, 0f, 921f, 2160f))
        assertEquals(ScreenState.Unknown, screenState(wins, 1856f, 2160f, home = emptySet(), opaque = setOf(own, "dev.omalauncher"), own = own, linux = listOf(terminal)))
    }

    @Test fun ourWindowsOtherThanLinuxInFrontStayUnknown() {
        assertEquals(ScreenState.Unknown, screenState(listOf(w(own, 0f, 0f, 1856f, 2160f)), 1856f, 2160f, home = emptySet(), opaque = setOf(own), own = own))
    }

    @Test fun twoLinuxWindowsPairByTitleThenInOrder() {
        val wins = listOf(w(own, 0f, 0f, 10f, 10f, "btop"), w(own, 0f, 0f, 20f, 20f, "Linux"))
        assertEquals(listOf(btop.id, terminal.id), linuxWindows(wins, own, listOf(terminal, btop)).map { it.packageName })
    }

    @Test fun moreOfOurWindowsThanLinuxOnesKeepThePackage() {
        val wins = listOf(w(own, 0f, 0f, 10f, 10f), w(own, 0f, 0f, 20f, 20f), w("maps", 0f, 0f, 30f, 30f))
        assertEquals(wins, linuxWindows(wins, own, listOf(terminal)))
    }

    @Test fun ourHomeInFrontIsNeverALinuxWindow() {
        val state = screenState(listOf(w(own, 0f, 0f, 1856f, 2160f)), 1856f, 2160f, home = setOf(own), opaque = setOf(own), own = own, linux = listOf(terminal))
        assertEquals(ScreenState.Home, state)
    }

    @Test fun evenATinyWindowOfOursCountsWhenPairing() {
        // Two windows of ours, one too small to see, and one Linux window: which is which is unsure, so neither is named.
        val wins = listOf(w(own, 0f, 0f, 50f, 50f), w(own, 0f, 0f, 1856f, 2160f))
        assertEquals(ScreenState.Unknown, screenState(wins, 1856f, 2160f, home = emptySet(), opaque = setOf(own), own = own, linux = listOf(terminal)))
    }
}
