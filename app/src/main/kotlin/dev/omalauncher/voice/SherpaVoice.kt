package dev.omalauncher.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * On-device text-to-speech with Supertonic 3 through sherpa-onnx ([SupertonicModel]), for [Speaker] to read replies
 * with instead of the phone's engine. Supertonic reads plain text itself; sherpa-onnx 2 dropped eSpeak, which Piper and
 * Kokoro voices need to read anything but the words in their lexicons.
 *
 * One thread generates and a second plays, so the next part is generated while one plays; [stop] drops what's queued
 * and cuts what's playing. Supertonic makes a text whole before any of it can play (about a third of its length on a
 * Pixel 9a), so a text is read in [parts]: a short first one when a reply starts, so it's heard soon. The model (about
 * 150 MB in memory) is loaded on first use and let go after [IDLE_MIN] minutes without a text, or once it's deleted.
 * Callbacks come on those threads.
 */
internal class SherpaVoice(
    private val context: Context,
    /** A bit of audio went out: for the orb's beat. */
    private val chunk: () -> Unit,
    /** A text is finished; not for those [stop] dropped. */
    private val done: () -> Unit,
    /** A text couldn't be read: the model failed to load, generate or play. */
    private val failed: () -> Unit,
) {
    private class Job(
        /** Empty: only load the model. */
        val text: String,
        val language: String,
        val rate: Float,
        val voice: Int,
        val generation: Int,
        /** The last part of a text: its end is marked once it's played. */
        val last: Boolean,
    )

    private val queue = LinkedBlockingQueue<Job>()

    @Volatile private var generation = 0

    private var thread: Thread? = null

    /** The loaded model; generating thread only. */
    private var loaded: OfflineTts? = null

    @Volatile private var track: AudioTrack? = null

    /** Loads the model ahead of the first text. */
    fun warmUp() {
        queue += Job("", "", 1f, 0, generation, last = false)
        ensure()
    }

    fun say(
        text: String,
        language: String,
        rate: Float,
        voice: Int,
    ) {
        // A reply starts: nothing's queued or playing.
        val starts = queue.none { it.text.isNotEmpty() } && pieces.isEmpty() && track == null
        val parts = parts(text, starts)
        // Nothing to say: done at once, or Voice waits for it.
        if (parts.isEmpty()) return done()
        val now = generation
        parts.forEachIndexed { i, part -> queue += Job(part, language, rate, voice, now, last = i == parts.lastIndex) }
        ensure()
    }

    fun stop() {
        generation++
        queue.clear()
        track?.let {
            runCatching {
                it.pause()
                it.flush()
            }
        }
    }

    @Synchronized
    private fun ensure() {
        if (thread != null) return
        thread = Thread(::run, "supertonic").apply { start() }
        Thread(::play, "supertonic-play").start()
    }

    private fun run() {
        while (true) {
            val job = queue.poll(IDLE_MIN, TimeUnit.MINUTES)
            // Not used for a while, or deleted in the settings: the memory goes back.
            if (job == null || !SupertonicModel.files.downloaded(context)) letGo()
            if (job == null) continue
            var rate = 0
            try {
                if (job.generation == generation) rate = read(job)
            } catch (e: Throwable) {
                Log.w(TAG, "reading failed", e)
                if (job.text.isNotEmpty() && job.generation == generation) failed()
            }
            if (job.last) pieces += Piece(null, rate, job.generation)
        }
    }

    private fun letGo() {
        val tts = loaded ?: return
        loaded = null
        runCatching { tts.release() }
        Log.i(TAG, "model let go")
    }

    private fun load(): OfflineTts {
        loaded?.let { return it }
        // A missing or half-deleted file can abort the process in the native code, not throw.
        check(SupertonicModel.files.downloaded(context)) { "Supertonic isn't downloaded" }
        val began = SystemClock.elapsedRealtime()
        val dir = SupertonicModel.dir(context)
        fun f(name: String) = File(dir, name).path
        val config =
            OfflineTtsModelConfig(
                supertonic =
                    OfflineTtsSupertonicModelConfig(
                        durationPredictor = f("duration_predictor.int8.onnx"),
                        textEncoder = f("text_encoder.int8.onnx"),
                        vectorEstimator = f("vector_estimator.int8.onnx"),
                        vocoder = f("vocoder.int8.onnx"),
                        ttsJson = f("tts.json"),
                        unicodeIndexer = f("unicode_indexer.bin"),
                        voiceStyle = f("voice.bin"),
                    ),
                numThreads = THREADS,
            )
        val tts = OfflineTts(null, OfflineTtsConfig(model = config))
        Log.i(TAG, "loaded in ${SystemClock.elapsedRealtime() - began} ms")
        loaded = tts
        return tts
    }

    /** Generates [job]'s audio and hands it to [play]; its sample rate. */
    private fun read(job: Job): Int {
        val tts = load()
        val rate = tts.sampleRate()
        if (job.text.isEmpty()) return rate
        val began = SystemClock.elapsedRealtime()
        val audio =
            tts.generateWithConfigAndCallback(
                job.text,
                // Normal speed, sped up as it plays: Supertonic's own speed above 1 makes a short sentence silent ("Got
                // it." at 1.5x is 0.9 s of nothing) and cuts longer ones short.
                GenerationConfig(sid = job.voice, speed = 1f, numSteps = STEPS, extra = mapOf("lang" to job.language)),
                // A class, not a lambda: sherpa's JNI calls invoke([F)Ljava/lang/Integer;, which a desugared lambda lacks.
                callback =
                    object : (FloatArray) -> Int {
                        // The whole text comes in one call; the audio is taken from the result. 0 stops it.
                        override fun invoke(samples: FloatArray): Int = if (job.generation == generation) 1 else 0
                    },
            )
        val samples = trim(audio.samples, rate, ends(job.text))
        if (job.generation == generation && samples.isNotEmpty()) pieces += Piece(samples, rate, job.generation, job.rate)
        Log.i(TAG, "${job.text.length} characters: ${samples.size * 1000L / rate} ms of speech in ${SystemClock.elapsedRealtime() - began} ms")
        return rate
    }

    private fun track(rate: Int): AudioTrack {
        track?.let { if (it.sampleRate == rate) return it else close() }
        // Room for Android to speed it up: with only the minimum, a speed above 1 is refused as out of range.
        val size = maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 4, rate)
        return AudioTrack
            .Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { track = it }
    }

    /** What's generated, for [play]: audio, or the end of a text (null). */
    private class Piece(
        val samples: FloatArray?,
        val rate: Int,
        val generation: Int,
        /** How fast it's played, 1 normal. */
        val speed: Float = 1f,
    )

    private val pieces = LinkedBlockingQueue<Piece>()

    /** Frames written to [track] since it last started; play thread only. */
    private var written = 0L

    /**
     * Plays [pieces] as they come. A reply gets a stream of its own, opened at its first part and closed once the last
     * has played out, the way Android's engine does it, and starts with a little silence while the speaker wakes. A
     * stream that fails is dropped, and the text counts as done, so Voice doesn't wait for it forever.
     */
    private fun play() {
        while (true) {
            val piece = pieces.take()
            val samples = piece.samples
            if (samples == null) {
                try {
                    val out = track
                    if (out != null && piece.generation == generation) {
                        // Done when it's been heard, not when it's been handed over.
                        drain(out, piece)
                        // The last of the reply: the stream closes, and the next reply opens a new one.
                        if (pieces.isEmpty() && queue.none { it.text.isNotEmpty() }) close()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "playing failed", e)
                    close()
                } finally {
                    if (piece.generation == generation) done()
                }
                continue
            }
            if (piece.generation != generation) continue
            try {
                write(samples, piece)
            } catch (e: Exception) {
                Log.w(TAG, "playing failed", e)
                failed()
                close()
            }
        }
    }

    private fun write(
        samples: FloatArray,
        piece: Piece,
    ) {
        val out = track(piece.rate)
        if (out.playState != AudioTrack.PLAYSTATE_PLAYING) {
            out.play()
            written = 0
            // Sped up by Android, the pitch kept.
            runCatching { out.playbackParams = PlaybackParams().setSpeed(piece.speed).setPitch(1f) }.onFailure { Log.w(TAG, "can't play at ${piece.speed}x", it) }
            val lead = FloatArray(piece.rate * LEAD_MS / 1000)
            out.write(lead, 0, lead.size, AudioTrack.WRITE_BLOCKING)
            written += lead.size
        }
        val step = piece.rate / 4
        var at = 0
        while (at < samples.size && piece.generation == generation) {
            val n = out.write(samples, at, minOf(step, samples.size - at), AudioTrack.WRITE_BLOCKING)
            // An error code: the stream is gone (stopped, its output went away).
            if (n <= 0) break
            at += n
            written += n
            chunk()
        }
    }

    /** Waits for what's been written to [out] to play, up to a second and a half; not once stopped. */
    private fun drain(
        out: AudioTrack,
        piece: Piece,
    ) {
        val until = SystemClock.elapsedRealtime() + 1500
        while (piece.generation == generation && SystemClock.elapsedRealtime() < until && (out.playbackHeadPosition.toLong() and 0xffffffffL) < written) SystemClock.sleep(20)
    }

    private fun close() {
        val out = track ?: return
        track = null
        runCatching { out.stop() }
        runCatching { out.release() }
    }

    companion object {
        private const val TAG = "supertonic"
        private const val THREADS = 4

        /** Denoising steps: more sound better and take longer; 5 is Supertonic's own default. */
        private const val STEPS = 5

        /** Silence ahead of a reply, while the speaker wakes up. */
        private const val LEAD_MS = 300

        /** Minutes without a text before the model is let go. */
        private const val IDLE_MIN = 5L

        /** The longest part read at once: a run-on text (code, a list without full stops) waits no longer than this. */
        const val MOST = 200

        /** A reply's first part, cut at its first pause after [FIRST_MIN] characters when it's longer than [FIRST]. */
        const val FIRST = 80
        const val FIRST_MIN = 20

        private val SENTENCE_END = setOf('.', '!', '?', '…', '。', '！', '？')
        private val PAUSES = setOf(',', ';', ':', '–', '—', '，', '；', '：', '、')

        /**
         * [text] in the parts it's read in, each made whole before it plays: a reply's first ([first]) cut short at its
         * first pause (a comma, a colon) so it's heard soon, and none longer than [MOST], cut at a pause or else a
         * space. Nothing when there's nothing to say: a text without letters or digits ("...", emoji) comes out as
         * babble or silence.
         */
        fun parts(
            text: String,
            first: Boolean,
        ): List<String> {
            if (text.none(Char::isLetterOrDigit)) return emptyList()
            val parts = ArrayList<String>()
            var rest = text.trim()
            if (first && rest.length > FIRST) {
                val cut = (FIRST_MIN until minOf(rest.length - 1, MOST)).firstOrNull { rest[it] in PAUSES && rest[it + 1].isWhitespace() }
                if (cut != null) {
                    parts += rest.substring(0, cut + 1)
                    rest = rest.substring(cut + 1).trim()
                }
            }
            while (rest.length > MOST) {
                val window = rest.substring(0, MOST)
                val cut =
                    window.indexOfLast { it in PAUSES || it in SENTENCE_END }.takeIf { it >= MOST / 2 }?.plus(1)
                        ?: window.lastIndexOf(' ').takeIf { it >= MOST / 2 }
                        ?: MOST
                parts += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) parts += rest
            return parts.filter { it.any(Char::isLetterOrDigit) }
        }

        /** Whether [text] ends a sentence, so a full pause follows it, not a comma's. */
        fun ends(text: String) = text.trimEnd().lastOrNull()?.let { it in SENTENCE_END } ?: false

        /** Quieter than this counts as silence. */
        private const val QUIET = 0.01f

        /**
         * [samples] without most of the silence Supertonic puts around a text (about 0.35 s before, 0.5 s after): the
         * first word comes sooner, and the pause between parts is a pause, not a second of nothing. A little is kept
         * before, and after, a comma's or a full stop's worth ([sentence]). Silence throughout: nothing.
         */
        fun trim(
            samples: FloatArray,
            rate: Int,
            sentence: Boolean,
        ): FloatArray {
            val start = samples.indexOfFirst { abs(it) > QUIET }
            if (start < 0) return FloatArray(0)
            val end = samples.indexOfLast { abs(it) > QUIET } + 1
            val from = maxOf(0, start - rate * BEFORE_MS / 1000)
            val to = minOf(samples.size, end + rate * (if (sentence) AFTER_SENTENCE_MS else AFTER_PAUSE_MS) / 1000)
            return samples.copyOfRange(from, to)
        }

        private const val BEFORE_MS = 40
        private const val AFTER_PAUSE_MS = 150
        private const val AFTER_SENTENCE_MS = 300
    }
}
