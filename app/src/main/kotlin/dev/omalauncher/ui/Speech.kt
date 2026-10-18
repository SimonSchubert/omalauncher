package dev.omalauncher.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Android speech recognition for the prompt bar. The guest has no microphone,
 * so speech becomes text here and only text reaches Linux. Prefers the
 * on-device recognizer (offline, fast) and falls back to the regular one when
 * it has no model for the language. Main thread only, like SpeechRecognizer.
 */
class Speech(
    private val context: Context,
) {
    var listening by mutableStateOf(false)
        private set

    /** What's been recognized so far: finished segments plus the current partial result. */
    var text by mutableStateOf("")
        private set

    /** Input loudness, 0..1. */
    var level by mutableFloatStateOf(0f)
        private set

    /** Why recognition failed; the UI clears it once shown. */
    var error by mutableStateOf<String?>(null)

    private var recognizer: SpeechRecognizer? = null
    private var onDevice = true
    private var finished = ""
    private var onDone: ((String) -> Unit)? = null

    /** While held, pauses don't end recognition: a new segment starts until [stop]. */
    private var held = false

    private val onDeviceAvailable get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    val available get() = onDeviceAvailable || SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Starts listening; [onDone] gets the full text (maybe empty) once recognition ends.
     * With [hold], it keeps listening across pauses until [stop].
     */
    fun start(
        hold: Boolean,
        onDone: (String) -> Unit,
    ) {
        cancel()
        error = null
        if (!available) {
            error = "no speech recognizer on this phone"
            return
        }
        held = hold
        this.onDone = onDone
        finished = ""
        text = ""
        listening = true
        listen()
    }

    /** Ends recognition now; the text so far goes to onDone. */
    fun stop() {
        held = false
        recognizer?.stopListening() ?: finish()
    }

    /** Ends recognition without calling onDone. */
    fun cancel() {
        held = false
        onDone = null
        recognizer?.destroy()
        recognizer = null
        listening = false
        level = 0f
    }

    /**
     * The language to recognize: the phone's, or the closest one the recognizer
     * has a model for. Recognizers only know common language-region pairs, so a
     * phone set to English (Germany) needs en-US or en-GB instead. Null until checked.
     */
    private var language: String? = null

    private fun listen() {
        recognizer?.destroy()
        val r =
            if (onDevice && onDeviceAvailable) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        recognizer = r
        r.setRecognitionListener(Listener(r))
        if (language != null || Build.VERSION.SDK_INT < 33) return r.startListening(intent())
        r.checkRecognitionSupport(
            intent(),
            context.mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    if (r !== recognizer) return
                    language = closestLanguage(support)
                    Log.i(TAG, "recognizing ${language ?: "the default language"}")
                    r.startListening(intent())
                }

                override fun onError(error: Int) {
                    if (r === recognizer) r.startListening(intent())
                }
            },
        )
    }

    private fun intent() =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .apply { language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) } }

    /**
     * The phone's language-region if supported, else the same language in another
     * region: installed models first, then the language's home region (de-DE),
     * then en-US style defaults, then any.
     */
    @androidx.annotation.RequiresApi(33)
    private fun closestLanguage(support: RecognitionSupport): String? {
        val locale = Locale.getDefault()
        val wanted = Locale(locale.language, locale.country).toLanguageTag()
        val pools =
            if (onDevice) {
                listOf(support.installedOnDeviceLanguages, support.supportedOnDeviceLanguages, support.onlineLanguages)
            } else {
                listOf(support.onlineLanguages, support.installedOnDeviceLanguages, support.supportedOnDeviceLanguages)
            }
        pools.flatten().firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { return it }
        val home = "${locale.language}-${locale.language}"
        for (pool in pools) {
            val same = pool.filter { it.substringBefore('-').equals(locale.language, ignoreCase = true) }
            same
                .minByOrNull {
                    when {
                        it.equals(home, ignoreCase = true) -> 0
                        it.endsWith("-US", ignoreCase = true) -> 1
                        else -> 2
                    }
                }?.let { return it }
        }
        return null
    }

    private fun finish() {
        recognizer?.destroy()
        recognizer = null
        listening = false
        level = 0f
        val done = onDone
        onDone = null
        done?.invoke(text.trim())
    }

    private fun join(
        a: String,
        b: String,
    ) = listOf(a, b).filter { it.isNotBlank() }.joinToString(" ") { it.trim() }

    private fun Bundle.best() = getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    /** Listens to one recognizer; callbacks from one that's been replaced are ignored. */
    private inner class Listener(
        private val r: SpeechRecognizer,
    ) : RecognitionListener {
        private val current get() = r === recognizer

        override fun onPartialResults(partial: Bundle) {
            if (current) text = join(finished, partial.best())
        }

        override fun onResults(results: Bundle) {
            if (!current) return
            finished = join(finished, results.best())
            text = finished
            if (held) listen() else finish()
        }

        override fun onError(code: Int) {
            if (!current) return
            when (code) {
                // Silence or nothing understood: fine while the button is held.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (held) listen() else finish()
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    if (onDevice) {
                        Log.i(TAG, "no on-device model for this language, using the regular recognizer")
                        onDevice = false
                        // The regular recognizer may know other languages than the on-device one.
                        language = null
                        listen()
                    } else {
                        fail("speech isn't available in this language")
                    }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("microphone permission needed")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> fail("speech recognition needs a network connection")
                else -> fail("speech recognition failed ($code)")
            }
        }

        private fun fail(message: String) {
            Log.w(TAG, message)
            // Keep what was already recognized; the error is only worth showing when there's nothing.
            if (text.isBlank()) error = message
            finish()
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (current) level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onEvent(
            eventType: Int,
            params: Bundle?,
        ) {}
    }

    companion object {
        private const val TAG = "speech"
    }
}
