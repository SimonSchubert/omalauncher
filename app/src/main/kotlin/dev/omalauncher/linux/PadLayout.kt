package dev.omalauncher.linux

import android.view.KeyEvent
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The controller Linux programs see for each pad Android has: a wired Xbox 360 pad as Linux's xpad driver reports
 * one, so SDL's and gilrs' mappings know it by its ids. Android has already put every pad into that shape: A is the
 * bottom button, the right stick is AXIS_Z/AXIS_RZ, the d-pad a hat. Also what goes over the socket to
 * libgamepads.so (patches/proot/gamepads): the description first, then the kernel's input_event records.
 */
internal object PadLayout {
    const val EV_SYN = 0x00
    const val EV_KEY = 0x01
    const val EV_ABS = 0x03
    const val SYN_REPORT = 0

    const val BTN_A = 0x130
    const val BTN_B = 0x131
    const val BTN_X = 0x133
    const val BTN_Y = 0x134
    const val BTN_TL = 0x136
    const val BTN_TR = 0x137
    const val BTN_SELECT = 0x13a
    const val BTN_START = 0x13b
    const val BTN_MODE = 0x13c
    const val BTN_THUMBL = 0x13d
    const val BTN_THUMBR = 0x13e

    const val ABS_X = 0x00
    const val ABS_Y = 0x01
    const val ABS_Z = 0x02
    const val ABS_RX = 0x03
    const val ABS_RY = 0x04
    const val ABS_RZ = 0x05
    const val ABS_HAT0X = 0x10
    const val ABS_HAT0Y = 0x11

    /** xpad's ids for a wired Xbox 360 pad: USB, Microsoft, 0x028e. */
    private const val BUS_USB = 0x03
    private const val VENDOR = 0x045e
    private const val PRODUCT = 0x028e
    private const val VERSION = 0x0114

    /** Android's button key codes and the evdev buttons xpad reports for them; Android's X is xpad's BTN_X. */
    private val buttons =
        mapOf(
            KeyEvent.KEYCODE_BUTTON_A to BTN_A,
            KeyEvent.KEYCODE_BUTTON_B to BTN_B,
            KeyEvent.KEYCODE_BUTTON_X to BTN_X,
            KeyEvent.KEYCODE_BUTTON_Y to BTN_Y,
            KeyEvent.KEYCODE_BUTTON_L1 to BTN_TL,
            KeyEvent.KEYCODE_BUTTON_R1 to BTN_TR,
            KeyEvent.KEYCODE_BUTTON_SELECT to BTN_SELECT,
            KeyEvent.KEYCODE_BUTTON_START to BTN_START,
            KeyEvent.KEYCODE_BUTTON_MODE to BTN_MODE,
            KeyEvent.KEYCODE_BUTTON_THUMBL to BTN_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR to BTN_THUMBR,
        )

    /** All buttons the pad has, in code order. */
    val BUTTONS = buttons.values.sorted()

    /** The evdev button for an Android key code, null if it isn't one of the pad's buttons. */
    fun button(keyCode: Int): Int? = buttons[keyCode]

    /** A d-pad key as the hat axis it moves and the value it holds while down; null for other keys. */
    fun dpad(keyCode: Int): Pair<Int, Int>? =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> ABS_HAT0X to -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> ABS_HAT0X to 1
            KeyEvent.KEYCODE_DPAD_UP -> ABS_HAT0Y to -1
            KeyEvent.KEYCODE_DPAD_DOWN -> ABS_HAT0Y to 1
            else -> null
        }

    /** A digital trigger's key as its trigger axis, for pads whose triggers are only buttons; null for other keys. */
    fun triggerKey(keyCode: Int): Int? =
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> ABS_Z
            KeyEvent.KEYCODE_BUTTON_R2 -> ABS_RZ
            else -> null
        }

    /** The axes, in the order [axes] returns them. */
    val AXES = intArrayOf(ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ, ABS_HAT0X, ABS_HAT0Y)

    fun isTrigger(code: Int) = code == ABS_Z || code == ABS_RZ

    fun isHat(code: Int) = code == ABS_HAT0X || code == ABS_HAT0Y

    /**
     * The evdev values of [AXES] from Android's [axis] values. [rightZ]: the right stick is AXIS_Z/AXIS_RZ, as on
     * most pads, rather than AXIS_RX/AXIS_RY. Triggers come as AXIS_LTRIGGER/AXIS_RTRIGGER or AXIS_BRAKE/AXIS_GAS.
     */
    fun axes(
        axis: (Int) -> Float,
        rightZ: Boolean,
    ): IntArray =
        intArrayOf(
            stick(axis(MotionEvent.AXIS_X)),
            stick(axis(MotionEvent.AXIS_Y)),
            trigger(maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE))),
            stick(axis(if (rightZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX)),
            stick(axis(if (rightZ) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY)),
            trigger(maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS))),
            hat(axis(MotionEvent.AXIS_HAT_X)),
            hat(axis(MotionEvent.AXIS_HAT_Y)),
        )

    /** -1..1 to xpad's stick range; down and right are positive in both. */
    fun stick(v: Float) = (v * 32767f).roundToInt().coerceIn(-32768, 32767)

    fun trigger(v: Float) = (v * 255f).roundToInt().coerceIn(0, 255)

    fun hat(v: Float) =
        when {
            v < -0.5f -> -1
            v > 0.5f -> 1
            else -> 0
        }

    /** Size of the description, struct pad in gamepads.c. */
    const val DESCRIPTION_SIZE = 1888
    private const val MAGIC = 0x50414d4f // "OMAP"
    private const val DESCRIPTION_VERSION = 1

    /** Size of one input_event on arm64: a timeval, then type, code and value. */
    const val EVENT_SIZE = 24

    /**
     * What libgamepads.so answers evdev's ioctls from (struct pad in gamepads.c): ids, [name], which events and
     * buttons and axes there are, each axis' range and value, and which buttons are [held].
     */
    fun description(
        name: String,
        held: Set<Int>,
        abs: IntArray,
    ): ByteArray {
        val b = ByteBuffer.allocate(DESCRIPTION_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(MAGIC).putInt(DESCRIPTION_VERSION)
        b.putShort(BUS_USB.toShort()).putShort(VENDOR.toShort()).putShort(PRODUCT.toShort()).putShort(VERSION.toShort())
        // 127 bytes at most, cut on a character, so the C string ends inside its 128.
        var nameBytes = name.toByteArray()
        var end = name.length
        while (nameBytes.size > 127) nameBytes = name.substring(0, --end).toByteArray()
        b.put(nameBytes)
        b.position(16 + 128)
        b.put(bits(8, EV_SYN, EV_KEY, EV_ABS))
        b.put(bits(96, *BUTTONS.toIntArray()))
        b.put(bits(8, *AXES))
        for (code in 0 until 64) {
            val (min, max, fuzz, flat) =
                when {
                    code !in AXES -> intArrayOf(0, 0, 0, 0)
                    isTrigger(code) -> intArrayOf(0, 255, 0, 0)
                    isHat(code) -> intArrayOf(-1, 1, 0, 0)
                    else -> intArrayOf(-32768, 32767, 16, 128)
                }
            b.putInt(abs[code]).putInt(min).putInt(max).putInt(fuzz).putInt(flat).putInt(0)
        }
        b.put(bits(96, *held.toIntArray()))
        check(b.position() == DESCRIPTION_SIZE)
        return b.array()
    }

    private fun bits(
        bytes: Int,
        vararg set: Int,
    ) = ByteArray(bytes).also { a -> set.forEach { a[it / 8] = (a[it / 8].toInt() or (1 shl (it % 8))).toByte() } }

    /** Appends one input_event to [out], stamped [timeMicros] of CLOCK_REALTIME, evdev's default clock. */
    fun event(
        out: ByteBuffer,
        timeMicros: Long,
        type: Int,
        code: Int,
        value: Int,
    ) {
        out.putLong(timeMicros / 1_000_000).putLong(timeMicros % 1_000_000)
        out.putShort(type.toShort()).putShort(code.toShort()).putInt(value)
    }
}
