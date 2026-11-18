package dev.omalauncher.ui.voice

import dev.omalauncher.voice.VoiceSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ParakeetModelTest {
    @Test
    fun speaksTheLauncherLanguagesItKnows() {
        // The launcher's languages Parakeet knows, whatever the region: the phone's recognizer for the rest.
        for (tag in listOf("en-US", "en-DE", "de-DE", "de-AT", "es-ES", "pt-BR", "ru-RU", "uk-UA")) assertTrue(tag, ParakeetModel.speaks(Locale.forLanguageTag(tag)))
        for (tag in listOf("ja-JP", "zh-CN", "in-ID", "id-ID")) assertFalse(tag, ParakeetModel.speaks(Locale.forLanguageTag(tag)))
    }

    @Test
    fun downloadIsTheModelAndTheVad() {
        assertEquals(671_220_638L, ParakeetModel.SIZE)
    }

    @Test
    fun sendAfterDefaultIsOneOfTheChoices() {
        assertTrue(VoiceSession.DEFAULT_SEND_AFTER in VoiceSession.SEND_AFTER)
        assertTrue(0L in VoiceSession.SEND_AFTER)
    }
}
