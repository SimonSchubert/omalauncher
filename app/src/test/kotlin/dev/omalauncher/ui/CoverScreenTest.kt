package dev.omalauncher.ui

import android.view.Surface
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isCoverScreen] tells a foldable's cover screen from a phone held sideways. The Flip 8 numbers are from a
 * screenshot of its cover screen (948x1048 px) with the launcher enabled through Samsung's MultiStar: the
 * launcher's window is 948x835 px, the rest the camera strip. Its density isn't in the screenshot; both
 * buckets a 342 ppi panel can get (2.0 and 2.25) must agree.
 */
class CoverScreenTest {
    private val flip8 = IntSize(948, 835)

    @Test fun aFlip8CoverScreenIsOne() {
        assertTrue(isCoverScreen(flip8, Surface.ROTATION_0, (835 / 2.0f).dp, enabled = true))
        assertTrue(isCoverScreen(flip8, Surface.ROTATION_0, (835 / 2.25f).dp, enabled = true))
        // Held upside down: Android reports 180, the window is the same.
        assertTrue(isCoverScreen(flip8, Surface.ROTATION_180, (835 / 2.0f).dp, enabled = true))
    }

    @Test fun aPhoneHeldSidewaysIsNot() {
        // A 20:9 phone in landscape, 1080x2400 at 2.625: the display is turned, and homeSide's rail is right for it.
        val sideways = IntSize(2400, 1080)
        assertFalse(isCoverScreen(sideways, Surface.ROTATION_90, (1080 / 2.625f).dp, enabled = true))
        assertFalse(isCoverScreen(sideways, Surface.ROTATION_270, (1080 / 2.625f).dp, enabled = true))
    }

    @Test fun aTabletInItsNaturalLandscapeIsNot() {
        // 2560x1600 at 2.0: 800 dp on the short side. homeSide keeps the bar below the grid there already.
        assertFalse(isCoverScreen(IntSize(2560, 1600), Surface.ROTATION_0, 800.dp, enabled = true))
    }

    @Test fun aPortraitWindowIsNot() {
        // The inner screen, and any phone upright: nothing to decide.
        assertFalse(isCoverScreen(IntSize(1080, 2520), Surface.ROTATION_0, 540.dp, enabled = true))
    }

    @Test fun homeCoverScreenFalseTurnsItAsBefore() {
        assertFalse(isCoverScreen(flip8, Surface.ROTATION_0, (835 / 2.0f).dp, enabled = false))
    }
}
