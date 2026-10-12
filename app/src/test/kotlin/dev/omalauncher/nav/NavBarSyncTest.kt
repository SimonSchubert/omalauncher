package dev.omalauncher.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavBarSyncTest {
    @Test fun phoneButtonsWinOverAndroidGestures() {
        assertEquals(SystemNavigation.ThreeButtons, effectiveMode(fullscreenGestures = 0, navigationMode = 2))
    }

    @Test fun switchOffWithNoModeStillMeansButtons() {
        assertEquals(SystemNavigation.ThreeButtons, effectiveMode(fullscreenGestures = 0, navigationMode = null))
    }

    @Test fun phoneGesturesAreGestures() {
        assertEquals(SystemNavigation.Gestures, effectiveMode(fullscreenGestures = 1, navigationMode = 0))
    }

    @Test fun phoneWithoutItsOwnSwitchUsesAndroidMode() {
        assertEquals(SystemNavigation.TwoButtons, effectiveMode(fullscreenGestures = null, navigationMode = 1))
        assertNull(effectiveMode(fullscreenGestures = null, navigationMode = null))
    }

    @Test fun gesturesNeedTheServiceActuallyRunning() {
        assertEquals(BarTarget.Buttons, barTarget(gesturesEnabled = true, serviceRunning = false))
        assertEquals(BarTarget.Buttons, barTarget(gesturesEnabled = false, serviceRunning = true))
        assertEquals(BarTarget.Gestures, barTarget(gesturesEnabled = true, serviceRunning = true))
    }

    @Test fun splitStateFlipsTheSwitchSoSystemUiNotices() {
        assertEquals(BarWrite(fsg = 0, mode = 0, toggleFsg = true), barWrite(BarTarget.Buttons, fsg = 0, mode = 2))
    }

    @Test fun matchingButtonsAreLeftAlone() {
        assertNull(barWrite(BarTarget.Buttons, fsg = 0, mode = 0))
    }

    @Test fun twoButtonModeIsKept() {
        assertNull(barWrite(BarTarget.Buttons, fsg = 0, mode = 1))
    }

    @Test fun hidingButtonsWritesBothSettings() {
        assertEquals(BarWrite(fsg = 1, mode = 2, toggleFsg = false), barWrite(BarTarget.Gestures, fsg = 0, mode = 0))
    }

    @Test fun otherPhonesAreNotWritten() {
        assertNull(barWrite(BarTarget.Buttons, fsg = null, mode = 2))
    }
}
