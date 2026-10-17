package dev.omalauncher.taskbar

import dev.omalauncher.ui.splitdrag.ZoneBox
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenStateTest {
    private val home = setOf("dev.omalauncher.debug")
    private fun w(pkg: String, l: Float, t: Float, r: Float, b: Float) = AppWindow(pkg, ZoneBox(l, t, r, b))

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
}
