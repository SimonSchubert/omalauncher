package dev.omalauncher

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.ui.VoiceScreen

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
        // Brought back in a new process (after an update, or after Android ended it), Voice comes up before home,
        // which starts the rest; without that the agents are never looked for, and it says none is installed.
        runtime.start()
        opens = 1
        runtime.voice.openedAnew()
        setInstantContent {
            VoiceScreen(runtime, opens = opens, shown = shown, onExit = ::backInstantly)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        opens++
        runtime.voice.openedAnew()
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
}
