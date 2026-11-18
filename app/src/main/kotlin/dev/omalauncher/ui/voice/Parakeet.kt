package dev.omalauncher.ui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * On-device speech recognition with NVIDIA's Parakeet TDT 0.6B v3 through sherpa-onnx, for [Speech]: the launcher
 * records the microphone itself, Silero VAD finds where each phrase ends, and each phrase is decoded whole. While a
 * phrase is still being said, what's been said of it so far is decoded again every few hundred milliseconds for the
 * partial text. No beeps, no audio focus taken, no session limit. Parakeet knows 25 European languages and picks the
 * one it hears.
 *
 * The models are downloaded in the voice settings, see [ParakeetModel]. Timings go to logcat under "parakeet".
 */
internal class Parakeet(
    private val context: Context,
    private val hold: Boolean,
    private val events: Events,
) {
    /** What it hears, on the main thread. */
    interface Events {
        /** The model is loaded: what's heard turns into text from now on, what was heard before too. */
        fun ready()

        /** The phrase being said, so far. */
        fun partial(text: String)

        /** A finished phrase. */
        fun phrase(text: String)

        fun level(level: Float)

        /** Listening ended, by [stop], a pause when not held, or [error]. Not after [cancel]. */
        fun ended(error: String?)
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var stopping = false

    @Volatile private var cancelled = false

    private val thread = Thread(::run, "parakeet")

    /** Whether [run]'s model loading began, and so needs a [release]. */
    private var loadStarted = false

    fun start() = thread.start()

    /** Ends listening once what's been said so far is decoded. */
    fun stop() {
        stopping = true
    }

    /** Ends listening now, without [Events.ended]. */
    fun cancel() {
        cancelled = true
        stopping = true
    }

    private fun post(call: Events.() -> Unit) = main.post { if (!cancelled) events.call() }

    private fun run() {
        var error: String? = null
        // The model loads while the microphone already listens: what's said meanwhile waits for it, not lost.
        val loading = FutureTask { acquire(context) }
        try {
            listen(loading)
        } catch (e: Throwable) {
            Log.w(TAG, "listening failed", e)
            error = (e as? ExecutionException)?.cause?.message ?: e.message ?: e.toString()
        } finally {
            // [acquire] counts this as a user even when loading failed: once it's done, [release] evens that out.
            if (loadStarted) {
                runCatching { loading.get() }
                release()
            }
        }
        post { ended(error) }
    }

    private fun listen(loading: FutureTask<OfflineRecognizer>) {
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "no microphone permission" }
        val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT), RATE * 4 * BUFFER_S)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, size)
        check(record.state == AudioRecord.STATE_INITIALIZED) { "the microphone can't be opened" }
        val vad = Vad(null, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model = File(ParakeetModel.dir(context), ParakeetModel.VAD).path, threshold = 0.5f, minSilenceDuration = PAUSE_S, minSpeechDuration = 0.15f, windowSize = WINDOW, maxSpeechDuration = 20f), sampleRate = RATE, numThreads = 1))
        try {
            val began = SystemClock.elapsedRealtime()
            record.startRecording()
            loadStarted = true
            Thread(loading, "parakeet-load").start()
            Log.i(TAG, "listening${if (hold) ", held" else ""}")
            val chunk = FloatArray(WINDOW)
            // The phrase being said, from a little before the VAD heard it start: its own cut begins too late for a
            // soft first word.
            var phrase = FloatArray(RATE * 30)
            var length = 0
            var speaking = false
            var lastPartial = 0L
            var partialEvery = PARTIAL_MS
            // The current phrase's latest partial text: Parakeet sometimes hears nothing in a whole phrase it heard
            // words in while it was said (quiet speech), and those words beat none.
            var lastText = ""
            var heard = false
            var loudest = 0f
            // Phrases finished before the model was there.
            val waiting = ArrayList<FloatArray>()
            var recognizer: OfflineRecognizer? = null
            while (!stopping) {
                val n = record.read(chunk, 0, WINDOW, AudioRecord.READ_BLOCKING)
                if (n <= 0) error("the microphone stopped ($n)")
                val level = loudness(chunk, n)
                loudest = maxOf(loudest, level)
                post { level(level) }
                val samples = if (n == WINDOW) chunk else chunk.copyOf(n)
                vad.acceptWaveform(samples)
                if (length + n > phrase.size) phrase = phrase.copyOf(phrase.size * 2)
                System.arraycopy(samples, 0, phrase, length, n)
                length += n
                if (recognizer == null && loading.isDone) {
                    recognizer = loading.get()
                    post { ready() }
                    Log.i(TAG, "model ready ${SystemClock.elapsedRealtime() - began}ms after the tap, ${waiting.size} phrases waiting")
                    for (w in waiting) decode(recognizer, w).first.let { post { phrase(it) } }
                    waiting.clear()
                }
                val now = SystemClock.elapsedRealtime()
                if (vad.isSpeechDetected()) {
                    // The first partial at once: the words so far show as soon as speech is heard.
                    if (!speaking) lastPartial = 0
                    speaking = true
                    heard = true
                } else if (!speaking && length > KEEP) {
                    // Silence before a phrase: only its last moments.
                    System.arraycopy(phrase, length - KEEP, phrase, 0, KEEP)
                    length = KEEP
                }
                if (recognizer != null && speaking && now - lastPartial >= partialEvery) {
                    val (text, ms) = decode(recognizer, phrase.copyOf(length))
                    // A partial must not take longer than the audio it keeps up with: slower phones decode less often.
                    partialEvery = maxOf(PARTIAL_MS, ms * 3 / 2)
                    lastPartial = SystemClock.elapsedRealtime()
                    if (text.isNotBlank()) {
                        lastText = text
                        post { partial(text) }
                    }
                }
                if (!vad.empty()) {
                    while (!vad.empty()) vad.pop()
                    val whole = phrase.copyOf(length)
                    if (recognizer != null) decode(recognizer, whole).first.ifBlank { lastText }.let { post { phrase(it) } } else waiting += whole
                    lastText = ""
                    length = 0
                    speaking = false
                    partialEvery = PARTIAL_MS
                    if (!hold) stopping = true
                }
                if (!hold && !heard && now - began > SILENCE_MS) stopping = true
            }
            Log.i(TAG, "stopped after ${SystemClock.elapsedRealtime() - began}ms, loudest %.2f".format(loudest))
            if (cancelled) return
            record.stop()
            // What's still being said when the tap came.
            vad.flush()
            val last = if (speaking || !vad.empty()) phrase.copyOf(length) else null
            if (waiting.isEmpty() && last == null) return
            val r = recognizer ?: loading.get()
            for (w in waiting) decode(r, w).first.let { post { phrase(it) } }
            if (last != null) decode(r, last).first.ifBlank { lastText }.let { post { phrase(it) } }
        } finally {
            record.release()
            vad.release()
        }
    }

    private fun decode(
        recognizer: OfflineRecognizer,
        samples: FloatArray,
    ): Pair<String, Long> {
        val t = SystemClock.elapsedRealtime()
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, RATE)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            val ms = SystemClock.elapsedRealtime() - t
            val audio = samples.size * 1000L / RATE
            Log.i(TAG, "decoded ${audio}ms of audio in ${ms}ms (RTF %.3f): %s".format(ms.toFloat() / audio.coerceAtLeast(1), text))
            return text to ms
        } finally {
            stream.release()
        }
    }

    private fun loudness(
        chunk: FloatArray,
        n: Int,
    ): Float {
        var sum = 0.0
        for (i in 0 until n) sum += chunk[i] * chunk[i]
        val db = 20 * log10(sqrt(sum / n).coerceAtLeast(1e-6))
        // -60 dB (quiet room) to -15 dB (speaking close).
        return ((db + 60) / 45).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        private const val TAG = "parakeet"
        private const val RATE = 16000

        /** Silero's window at 16 kHz. */
        private const val WINDOW = 512

        /** How long a pause ends a phrase. */
        private const val PAUSE_S = 0.6f

        private const val PARTIAL_MS = 200L

        /** Not held, how long listening waits for anything to be said. */
        private const val SILENCE_MS = 8000L

        /** The microphone's buffer, so decoding a phrase never drops what's said meanwhile. */
        private const val BUFFER_S = 10

        /** Samples kept before a phrase starts. */
        private const val KEEP = RATE / 2


        /** How long the model stays loaded after the last use, on the voice screen or not: it takes 650 MB. */
        private const val UNLOAD_MS = 2 * 60 * 1000L

        private const val THREADS = 4


        private val lock = Any()
        private var loaded: OfflineRecognizer? = null

        /** Sessions and preloads holding [loaded]: it's only unloaded with none. */
        private var users = 0
        private val unloader = Handler(Looper.getMainLooper())
        private val unload =
            Runnable {
                synchronized(lock) {
                    if (users > 0) return@Runnable
                    loaded?.release()
                    loaded = null
                }
                Log.i(TAG, "model unloaded")
            }

        /** The recognizer, loaded the first time; once the last user calls [release], it's unloaded after a while. */
        private fun acquire(context: Context): OfflineRecognizer =
            synchronized(lock) {
                users++
                unloader.removeCallbacks(unload)
                loaded ?: run {
                    val t = SystemClock.elapsedRealtime()
                    val dir = File(ParakeetModel.dir(context), ParakeetModel.MODEL)
                    val config =
                        OfflineRecognizerConfig(
                            modelConfig =
                                OfflineModelConfig(
                                    transducer = OfflineTransducerModelConfig(encoder = "$dir/encoder.int8.onnx", decoder = "$dir/decoder.int8.onnx", joiner = "$dir/joiner.int8.onnx"),
                                    tokens = "$dir/tokens.txt",
                                    modelType = "nemo_transducer",
                                    numThreads = THREADS,
                                ),
                        )
                    OfflineRecognizer(null, config).also {
                        loaded = it
                        Log.i(TAG, "model loaded in ${SystemClock.elapsedRealtime() - t}ms")
                    }
                }
            }

        /** One thread for [keep] and [letGo], so a let go never comes before its keep. */
        private val keeper = Executors.newSingleThreadExecutor()
        private var kept = false

        /**
         * Loads the model and keeps it while a screen that listens is in sight, so a tap doesn't wait for it to load
         * again; [letGo] when it's out of sight. Only when Parakeet is [ParakeetModel.used].
         */
        fun keep(
            context: Context,
            locale: Locale,
        ) {
            if (!ParakeetModel.used(context, locale)) return
            val app = context.applicationContext
            keeper.execute {
                if (kept) return@execute
                kept = true
                runCatching { acquire(app) }.onFailure { Log.w(TAG, "loading failed", it) }
            }
        }

        fun letGo() =
            keeper.execute {
                if (!kept) return@execute
                kept = false
                release()
            }

        private fun release() =
            synchronized(lock) {
                users--
                if (users == 0) unloader.postDelayed(unload, UNLOAD_MS)
            }
    }
}
