package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Key-boundary rules shared by touch handling and cancellation regression tests. */
class ExtraKeysGestureTest {
    private fun cancelled(x: Float, y: Float, popup: Boolean = true) =
        extraKeyDragCancelled(x, y, 80f, 40f, 4f, 4f, popup)

    @Test fun upwardPopupSwipeCanLeaveKeycap() {
        assertFalse(cancelled(40f, -30f, popup = true))
        assertFalse(cancelled(40f, 20f))
        // Natural thumb arc drift during an upward swipe is allowed
        assertFalse(cancelled(-20f, -30f, popup = true))
        assertFalse(cancelled(100f, -30f, popup = true))
        // Extreme sideways drift even when moving up cancels
        assertTrue(cancelled(-90f, -30f, popup = true))
        assertTrue(cancelled(170f, -30f, popup = true))
    }

    private fun releaseAt(x: Float, y: Float, popup: Boolean = true, pressed: Boolean = false) =
        extraKeyGestureStep(x, y, 24f, 80f, 40f, 4f, 4f, 16f, popup, pressed)

    @Test fun completeReleaseCancelsEvenAfterPopupThreshold() {
        // A swipe selected by moving up must not bypass the bounds check.
        assertEquals(Release.Cancelled, releaseAt(-90f, -30f).release)
        assertEquals(Release.Cancelled, releaseAt(170f, -30f).release)
        // Realistic thumb-arc drift is still a popup swipe, including on release.
        assertEquals(Release.Swipe, releaseAt(-20f, -30f).release)
        assertEquals(Release.Swipe, releaseAt(100f, -30f).release)
        assertTrue(releaseAt(-20f, -30f).selected)
    }

    @Test fun completeReleaseDistinguishesTapPopupAndCancellation() {
        assertEquals(Release.Tap, releaseAt(40f, 22f).release)
        assertEquals(Release.Cancelled, releaseAt(85f, 22f).release)
        assertEquals(Release.Cancelled, releaseAt(40f, -30f, popup = false).release)
        assertNull(releaseAt(40f, -30f, pressed = true).release)
    }

    @Test fun slidingOffTheSidesCancelsEvenForPopupKeys() {
        assertTrue(cancelled(-5f, 16f))
        assertTrue(cancelled(85f, 16f))
        assertFalse(cancelled(83f, 16f))
    }

    @Test fun slidingBelowKeycapCancelsEvenForPopupKeys() {
        assertTrue(cancelled(40f, 45f))
        assertFalse(cancelled(40f, 44f))
    }

    @Test fun nonPopupKeyCancelsAboveItsBoundsToo() {
        assertTrue(cancelled(40f, -5f, popup = false))
        assertFalse(cancelled(40f, -4f, popup = false))
    }
}
