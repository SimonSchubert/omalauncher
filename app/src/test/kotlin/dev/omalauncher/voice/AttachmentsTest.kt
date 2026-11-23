package dev.omalauncher.voice

import dev.omalauncher.voice.VoiceSession.Attachment
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AttachmentsTest {
    @Test
    fun nothingAttachedIsJustWhatWasSaid() {
        assertEquals("fix it", VoiceSession.withAttachments("fix it", emptyList()))
    }

    @Test
    fun picturesAndTextComeBeforeWhatWasSaid() {
        val parts = listOf(Attachment.Picture(File("/x/a.png"), "/root/.cache/omalauncher/voice-images/a.png"), Attachment.Pasted("error: boom"))
        assertEquals(
            "[The user attached a picture: /root/.cache/omalauncher/voice-images/a.png — look at it.]\n\n" +
                "[The user pasted this:]\n```\nerror: boom\n```\n\n" +
                "what's wrong here?",
            VoiceSession.withAttachments("what's wrong here?", parts),
        )
    }

    @Test
    fun pastedCodeBlocksDontEndTheFence() {
        val text = "see\n```kotlin\nval x = 1\n```"
        assertEquals("[The user pasted this:]\n````\n$text\n````", VoiceSession.withAttachments("", listOf(Attachment.Pasted(text))))
    }
}
