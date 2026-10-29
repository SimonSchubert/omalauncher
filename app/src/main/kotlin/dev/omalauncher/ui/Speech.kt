package dev.omalauncher.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.R
import java.util.Locale

/**
 * Android speech recognition for the prompt bar and widgets' dictation (see
 * [dev.omalauncher.widgets.runtime.Dictation]). The guest has no microphone,
 * so speech becomes text here and only text reaches Linux. Prefers the
 * on-device recognizer (offline, fast, silent) and falls back to the regular
 * one when there's none or it has no model for the language. Not the regular
 * one first: Google's Speech Services beeps, and pauses what's playing, each
 * time it starts and each time it stops hearing nothing, every few seconds
 * while held. Main thread only, like SpeechRecognizer.
 * The prompt bar and widgets each have one; one starting cancels the other's.
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
    private var onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    private var finished = ""

    /** The current segment's latest partial result, kept should the segment end without a final one. */
    private var partial = ""
    private var onDone: ((String) -> Unit)? = null

    /** While held, pauses don't end recognition: a new segment starts until [stop]. */
    private var held = false

    /** Whether this recognition has started over once already, after losing the recognizer; see [Listener.onError]. */
    private var retried = false

    private val main = Handler(Looper.getMainLooper())

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
        active?.takeIf { it !== this }?.cancel()
        active = this
        error = null
        if (!available) {
            error = context.getString(R.string.prompt_speech_no_recognizer)
            return
        }
        held = hold
        this.onDone = onDone
        finished = ""
        partial = ""
        text = ""
        listening = true
        retried = false
        listen()
    }

    /** Ends recognition now; the text so far goes to onDone. */
    fun stop() {
        held = false
        // Not listening yet, still asking for the languages: there's nothing to wait for.
        val r = recognizer?.takeIf { pending == null } ?: return finish()
        r.stopListening()
    }

    /** Ends recognition without calling onDone, and forgets what it heard. */
    fun cancel() {
        held = false
        onDone = null
        finished = ""
        partial = ""
        text = ""
        recognizer?.destroy()
        recognizer = null
        pending = null
        listening = false
        level = 0f
    }

    /**
     * The language to recognize: the phone's, or the closest one the recognizer
     * has a model for. Recognizers only know common language-region pairs, so a
     * phone set to English (Germany) needs en-US or en-GB instead. Null for the
     * recognizer's default; asked once for each kind of recognizer, see [languages].
     */
    private var language: String?
        get() = languages[onDevice]
        set(value) {
            languages[onDevice] = value
        }

    /** The recognizer that's being asked for its languages before it listens. */
    private var pending: SpeechRecognizer? = null

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
        Log.i(TAG, "listening with the ${if (onDevice && onDeviceAvailable) "on-device" else "regular"} recognizer")
        if (onDevice in languages || Build.VERSION.SDK_INT < 33) return begin(r)
        pending = r
        // A Pixel took 16 seconds to answer, with an error, the first time: the words said meanwhile were lost.
        main.postDelayed({
            if (pending !== r) return@postDelayed
            pending = null
            language = mainRegion()
            Log.i(TAG, "no languages after ${SUPPORT_WAIT_MS}ms, recognizing $language")
            begin(r)
        }, SUPPORT_WAIT_MS)
        guarded {
            r.checkRecognitionSupport(
                intent(),
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (pending !== r) return
                        pending = null
                        language = closestLanguage(support)
                        Log.i(TAG, "recognizing ${language ?: "the default language"}")
                        begin(r)
                    }

                    // Google's Speech Services can't say which languages it knows (error 14), and asked for the phone's
                    // en-DE it fails, with a beep: the language's main region instead.
                    override fun onError(error: Int) {
                        if (pending !== r) return
                        pending = null
                        language = mainRegion()
                        Log.i(TAG, "no languages ($error), recognizing $language")
                        begin(r)
                    }
                },
            )
        }
    }

    /**
     * The next segment while held, on the same recognizer: destroying it takes the connection to the service down,
     * and a new one made right after loses it too (ERROR_SERVER_DISCONNECTED), on a Pixel at every pause.
     */
    private fun next() = recognizer?.let(::begin) ?: listen()

    private fun begin(r: SpeechRecognizer) = guarded { r.startListening(intent()) }

    /**
     * Runs [call] on the recognizer. The phone's default one can be another app's that won't let
     * others bind to it (ChatGPT's voice assistant): that throws instead of reporting an error.
     */
    private fun guarded(call: () -> Unit) {
        try {
            call()
        } catch (e: SecurityException) {
            Log.w(TAG, "the speech recognizer refused", e)
            if (onDevice && SpeechRecognizer.isRecognitionAvailable(context)) {
                onDevice = false
                return listen()
            }
            if (text.isBlank()) error = context.getString(R.string.prompt_speech_refused)
            finish()
        }
    }

    private fun intent() =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Google's recognizers otherwise beep as they start and as they stop hearing anything, and take the audio
            // focus to, pausing what plays: read by both the on-device one and Speech Services.
            .putExtra(MUTE_BEEPS, true)
            .apply { language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) } }
            .apply {
                // Held, one session that hands over each phrase as it ends, rather than a new one after every pause,
                // which loses what's said in between. One that doesn't do segments ignores it, and ends at a pause as
                // before, for [next]. An Int: Google's recognizers ignore a Long, and with it the segments.
                if (held && Build.VERSION.SDK_INT >= 33) {
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SESSION_MS)
                }
            }

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

    /** The phone's language in its main region: en-US, de-DE, fr-FR. */
    private fun mainRegion(): String {
        val l = Locale.getDefault().language
        return if (l == "en") "en-US" else "$l-${l.uppercase(Locale.ROOT)}"
    }

    private fun finish() {
        recognizer?.destroy()
        recognizer = null
        pending = null
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
            if (!current) return
            this@Speech.partial = partial.best()
            text = join(finished, this@Speech.partial)
        }

        override fun onResults(results: Bundle) {
            if (!current) return
            finished = join(finished, results.best().ifEmpty { partial })
            partial = ""
            text = finished
            retried = false
            if (held) next() else finish()
        }

        override fun onError(code: Int) {
            if (!current) return
            when (code) {
                // The other Speech's recognizer, just destroyed as this one started, can take the connection to the
                // service down with it: once it's gone, this one starts over on a new one.
                ERROR_SERVER_DISCONNECTED ->
                    if (!retried) {
                        retried = true
                        main.postDelayed({ if (r === recognizer) listen() }, RETRY_MS)
                    } else {
                        fail(code, R.string.prompt_speech_failed)
                    }
                // Silence or nothing understood: fine while the button is held.
                // A segment can end so after partial results, the Pixel's recognizer for one: those words stay.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    finished = join(finished, partial)
                    partial = ""
                    text = finished
                    if (held) next() else finish()
                }
                // The phone's language-region (en-DE) may be unknown to a recognizer that can't say which it knows: the
                // language's main region next, kept for later, then the other recognizer, whose languages are its own.
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    val main = mainRegion()
                    when {
                        language != main -> {
                            Log.i(TAG, "${language ?: "the default language"} isn't known, recognizing $main")
                            language = main
                            next()
                        }
                        onDevice && SpeechRecognizer.isRecognitionAvailable(context) -> {
                            Log.i(TAG, "no on-device model for $main, using the regular recognizer")
                            onDevice = false
                            listen()
                        }
                        else -> fail(code, R.string.prompt_speech_language)
                    }
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail(code, R.string.prompt_speech_mic_permission)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> fail(code, R.string.prompt_speech_network)
                else -> fail(code, R.string.prompt_speech_failed)
            }
        }

        /** [message] may show the recognizer's error [code]. */
        private fun fail(
            code: Int,
            @StringRes message: Int,
        ) {
            Log.w(TAG, "speech recognition failed ($code)")
            // Keep what was already recognized; the error is only worth showing when there's nothing.
            if (text.isBlank()) error = context.getString(message, code)
            finish()
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (current) level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (!current) return
            finished = join(finished, segmentResults.best().ifEmpty { partial })
            partial = ""
            text = finished
            retried = false
        }

        override fun onEndOfSegmentedSession() {
            if (!current) return
            finished = join(finished, partial)
            partial = ""
            text = finished
            if (held) next() else finish()
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

        /** SpeechRecognizer.ERROR_SERVER_DISCONNECTED, from API 31. */
        private const val ERROR_SERVER_DISCONNECTED = 11

        private const val RETRY_MS = 300L

        /**
         * How long one held session asks to last; [stop] ends it sooner, and [next] starts another after. Google's
         * recognizers take at most 5 minutes, and ignore more, segments and all.
         */
        private const val SESSION_MS = 5 * 60 * 1000

        /** Google's own extra, in both its recognizers, for no start and end beeps. */
        private const val MUTE_BEEPS = "com.google.recognition.extra.MUTE_AUDIO_BEEPS"

        /** How long a recognizer gets to say which languages it knows before it listens for the default one. */
        private const val SUPPORT_WAIT_MS = 2000L

        /** [language] for each kind of recognizer, on-device or not, once asked: the same for every Speech. */
        private val languages = HashMap<Boolean, String?>()

        /** The one started last: the phone has one microphone, and a second recognizer would get nothing. */
        private var active: Speech? = null
    }
}
