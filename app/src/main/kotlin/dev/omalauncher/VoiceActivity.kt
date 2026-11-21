package dev.omalauncher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.ui.settings.SettingsPage
import dev.omalauncher.ui.voice.VoiceScreen
import dev.omalauncher.voice.VoiceSession
import dev.omalauncher.widgets.editByVoicePrompt

/** The voice screen, a spoken conversation with a coding agent: its own task and Recents card, opened from the AI bar's mic. */
class VoiceActivity : InstantActivity() {
    /** Each time it's opened, not when it comes back from Recents: opening it is asking to talk. */
    private var opens by mutableIntStateOf(0)

    /** In sight. Leaving only sends the task to the back, so this, not the screen's composition, says it's closed. */
    private var shown by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The agents run in Linux; without it there's no one to talk to.
        if (!runtime.bootstrap.wanted) {
            finishAndRemoveTask()
            return
        }
        // Stopped in settings: it's started there.
        if (runtime.linuxStopped) {
            runtime.openSettings(SettingsPage.Linux)
            finishAndRemoveTask()
            return
        }
        // Brought back in a new process (after an update, or after Android ended it), Voice comes up before home,
        // which starts the rest; without that the agents are never looked for, and it says none is installed.
        runtime.start()
        opens = 1
        // Recreated, or brought back from Recents with the intent it was first opened with: that edit was asked for then.
        val again = savedInstanceState != null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        runtime.voice.openedAnew(if (again) null else about(intent))
        setInstantContent {
            VoiceScreen(runtime, opens = opens, shown = shown, onExit = ::backInstantly)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        opens++
        runtime.voice.openedAnew(about(intent))
    }

    /** The owl file [intent] asks to change, as Voice tells the agent; null for none, or one that's gone. */
    private fun about(intent: Intent): VoiceSession.About? {
        val name = intent.getStringExtra(EXTRA_EDIT) ?: return null
        val spec = runtime.widgets.specs.firstOrNull { it.file.name == name } ?: return null
        return VoiceSession.About(getString(R.string.voice_note_edit, spec.name), editByVoicePrompt(spec))
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing || shown) return
        shown = true
        runtime.voice.opened()
    }

    override fun onStop() {
        if (shown) {
            shown = false
            runtime.voice.closed()
        }
        super.onStop()
    }

    companion object {
        /** The file name of an owl file to change: Voice tells the agent about it with the first thing said. */
        private const val EXTRA_EDIT = "edit"

        /** Opens Voice to change the owl file [name]. */
        fun edit(
            context: Context,
            name: String,
        ): Intent = Intent(context, VoiceActivity::class.java).putExtra(EXTRA_EDIT, name)
    }
}
