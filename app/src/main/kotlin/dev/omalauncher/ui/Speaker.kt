package dev.omalauncher.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.AppLanguage
import java.util.Locale

/**
 * Android's own text-to-speech: the voice screen's replies, in the language, voice and speed picked there ([use]), by
 * default the launcher's language. Not the engine's own default, which follows the phone's: with the launcher in German
 * on an English phone, the user talks German, the agent answers in German, and an English voice would read it.
 * Sentences queue up and play one after another; [stop] stops what's playing and drops the rest. Started
 * on first use. Main thread.
 */
class Speaker(
    private val context: Context,
) {
    private var tts: TextToSpeech? = null

    /** Started: it can say which languages and voices it has. */
    var ready by mutableStateOf(false)
        private set

    /** A language tag ("de", "en-GB"), null for the launcher's; see [use]. */
    private var language: String? = null

    /** A voice's name, used while it's one for [language]; null for the engine's choice. */
    private var voice: String? = null
    private var rate = 1f

    /** Whether it's saying something, or has something queued. */
    var speaking by mutableStateOf(false)
        private set

    /**
     * Why replies aren't heard, for the screen to say: no text-to-speech engine on the phone, or the engine
     * failing to read (often no voice installed for the language). Null once it reads again.
     */
    var problem by mutableStateOf<String?>(null)
        private set

    /** Starts the engine ahead of the first reply: sooner speech, and [problem] known before anything's said. */
    fun warmUp() = start()

    /** What was asked before the engine was ready, said once it is. */
    private val waiting = ArrayDeque<String>()
    private var next = 0

    /** Utterances handed to the engine and not finished yet. */
    private var queued = 0
    private val main = Handler(Looper.getMainLooper())

    fun speak(text: String) {
        if (text.isBlank()) return
        speaking = true
        if (!ready) {
            waiting += text
            start()
            return
        }
        say(text)
    }

    fun stop() {
        waiting.clear()
        queued = 0
        speaking = false
        tts?.stop()
    }

    private fun start() {
        if (tts != null) return
        // With no engine at all, Android reports the failure from inside the constructor, before it returns.
        var failed = false
        val engine =
            TextToSpeech(context.applicationContext) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "text-to-speech failed to start: $status")
                    failed = true
                    tts = null
                    problem = NO_ENGINE
                    waiting.clear()
                    speaking = false
                    return@TextToSpeech
                }
                tts?.setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                tts?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}

                        override fun onDone(id: String?) =
                            main.post {
                                problem = null
                                finished()
                            }.let {}

                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) =
                            main.post {
                                problem = CANT_READ
                                finished()
                            }.let {}

                        override fun onStop(
                            id: String?,
                            interrupted: Boolean,
                        ) = main.post(::finished).let {}
                    },
                )
                ready = true
                applied = null
                problem = null
                while (waiting.isNotEmpty()) say(waiting.removeFirst())
            }
        if (failed) engine.shutdown() else tts = engine
    }

    private fun finished() {
        queued = (queued - 1).coerceAtLeast(0)
        if (queued == 0 && waiting.isEmpty()) speaking = false
    }

    /** Reads in [language] (null for the launcher's), with [voice] when it's one of that language's, at [rate] (1 normal). */
    fun use(
        language: String?,
        voice: String?,
        rate: Float,
    ) {
        this.language = language
        this.voice = voice
        this.rate = rate
    }

    /** The language replies are read in. */
    val locale: Locale get() = language?.let(Locale::forLanguageTag)?.let { Locale(AppLanguage.short(it.language), it.country) } ?: Locale.getDefault()

    /** The languages it can read, one each (not each region), as tags; none before it's [ready]. */
    fun languages(): List<String> = runCatching { tts?.availableLanguages?.map { AppLanguage.short(it.language) }?.distinct() }.getOrNull().orEmpty()

    /**
     * [language]'s voices: installed first, then by region; none before it's [ready]. Not Google's "en-US-language"
     * ones, which stand for the engine's default for the language.
     */
    fun voices(language: String): List<Voice> =
        runCatching { tts?.voices?.filter { AppLanguage.short(it.locale.language) == AppLanguage.short(language) && !it.name.endsWith("-language") } }
            .getOrNull()
            .orEmpty()
            .sortedWith(compareBy({ TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features }, { it.isNetworkConnectionRequired }, { it.locale.toLanguageTag() }, { it.name }))

    /** Whether it has a voice for [locale]; true until it's [ready], as it can't say. */
    fun reads(locale: Locale) = tts?.takeIf { ready }?.let { runCatching { it.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(true) } ?: true

    /** What the engine was last set to, set again when the settings or the launcher's language change. */
    private var applied: Triple<Locale, String?, Float>? = null

    private fun apply() {
        val engine = tts ?: return
        val want = Triple(locale, voice, rate)
        if (want == applied) return
        applied = want
        engine.setSpeechRate(rate)
        val picked = voice?.let { name -> runCatching { engine.voices?.firstOrNull { it.name == name && it.locale.language == locale.language } }.getOrNull() }
        if (picked != null) {
            engine.voice = picked
        } else if (engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "no voice for $locale")
        }
    }

    private fun say(text: String) {
        apply()
        queued++
        // Refused (the engine went away): no progress callback comes for it, so it's done now.
        if (tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "s${next++}") != TextToSpeech.SUCCESS) {
            problem = CANT_READ
            finished()
        }
    }

    private companion object {
        const val TAG = "speaker"
        const val NO_ENGINE = "no text-to-speech on this phone: replies aren't read aloud"
        const val CANT_READ = "text-to-speech couldn't read the reply; is a voice for its language installed?"
    }
}
