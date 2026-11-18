package dev.omalauncher.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginNoticeTest {
    @Test
    fun hermesWithoutAProviderOffersToLogIn() {
        val said = "⚠️ Hermes is not connected to any AI provider yet. Run `hermes model` to pick one.\n\nThen ask again."
        val notice = VoiceSession.failure("admitted_turn_failed", said)
        assertEquals("⚠️ Hermes is not connected to any AI provider yet.", notice)
        assertTrue(VoiceSession.needsLogin(notice))
    }

    @Test
    fun anErrorThatSaysSoItselfStays() {
        assertEquals("Not logged in · Please run /login", VoiceSession.failure("Not logged in · Please run /login", "anything"))
    }

    @Test
    fun anOrdinaryReplyDoesNotReplaceTheError() {
        assertEquals("rate limited", VoiceSession.failure("rate limited", "I started on the widget."))
        assertEquals("admitted_turn_failed", VoiceSession.failure("admitted_turn_failed", ""))
        assertFalse(VoiceSession.needsLogin("rate limited"))
    }
}
