package dev.omalauncher.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupertonicModelTest {
    @Test
    fun speaksEveryLauncherLanguageButChinese() {
        // "in" is how Android still names Indonesian.
        for (language in listOf("en", "de", "es", "fr", "in", "id", "it", "ja", "pt", "ru", "tr", "uk", "vi")) assertTrue(language, SupertonicModel.speaks(language))
        assertFalse(SupertonicModel.speaks("zh"))
        assertEquals("id", SupertonicModel.code("in"))
    }

    @Test
    fun aVoiceIsSavedBesideTheEnginesVoiceNames() {
        for (sid in 0 until SupertonicModel.VOICES) assertEquals(sid, SupertonicModel.sid(SupertonicModel.voiceName(sid)))
        // The phone engine's voices, none, and ones Supertonic doesn't have: its default voice reads.
        assertNull(SupertonicModel.sid("de-de-x-deb-local"))
        assertNull(SupertonicModel.sid(null))
        assertNull(SupertonicModel.sid("supertonic:10"))
        assertNull(SupertonicModel.sid("supertonic:x"))
    }

    @Test
    fun downloadIsTheSevenFiles() {
        assertEquals(145_295_768L, SupertonicModel.files.size)
    }

    @Test
    fun parakeetsDownloadIsUnchanged() {
        assertEquals(671_220_638L, ParakeetModel.files.size)
    }
}
