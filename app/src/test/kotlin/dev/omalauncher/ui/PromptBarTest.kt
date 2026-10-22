package dev.omalauncher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBarTest {
    @Test
    fun codexResumeCommandAcceptsSlashAndPlainForms() {
        assertTrue(isCodexResumeCommand("codex", "resume"))
        assertTrue(isCodexResumeCommand("codex", "  /RESUME  "))
    }

    @Test
    fun resumeCommandDoesNotHijackOtherAgentsOrSearches() {
        assertFalse(isCodexResumeCommand("claude", "resume"))
        assertFalse(isCodexResumeCommand("codex", "resume me"))
        assertFalse(isCodexResumeCommand("codex", ""))
    }
}
