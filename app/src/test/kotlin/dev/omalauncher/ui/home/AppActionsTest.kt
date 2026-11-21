package dev.omalauncher.ui.home

import dev.omalauncher.widgets.WidgetSpec
import dev.omalauncher.widgets.editByVoicePrompt
import dev.omalauncher.widgets.lang.OwlKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppActionsTest {
    private val spec = WidgetSpec(File("/data/widgets/notes.owl"), "notes", 0, 0, null, kind = OwlKind.App)
    private val hit = AppHit.Owl(spec)

    private fun handlers(
        edited: MutableList<String>,
        voiced: MutableList<String>,
    ) = AppHandlers(
        launch = {},
        pin = {},
        info = {},
        uninstall = {},
        remove = {},
        shortcuts = { error("no shortcuts") },
        edit = { edited += it.name },
        voice = { voiced += it.name },
        share = {},
        removeOwl = {},
    )

    @Test fun voiceBesideEditOnlyWhenVoiceCanTalk() {
        assertEquals(listOf("pin", "edit", "voice", "share", "remove"), hit.actionWords(voice = true))
        assertEquals(listOf("pin", "edit", "share", "remove"), hit.actionWords(voice = false))
    }

    @Test fun voiceOpensVoiceOnTheFile() {
        val edited = mutableListOf<String>()
        val voiced = mutableListOf<String>()
        hit.act("voice", handlers(edited, voiced))
        hit.act("edit", handlers(edited, voiced))
        assertEquals(listOf("notes"), voiced)
        assertEquals(listOf("notes"), edited)
    }

    @Test fun voicePromptNamesTheWholePath() {
        // A Voice agent may run in home rather than the widgets folder.
        val prompt = editByVoicePrompt(spec)
        assertTrue(prompt, prompt.contains("app \"notes\" in /root/.config/omalauncher/widgets/notes.owl"))
        assertTrue(prompt, prompt.contains("owl-check /root/.config/omalauncher/widgets/notes.owl"))
        assertTrue(prompt, prompt.endsWith("What I want changed:"))
    }
}
