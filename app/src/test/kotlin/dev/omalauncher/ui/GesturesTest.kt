package dev.omalauncher.ui

import dev.omalauncher.ui.GestureAction.Camera
import dev.omalauncher.ui.GestureAction.Drawer
import dev.omalauncher.ui.GestureAction.None
import org.junit.Assert.assertEquals
import org.junit.Test

class GesturesTest {
    @Test fun swipeChoicesIncludeExpectedActions() {
        assertEquals(listOf(Drawer, None), SwipeUpChoices)
        assertEquals(listOf(Camera, None), SwipeRightChoices)
    }

    @Test fun gestureActionLookup() {
        assertEquals(Drawer, GestureAction.of("drawer"))
        assertEquals(Camera, GestureAction.of("camera"))
        assertEquals(None, GestureAction.of("none"))
        assertEquals(null, GestureAction.of("unknown"))
    }
}
