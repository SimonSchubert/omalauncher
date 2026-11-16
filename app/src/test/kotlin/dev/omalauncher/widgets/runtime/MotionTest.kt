package dev.omalauncher.widgets.runtime

import android.view.Surface
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.TRUE
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTest {
    private fun tilt(
        x: Double,
        y: Double,
        z: Double,
        rotation: Int = Surface.ROTATION_0,
    ) = (Motion.tilt(x, y, z, rotation) as Obj).fields.mapValues { (k, v) -> if (k == "available") v else (v as Num).v }

    @Test fun flatFaceUpIsLevel() {
        val m = tilt(0.0, 0.0, 9.81)
        assertEquals(TRUE, m["available"])
        assertEquals(mapOf("x" to 0.0, "y" to 0.0, "z" to 1.0, "roll" to 0.0, "pitch" to 0.0), m - "available")
    }

    @Test fun uprightLeansTheBottomEdgeDown() {
        // Held up in portrait: "up" is the phone's top.
        val m = tilt(0.0, 9.81, 0.0)
        assertEquals(1.0, m["y"])
        assertEquals(90.0, m["pitch"])
        assertEquals(0.0, m["x"])
    }

    @Test fun rightEdgeDownRollsRight() {
        // Flat, then the right edge lowered by 30°: "up" leans toward the left edge.
        val m = tilt(-Math.sin(Math.toRadians(30.0)), 0.0, Math.cos(Math.toRadians(30.0)))
        assertEquals(0.5, m["x"])
        assertEquals(30.0, m["roll"])
        assertEquals(0.0, m["y"])
    }

    @Test fun followsTheScreenAsItTurns() {
        // The phone's left edge down, shown in landscape with its top to the left (rotation 90): the screen's bottom.
        assertEquals(1.0, tilt(1.0, 0.0, 0.0, Surface.ROTATION_90)["y"])
        assertEquals(1.0, tilt(-1.0, 0.0, 0.0, Surface.ROTATION_270)["y"])
        // Upside down, the phone's top is the screen's bottom edge, now up: y is -1.
        assertEquals(-1.0, tilt(0.0, 1.0, 0.0, Surface.ROTATION_180)["y"])
    }
}
