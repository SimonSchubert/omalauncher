package dev.omalauncher.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class GamePadTest {
    private val r = 100f

    @Test fun theMiddleIsDead() {
        assertEquals(emptySet<PadButton>(), dpadDirections(Offset(5f, -5f), r))
    }

    @Test fun armsAndDiagonals() {
        assertEquals(setOf(PadButton.Up), dpadDirections(Offset(0f, -80f), r))
        assertEquals(setOf(PadButton.Down), dpadDirections(Offset(0f, 80f), r))
        assertEquals(setOf(PadButton.Left), dpadDirections(Offset(-80f, 0f), r))
        assertEquals(setOf(PadButton.Right), dpadDirections(Offset(80f, 0f), r))
        assertEquals(setOf(PadButton.Up, PadButton.Right), dpadDirections(Offset(60f, -60f), r))
        assertEquals(setOf(PadButton.Down, PadButton.Left), dpadDirections(Offset(-60f, 60f), r))
    }

    @Test fun keysRoundTripAndBadEntriesAreDropped() {
        val keys = mapOf(PadButton.A to 103, PadButton.Start to 1)
        assertEquals(keys, parseKeys(formatKeys(keys)))
        assertEquals(mapOf(PadButton.B to 57), parseKeys("B=57,Nope=3,A=99999,Up=x"))
        assertEquals(emptyMap<PadButton, Int>(), parseKeys(null))
    }

    @Test fun aButtonCanBeUnmapped() {
        assertEquals(mapOf(PadButton.A to KeyNames.NONE), parseKeys(formatKeys(mapOf(PadButton.A to KeyNames.NONE))))
        assertEquals("none", KeyNames.name(KeyNames.NONE))
    }

    @Test fun everyDefaultHasAName() {
        PadButton.entries.forEach { assertEquals(true, KeyNames.name(it.defaultKey) != null) }
    }
}
