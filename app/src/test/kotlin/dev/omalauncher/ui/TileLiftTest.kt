package dev.omalauncher.ui

import androidx.compose.ui.geometry.Rect
import dev.omalauncher.ui.TerminalTiles.Companion.liftAndCut
import dev.omalauncher.ui.TerminalTiles.Companion.typingBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TileLiftTest {
    private val tall = Rect(0f, 100f, 1000f, 2000f)

    private fun lift(
        bounds: Rect,
        ime: Int,
        target: Int = ime,
        margin: Float = 8f,
    ) = liftAndCut(bounds, ime, target, windowHeight = 2400, margin = margin, top = 50f, minHeight = 64f)

    @Test fun aFieldKeepsItsTallTileAboveTheKeyboardWithoutATerminal() {
        val bounds = typingBounds(fieldFocused = true, tile = tall, terminal = null)!!
        val (l, c) = lift(bounds, 900)
        assertEquals(50f, l, 0f)
        assertEquals(458f, c, 0f)
        assertEquals(1492f, bounds.bottom - l - c, 0f)
    }

    @Test fun aFieldWaitsForItsOwnTileRatherThanATerminalsBounds() {
        assertNull(typingBounds(fieldFocused = true, tile = null, terminal = tall))
        val next = Rect(0f, 1000f, 1000f, 1800f)
        assertEquals(308f to 0f, lift(typingBounds(fieldFocused = true, tile = next, terminal = null)!!, 900))
    }

    @Test fun nothingTypingLiftsNothing() {
        assertNull(typingBounds(fieldFocused = false, tile = tall, terminal = null))
    }

    @Test fun aClosedKeyboardLiftsNothingAndReopeningLiftsAgain() {
        assertEquals(0f to 0f, lift(tall, 0))
        assertEquals(50f to 458f, lift(tall, 900))
    }

    @Test fun theCutFollowsTheKeyboardsTargetNotItsAnimation() {
        assertEquals(0f to 458f, lift(tall, 0, target = 900))
        assertEquals(50f to 458f, lift(tall, 450, target = 900))
        assertEquals(50f to 458f, lift(tall, 900))
        assertEquals(50f to 158f, lift(tall, 600))
    }

    @Test fun aTerminalFallsBackToItsOwnBoundsAndClearsTheExtraKeys() {
        val bounds = typingBounds(fieldFocused = false, tile = null, terminal = tall)!!
        assertSame(tall, bounds)
        val (l, c) = lift(bounds, 900, margin = 108f)
        assertEquals(50f, l, 0f)
        assertEquals(558f, c, 0f)
        assertEquals(1392f, bounds.bottom - l - c, 0f)
        val (_, most) = lift(bounds, 2500, margin = 108f)
        assertEquals(1836f, most, 0f)
        assertEquals(64f, bounds.height - most, 0f)
    }
}
