package dev.omalauncher.ui

import android.text.InputType
import dev.omalauncher.theme.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalInputTypeTest {
    @Test fun suggestionsAreOnByDefault() {
        assertTrue(Style()[Style.TERMINAL_SUGGESTIONS])
    }

    @Test fun rawInputIsAPasswordFieldWithoutSuggestions() {
        val expected = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        assertEquals(expected, terminalInputType(false))
    }

    @Test fun plainInputIsJustText() {
        assertEquals(InputType.TYPE_CLASS_TEXT, terminalInputType(true))
    }
}
