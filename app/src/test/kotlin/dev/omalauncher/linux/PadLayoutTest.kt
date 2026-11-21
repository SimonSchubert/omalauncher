package dev.omalauncher.linux

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PadLayoutTest {
    private fun ByteArray.le() = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

    private fun bit(
        bytes: ByteArray,
        offset: Int,
        n: Int,
    ) = bytes[offset + n / 8].toInt() and (1 shl (n % 8)) != 0

    /** gamepads.c reads the description as its struct pad: same size, and the fields where it has them. */
    @Test fun descriptionIsLaidOutAsTheGuestLibraryReadsIt() {
        val source = File("../patches/proot/gamepads/gamepads.c").readText()
        assertTrue(source.contains("sizeof(struct pad) == ${PadLayout.DESCRIPTION_SIZE}"))

        val abs = IntArray(64).also { it[PadLayout.ABS_X] = -1200 }
        val d = PadLayout.description("Xbox Wireless Controller", setOf(PadLayout.BTN_START), abs)
        assertEquals(PadLayout.DESCRIPTION_SIZE, d.size)
        val b = d.le()
        assertEquals(0x50414d4f, b.getInt(0))
        assertEquals(1, b.getInt(4))
        // struct input_id: USB, Microsoft, Xbox 360 pad, as xpad has it.
        assertEquals(listOf(0x03, 0x045e, 0x028e, 0x0114), (0 until 4).map { b.getShort(8 + 2 * it).toInt() and 0xffff })
        assertEquals("Xbox Wireless Controller", String(d, 16, 24))
        assertEquals(0, d[16 + 24].toInt())
        // Event types: EV_SYN, EV_KEY, EV_ABS.
        assertEquals(0x0b, d[144].toInt())
        for (button in PadLayout.BUTTONS) assertTrue(bit(d, 152, button))
        assertTrue(!bit(d, 152, 0x132)) // BTN_C, which xpad hasn't
        for (axis in PadLayout.AXES) assertTrue(bit(d, 248, axis))
        // input_absinfo: value, minimum, maximum, fuzz, flat, resolution.
        fun absinfo(code: Int) = IntArray(6) { b.getInt(256 + code * 24 + it * 4) }
        assertArrayEquals(intArrayOf(-1200, -32768, 32767, 16, 128, 0), absinfo(PadLayout.ABS_X))
        assertArrayEquals(intArrayOf(0, 0, 255, 0, 0, 0), absinfo(PadLayout.ABS_RZ))
        assertArrayEquals(intArrayOf(0, -1, 1, 0, 0, 0), absinfo(PadLayout.ABS_HAT0Y))
        assertTrue(bit(d, 1792, PadLayout.BTN_START))
        assertTrue(!bit(d, 1792, PadLayout.BTN_A))
    }

    @Test fun aLongNameEndsInsideItsField() {
        val d = PadLayout.description("Ż".repeat(100), emptySet(), IntArray(64))
        val name = d.copyOfRange(16, 16 + 128)
        val end = name.indexOf(0)
        assertTrue(end in 1..127)
        assertEquals("Ż".repeat(end / 2), String(name, 0, end))
    }

    @Test fun eventsAreTheKernelsInputEvent() {
        val out = ByteBuffer.allocate(PadLayout.EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        PadLayout.event(out, 1_700_000_000_123_456L, PadLayout.EV_ABS, PadLayout.ABS_Y, -32768)
        val b = out.array().le()
        assertEquals(1_700_000_000L, b.getLong(0))
        assertEquals(123_456L, b.getLong(8))
        assertEquals(PadLayout.EV_ABS, b.getShort(16).toInt())
        assertEquals(PadLayout.ABS_Y, b.getShort(18).toInt())
        assertEquals(-32768, b.getInt(20))
    }

    @Test fun buttonsAreXpadsWithAndroidsXAsBtnX() {
        assertEquals(PadLayout.BTN_A, PadLayout.button(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(PadLayout.BTN_X, PadLayout.button(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(PadLayout.BTN_Y, PadLayout.button(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(PadLayout.BTN_MODE, PadLayout.button(KeyEvent.KEYCODE_BUTTON_MODE))
        assertNull(PadLayout.button(KeyEvent.KEYCODE_BACK))
        assertEquals(PadLayout.ABS_HAT0Y to -1, PadLayout.dpad(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(PadLayout.ABS_RZ, PadLayout.triggerKey(KeyEvent.KEYCODE_BUTTON_R2))
    }

    @Test fun axesScaleToXpadsRanges() {
        val values =
            mapOf(
                MotionEvent.AXIS_X to -1f,
                MotionEvent.AXIS_Y to 0.5f,
                MotionEvent.AXIS_BRAKE to 1f,
                MotionEvent.AXIS_LTRIGGER to 0.2f,
                MotionEvent.AXIS_Z to 1f,
                MotionEvent.AXIS_RX to -1f,
                MotionEvent.AXIS_GAS to 0.5f,
                MotionEvent.AXIS_HAT_X to -1f,
                MotionEvent.AXIS_HAT_Y to 0.3f,
            )
        val axis = { a: Int -> values[a] ?: 0f }
        assertArrayEquals(intArrayOf(-32767, 16384, 255, 32767, 0, 128, -1, 0), PadLayout.axes(axis, rightZ = true))
        // A pad whose right stick is AXIS_RX/AXIS_RY.
        assertEquals(-32767, PadLayout.axes(axis, rightZ = false)[3])
    }
}
