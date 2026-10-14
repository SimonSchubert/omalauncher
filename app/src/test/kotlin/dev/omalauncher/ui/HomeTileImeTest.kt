package dev.omalauncher.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HomeTileImeTest {
    @Test fun ordinaryInputKeepsItsTallTileAboveImeWithoutATerminal() {
        val ime = HomeTileIme()
        ime.focusInput(Any(), Any())
        ime.tile = Rect(0f, 100f, 1000f, 2000f)

        assertEquals(50f, ime.liftFor(null, 900, 900, 2400, 8f, 50f, 64f), 0f)
        assertEquals(458f, ime.cut, 0f)
        assertEquals(1492f, ime.tile!!.bottom - ime.lift - ime.cut, 0f)
    }

    @Test fun lateReleaseDoesNotDropTheNextInputInTheSameTile() {
        val ime = HomeTileIme()
        val owner = Any()
        val first = Any()
        val second = Any()
        ime.focusInput(owner, first)
        ime.tile = Rect(0f, 100f, 1000f, 2000f)
        ime.focusInput(owner, second)
        ime.releaseInput(first)

        assertSame(owner, ime.inputOwner)
        assertEquals(50f, ime.liftFor(null, 900, 900, 2400, 8f, 50f, 64f), 0f)
        assertEquals(458f, ime.cut, 0f)
    }

    @Test fun changingTilesCannotUseTheOldTilesBoundsOrATerminalFallback() {
        val ime = HomeTileIme()
        val old = Any()
        val next = Any()
        ime.focusInput(Any(), old)
        ime.tile = Rect(0f, 100f, 1000f, 2000f)
        ime.liftFor(null, 900, 900, 2400, 8f, 50f, 64f)
        ime.focusInput(Any(), next)
        ime.releaseInput(old)

        assertEquals(0f, ime.liftFor(Rect(0f, 100f, 1000f, 2000f), 900, 900, 2400, 8f, 50f, 64f), 0f)
        assertEquals(0f, ime.cut, 0f)
        ime.tile = Rect(0f, 1000f, 1000f, 1800f)
        assertEquals(308f, ime.liftFor(null, 900, 900, 2400, 8f, 50f, 64f), 0f)
        assertEquals(0f, ime.cut, 0f)
    }

    @Test fun releasingOrResettingInputRestoresTheUncutTile() {
        val ime = HomeTileIme()
        val token = Any()
        ime.focusInput(Any(), token)
        ime.tile = Rect(0f, 100f, 1000f, 2000f)
        ime.liftFor(null, 900, 900, 2400, 8f, 50f, 64f)
        ime.releaseInput(token)

        assertNull(ime.inputOwner)
        assertEquals(0f, ime.liftFor(null, 900, 900, 2400, 8f, 50f, 64f), 0f)
        assertEquals(0f, ime.cut, 0f)

        ime.focusInput(Any(), Any())
        ime.tile = Rect(0f, 100f, 1000f, 2000f)
        ime.clearInput()
        assertEquals(0f, ime.liftFor(null, 900, 900, 2400, 8f, 50f, 64f), 0f)
        assertEquals(0f, ime.cut, 0f)
    }

    @Test fun keyboardDismissalAndReopeningDoNotForgetTheFocusedInput() {
        val ime = HomeTileIme()
        val owner = Any()
        ime.focusInput(owner, Any())
        ime.tile = Rect(0f, 100f, 1000f, 2000f)
        ime.liftFor(null, 900, 900, 2400, 8f, 50f, 64f)

        assertEquals(0f, ime.liftFor(null, 0, 0, 2400, 8f, 50f, 64f), 0f)
        assertEquals(0f, ime.cut, 0f)
        assertSame(owner, ime.inputOwner)
        assertEquals(50f, ime.liftFor(null, 900, 900, 2400, 8f, 50f, 64f), 0f)
        assertEquals(458f, ime.cut, 0f)
    }

    @Test fun inputUsesTargetCutWithoutFeedingTheCutBackIntoMeasuredBounds() {
        val ime = HomeTileIme()
        ime.focusInput(Any(), Any())
        ime.tile = Rect(0f, 100f, 1000f, 2000f)

        assertEquals(0f, ime.liftFor(null, 0, 900, 2400, 8f, 50f, 64f), 0f)
        assertEquals(458f, ime.cut, 0f)
        assertEquals(50f, ime.liftFor(null, 450, 900, 2400, 8f, 50f, 64f), 0f)
        assertEquals(458f, ime.cut, 0f)
        assertEquals(50f, ime.liftFor(null, 900, 900, 2400, 8f, 50f, 64f), 0f)
        assertEquals(458f, ime.cut, 0f)
        assertEquals(50f, ime.liftFor(null, 600, 600, 2400, 8f, 50f, 64f), 0f)
        assertEquals(158f, ime.cut, 0f)
    }

    @Test fun terminalKeepsItsFallbackBoundsAndExtraKeysClearance() {
        val ime = HomeTileIme()
        val terminal = Rect(0f, 100f, 1000f, 2000f)

        assertEquals(50f, ime.liftFor(terminal, 900, 900, 2400, 108f, 50f, 64f), 0f)
        assertEquals(558f, ime.cut, 0f)
        assertEquals(1392f, terminal.bottom - ime.lift - ime.cut, 0f)
        ime.liftFor(terminal, 2500, 2500, 2400, 108f, 50f, 64f)
        assertEquals(1836f, ime.cut, 0f)
        assertEquals(64f, terminal.height - ime.cut, 0f)
    }
}
