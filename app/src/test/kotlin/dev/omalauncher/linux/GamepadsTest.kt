package dev.omalauncher.linux

import android.view.InputDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadsTest {
    private val keyboard = InputDevice.SOURCE_KEYBOARD
    private val gamepad = InputDevice.SOURCE_GAMEPAD
    private val joystick = InputDevice.SOURCE_JOYSTICK
    private val dpad = InputDevice.SOURCE_DPAD

    @Test fun anXboxControllerIsAPad() {
        assertTrue(Gamepads.isPad(false, keyboard or gamepad or joystick, InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC) { true })
    }

    /** As Android described a Razer Ornata V3's keyboard interface, whose arrows became a pad's d-pad. */
    @Test fun aKeyboardThatClaimsAJoystickIsNot() {
        assertFalse(Gamepads.isPad(false, keyboard or dpad or joystick, InputDevice.KEYBOARD_TYPE_ALPHABETIC) { false })
    }

    @Test fun aGamepadNeedsAnAButtonAndNoVirtualDeviceIsOne() {
        assertFalse(Gamepads.isPad(false, keyboard or gamepad, InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC) { false })
        assertTrue(Gamepads.isPad(false, keyboard or gamepad, InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC) { true })
        assertFalse(Gamepads.isPad(true, keyboard or gamepad or joystick, InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC) { true })
    }
}
