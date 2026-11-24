package dev.omalauncher.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.R
import dev.omalauncher.net.openHttp
import dev.omalauncher.widgets.lang.hex
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * A speech model's files on the phone ([ParakeetModel], [SupertonicModel]): downloaded when asked for in the voice
 * settings, each checked against its SHA-256. A download that stops picks up where it was. In noBackupFilesDir/[folder]:
 * hundreds of MB have no place in a backup. Whether the model is used is a setting of its own ([on], saved as [pref]),
 * so the phone's own service can be picked again without downloading it again; a finished download turns it on.
 */
internal class ModelFiles(
    private val tag: String,
    private val folder: String,
    private val parts: List<Part>,
    private val pref: String,
) {
    class Part(
        /** Where it goes, in [dir]. */
        val name: String,
        val url: String,
        val size: Long,
        val sha256: String,
    )

    /** All of it, in bytes. */
    val size = parts.sumOf { it.size }

    fun dir(context: Context) = File(context.noBackupFilesDir, folder)

    private fun file(
        context: Context,
        part: Part,
    ) = File(dir(context), part.name)

    /** Whether every file is there; each was checked as it arrived. */
    fun downloaded(context: Context) = parts.all { file(context, it).length() == it.size }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var onState: MutableState<Boolean>? = null

    /**
     * The pick as Compose state, made from [saved] by its first reader. Made, not written: the first reader can be the
     * drawer's snapshotFlow, whose snapshot is read-only, and a write there throws.
     */
    internal fun onState(saved: () -> Boolean): MutableState<Boolean> = onState ?: synchronized(this) { onState ?: mutableStateOf(saved()).also { onState = it } }

    /** Whether the model is picked over the phone's own service, once it's downloaded. */
    fun on(context: Context): Boolean = onState { prefs(context).getBoolean(pref, true) }.value

    fun setOn(
        context: Context,
        on: Boolean,
    ) {
        onState { on }.value = on
        prefs(context).edit().putBoolean(pref, on).apply()
    }

    /** Whether the model is the one used: picked ([on]) and downloaded. */
    fun used(context: Context) = on(context) && downloaded(context)

    /** Bytes there so far, while [downloading]. */
    var done by mutableLongStateOf(0L)
        private set

    var downloading by mutableStateOf(false)
        private set

    /** While [downloading]: a file is in, and being checked against its SHA-256 (seconds, for a big one). */
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

    /** Downloads what's missing, then turns the model [on]. Main thread. */
    fun download(context: Context) {
        if (downloading) return
        val app = context.applicationContext
        cancelled = false
        error = null
        downloading = true
        done = parts.sumOf { have(app, it) }
        Thread({
            val failed =
                try {
                    fetchAll(app)
                    null
                } catch (e: Exception) {
                    if (!cancelled) Log.w(tag, "download failed", e)
                    e.message ?: e.toString()
                }
            main.post {
                downloading = false
                changes++
                if (failed == null) setOn(app, true) else if (!cancelled) error = failed
            }
        }, "$tag-download").start()
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
        }, "$tag-delete").start()
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
        val missing = size - parts.sumOf { have(context, it) }
        // Room for the rest, and some left over for the phone.
        val free = dir(context).apply { mkdirs() }.usableSpace
        if (free < missing + SPARE) throw IOException(context.getString(R.string.voice_parakeet_space, size(context, missing)))
        var before = 0L
        for (part in parts) {
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
        return digest.digest().hex()
    }

    private companion object {
        const val PREFS = "speech"
        const val PARTIAL = ".part"
        const val REPORT = 1024 * 1024L
        const val SPARE = 200 * 1024 * 1024L
    }
}
