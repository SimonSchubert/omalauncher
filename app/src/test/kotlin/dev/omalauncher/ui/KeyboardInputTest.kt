package dev.omalauncher.ui

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeyboardInputTest {
    @Test
    fun terminalUsesNormalTextSoGestureTypingRemainsAvailable() {
        val type = terminalInputType()

        assertEquals(type, type and InputType.TYPE_MASK_CLASS)
        assertEquals(0, type and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        assertFalse(type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertFalse(type and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0)
    }
}
