package dev.omalauncher.ui.splitdrag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitDragSessionTest {
    @Test fun followsTheFingerOverTheLauncher() {
        val s = SplitDragSession()
        s.begin("Maps")
        assertEquals("Maps", s.app)
        assertNull(s.finger)
        s.move(10f, 20f)
        assertEquals(10f to 20f, s.finger)
    }

    @Test fun leavingHandsOverToTheSystem() {
        val s = SplitDragSession()
        s.begin("Maps")
        s.move(10f, 20f)
        s.leave()
        assertNull(s.finger)
        assertEquals("Maps", s.app)
    }

    @Test fun endingClearsEverything() {
        val s = SplitDragSession()
        s.begin("Maps")
        s.move(10f, 20f)
        s.end()
        assertNull(s.app)
        assertNull(s.finger)
    }
}
