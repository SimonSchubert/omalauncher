package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalGestureTest {
    @Test fun sidewaysOnAnInertTileTurnsThePage() {
        assertEquals(TerminalGesture.TurnPage, terminalGesture(tileActive = false, dx = 40f, dy = 5f))
    }

    @Test fun aVerticalDragScrollsTheTile() {
        assertEquals(TerminalGesture.ScrollTile, terminalGesture(tileActive = false, dx = 4f, dy = -30f))
    }

    @Test fun anActiveTileKeepsASidewaysDrag() {
        assertEquals(TerminalGesture.ScrollTile, terminalGesture(tileActive = true, dx = 40f, dy = 2f))
    }
}
