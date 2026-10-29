package dev.omalauncher.widgets.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.R
import dev.omalauncher.ui.Speech
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion

/**
 * Dictation for widgets: `source said = android.speech` and `speech("start")`. One widget listens at a time, the one
 * that started last, and each sees only its own: the others read it as not listening, with nothing heard. It listens
 * across pauses until stopped, and stops as its widget stops reading the source: its screen closed, home went away.
 * The prompt bar has a [Speech] of its own; one starting cancels the other. Main thread only.
 */
class Dictation(
    private val context: Context,
) {
    private val speech = Speech(context)

    /**
     * The widget listening, or that listened last; who [heard] and the source's text belong to. State, like the rest:
     * the others' sources read nothing else of it, and must see it become theirs.
     */
    private var owner by mutableStateOf<Any?>(null)

    /** The whole text of [owner]'s last dictation once it ended, "" from its start: `on said.final` runs once for each. */
    private var final by mutableStateOf("")

    /** Asks the activity in front for the microphone, then says whether it was granted; set as it resumes. */
    var askMic: ((granted: (Boolean) -> Unit) -> Unit)? = null

    /** What [owner]'s last dictation heard, once it ended; what lets its `on said.final` type it, see WidgetInstance. */
    fun heard(owner: Any) = final.takeIf { this.owner === owner && it.isNotEmpty() }

    /** android.speech for [owner]; when it stops reading, its dictation stops too. */
    fun updates(owner: Any): Flow<Value> {
        // Asked once a read: it's a package query, and only changes as a recognizer is installed.
        val available = speech.available
        return snapshotFlow { value(owner, available) }.onCompletion {
            if (this@Dictation.owner !== owner) return@onCompletion
            speech.cancel()
            // Not kept for a widget that may be gone for good: one that reads again starts with nothing heard.
            this@Dictation.owner = null
            final = ""
        }
    }

    fun value(
        owner: Any,
        available: Boolean = speech.available,
    ): Value {
        val mine = this.owner === owner
        return Obj(
            mapOf(
                "available" to bool(available),
                "listening" to bool(mine && speech.listening),
                "text" to Str(if (mine) speech.text else ""),
                "final" to Str(if (mine) final else ""),
                "error" to (speech.error?.takeIf { mine }?.let(::Str) ?: Null),
            ),
        )
    }

    /** speech("start"), "stop", "toggle" or "cancel" from [owner]'s tap. */
    fun act(
        owner: Any,
        word: String,
    ) {
        val mine = this.owner === owner && speech.listening
        when (word) {
            "start" -> start(owner)
            "toggle" -> if (mine) speech.stop() else start(owner)
            "stop" -> if (mine) speech.stop()
            "cancel" -> if (mine) speech.cancel()
        }
    }

    private fun start(owner: Any) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val ask = askMic
            if (ask != null) {
                ask { granted -> if (granted) listen(owner) else denied(owner) }
            } else {
                denied(owner)
            }
            return
        }
        listen(owner)
    }

    private fun listen(owner: Any) {
        this.owner = owner
        final = ""
        speech.start(hold = true) { heard -> if (this.owner === owner) final = heard }
    }

    private fun denied(owner: Any) {
        speech.cancel()
        this.owner = owner
        final = ""
        speech.error = context.getString(R.string.prompt_speech_mic_permission)
    }
}
