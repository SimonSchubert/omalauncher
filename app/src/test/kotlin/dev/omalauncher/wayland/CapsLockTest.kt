package dev.omalauncher.wayland

import org.junit.Assert.assertEquals
import org.junit.Test

class CapsLockTest {
    private val numLock = 16

    @Test fun androidStateWinsOverOurCopy() {
        assertEquals(MOD_LOCK, lockedModsAfter(0, 0, pressed = true, capsLock = true))
        assertEquals(0, lockedModsAfter(MOD_LOCK, 0, pressed = true, capsLock = false))
    }

    @Test fun capsLockKeyTakesAndroidsNewStateInsteadOfTogglingTwice() {
        // Android already reports the state the key just switched to.
        assertEquals(MOD_LOCK, lockedModsAfter(0, MOD_LOCK, pressed = true, capsLock = true))
        assertEquals(MOD_LOCK, lockedModsAfter(MOD_LOCK, MOD_LOCK, pressed = false, capsLock = true))
    }

    @Test fun withoutAndroidsStateTheKeyTogglesOnPress() {
        assertEquals(MOD_LOCK, lockedModsAfter(0, MOD_LOCK, pressed = true, capsLock = null))
        assertEquals(MOD_LOCK, lockedModsAfter(MOD_LOCK, MOD_LOCK, pressed = false, capsLock = null))
        assertEquals(0, lockedModsAfter(MOD_LOCK, MOD_LOCK, pressed = true, capsLock = null))
        assertEquals(MOD_LOCK, lockedModsAfter(MOD_LOCK, 0, pressed = true, capsLock = null))
    }

    @Test fun otherLockedModifiersStay() {
        assertEquals(numLock or MOD_LOCK, withCapsLock(numLock, true))
        assertEquals(numLock, withCapsLock(numLock or MOD_LOCK, false))
    }
}
