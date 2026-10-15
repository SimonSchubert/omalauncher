package dev.omalauncher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowInputTest {
    @Test fun ternGetsDragToScrollEvenWhenItBindsTouch() {
        assertTrue(prefersPointerGestures("tern"))
        assertTrue(prefersPointerGestures("so.stencil.tern"))
    }

    @Test fun touchClientsKeepTheirNativeGestures() {
        assertFalse(prefersPointerGestures("foot"))
        assertFalse(prefersPointerGestures("org.gnome.TextEditor"))
    }

    @Test fun compatibilityDoesNotMatchUnrelatedAppIds() {
        assertFalse(prefersPointerGestures("org.example.terminal"))
        assertFalse(prefersPointerGestures("org.example.tern"))
        assertFalse(prefersPointerGestures(""))
    }
}
