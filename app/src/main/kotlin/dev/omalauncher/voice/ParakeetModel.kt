package dev.omalauncher.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.R
import dev.omalauncher.net.openHttp
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * [Parakeet]'s files on the phone: downloaded when asked for in the voice settings, from Hugging Face (the model) and
 * sherpa-onnx's releases (the VAD), each checked against its SHA-256. A download that stops picks up where it was.
 * In noBackupFilesDir: 670 MB has no place in a backup. Whether it's used is a setting of its own ([on]), so it can be
 * turned off without downloading it again.
 */
internal object ParakeetModel {
    class Part(
        /** Where it goes, in [dir]. */
        val name: String,
        val url: String,
        val size: Long,
        val sha256: String,
    )

    private const val HF = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"
    const val MODEL = "parakeet-tdt-0.6b-v3-int8"
    const val VAD = "silero_vad.onnx"

    private val PARTS =
        listOf(
            Part("$MODEL/encoder.int8.onnx", "$HF/encoder.int8.onnx", 652_282_294, "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479"),
            Part("$MODEL/decoder.int8.onnx", "$HF/decoder.int8.onnx", 11_845_274, "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5"),
            Part("$MODEL/joiner.int8.onnx", "$HF/joiner.int8.onnx", 6_355_277, "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98"),
            Part("$MODEL/tokens.txt", "$HF/tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
            Part(VAD, "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx", 643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
        )

    /** All of it, in bytes. */
    val SIZE = PARTS.sumOf { it.size }

    /** The languages Parakeet TDT 0.6B v3 knows; it tells them apart itself. */
    private val LANGUAGES = setOf("bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it", "lv", "lt", "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "ru", "uk")

    fun speaks(locale: Locale) = locale.language in LANGUAGES

    fun dir(context: Context) = File(context.noBackupFilesDir, "speech")

    private fun file(
        context: Context,
        part: Part,
    ) = File(dir(context), part.name)

    /** Whether every file is there; each was checked as it arrived. */
    fun downloaded(context: Context) = PARTS.all { file(context, it).length() == it.size }

    /** Whether speech is recognized with Parakeet, for [locale]: downloaded, [on], and a language it knows. */
    fun used(
        context: Context,
        locale: Locale,
    ) = speaks(locale) && on(context) && downloaded(context)

    private const val PREFS = "speech"
    private const val ON = "parakeet"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val onState = mutableStateOf<Boolean?>(null)

    /** Whether Parakeet is picked over the phone's recognizer, once it's downloaded. */
    fun on(context: Context): Boolean = onState.value ?: prefs(context).getBoolean(ON, true).also { onState.value = it }

    fun setOn(
        context: Context,
        on: Boolean,
    ) {
        onState.value = on
        prefs(context).edit().putBoolean(ON, on).apply()
    }

    // ---------- the download, for the settings to show

    /** Bytes there so far, while [downloading]. */
    var done by mutableLongStateOf(0L)
        private set

    var downloading by mutableStateOf(false)
        private set

    /** While [downloading]: a file is in, and being checked against its SHA-256 (seconds, for the 650 MB one). */
    var checking by mutableStateOf(false)
        private set

    /** Why the last download stopped; cleared by the next. */
    var error by mutableStateOf<String?>(null)
        private set

    /** Changes when files arrive or go, for the settings to read [downloaded] again. */
    var changes by mutableIntStateOf(0)
        private set

    @Volatile private var cancelled = false

    private val main = Handler(Looper.getMainLooper())

    fun size(
        context: Context,
        bytes: Long,
    ): String = Formatter.formatShortFileSize(context, bytes)

    /** Downloads what's missing, then turns Parakeet [on]. Main thread. */
    fun download(context: Context) {
        if (downloading) return
        val app = context.applicationContext
        cancelled = false
        error = null
        downloading = true
        done = PARTS.sumOf { have(app, it) }
        Thread({
            val failed =
                try {
                    fetchAll(app)
                    null
                } catch (e: Exception) {
                    if (!cancelled) Log.w(TAG, "download failed", e)
                    e.message ?: e.toString()
                }
            main.post {
                downloading = false
                changes++
                if (failed == null) setOn(app, true) else if (!cancelled) error = failed
            }
        }, "parakeet-download").start()
    }

    /** Stops the download; what's there stays for the next one. */
    fun cancel() {
        cancelled = true
    }

    /** Deletes the files, and stops a download. Main thread. */
    fun delete(context: Context) {
        cancel()
        error = null
        val dir = dir(context)
        Thread({
            dir.deleteRecursively()
            main.post { changes++ }
        }, "parakeet-delete").start()
    }

    /** Bytes of [part] there: done, or so far. */
    private fun have(
        context: Context,
        part: Part,
    ): Long {
        val f = file(context, part)
        return if (f.length() == part.size) part.size else File(f.path + PARTIAL).length().coerceAtMost(part.size)
    }

    private fun fetchAll(context: Context) {
        val missing = SIZE - PARTS.sumOf { have(context, it) }
        // Room for the rest, and some left over for the phone.
        val free = dir(context).apply { mkdirs() }.usableSpace
        if (free < missing + SPARE) throw IOException(context.getString(R.string.voice_parakeet_space, size(context, missing)))
        var before = 0L
        for (part in PARTS) {
            val start = before
            fetch(context, part) { n -> main.post { done = start + n } }
            before += part.size
        }
    }

    private fun fetch(
        context: Context,
        part: Part,
        progress: (Long) -> Unit,
    ) {
        val target = file(context, part)
        if (target.length() == part.size) return progress(part.size)
        target.parentFile?.mkdirs()
        val partial = File(target.path + PARTIAL)
        var have = partial.length()
        if (have > part.size) {
            partial.delete()
            have = 0
        }
        if (have < part.size) {
            val c = openHttp(part.url, readTimeout = 30_000)
            try {
                c.useCaches = false
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                when (val code = c.responseCode) {
                    200 -> have = 0
                    206 -> {}
                    else -> throw IOException("HTTP $code")
                }
                FileOutputStream(partial, have > 0).use { out ->
                    c.inputStream.use { input ->
                        val buffer = ByteArray(256 * 1024)
                        var reported = have
                        while (true) {
                            if (cancelled) throw IOException("cancelled")
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            have += n
                            if (have - reported >= REPORT) {
                                reported = have
                                progress(have)
                            }
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
        }
        progress(have)
        main.post { checking = true }
        val sound =
            try {
                partial.length() == part.size && sha256(partial) == part.sha256
            } finally {
                main.post { checking = false }
            }
        if (!sound) {
            partial.delete()
            throw IOException(context.getString(R.string.voice_parakeet_damaged))
        }
        if (!partial.renameTo(target)) throw IOException("can't rename $partial")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val TAG = "parakeet"
    private const val PARTIAL = ".part"
    private const val REPORT = 1024 * 1024L
    private const val SPARE = 200 * 1024 * 1024L
}
