package dev.omalauncher.upgrade

import android.content.Context
import android.util.Log
import dev.omalauncher.widgets.lang.sha256Hex
import org.json.JSONObject
import java.io.File

/** How a launcher-written file treats the copy on disk. */
enum class Policy {
    /** Launcher plumbing (shims, scripts): always rewritten to the current version. */
    Owned,

    /**
     * A default the user may edit or delete: updated only while it's unchanged
     * since we wrote it, never brought back once deleted. A file that was
     * already there before we first wrote it is the user's.
     */
    Seeded,
}

/** One file the launcher provides. */
class Managed(
    val file: File,
    val policy: Policy,
    val executable: Boolean = false,
    val content: () -> ByteArray,
) {
    companion object {
        fun text(
            file: File,
            policy: Policy,
            executable: Boolean = false,
            content: () -> String,
        ) = Managed(file, policy, executable) { content().toByteArray() }

        /** [path] in the APK's assets, read when it's written. */
        fun asset(
            context: Context,
            path: String,
            file: File,
            policy: Policy,
            executable: Boolean = false,
        ) = Managed(file, policy, executable) { context.assets.open(path).use { it.readBytes() } }
    }
}

/**
 * Keeps launcher-provided files in step with the installed APK. Every file
 * we write is recorded with its hash in a manifest, which tells an untouched
 * default (safe to update or remove) from one the user edited (left alone).
 * Files that drop out of the desired set are deleted if still untouched, so
 * nothing lingers from older versions.
 */
class ManagedFiles(
    /** Paths in the manifest are relative to this, so they survive a moved data dir. */
    private val base: File,
    private val manifest: File,
) {
    fun sync(desired: List<Managed>) {
        val recorded = load()
        val next = HashMap<String, String>()
        var failed = false
        for (m in desired) {
            val key = m.file.relativeTo(base).path
            val before = recorded[key]
            val current = hash(m.file)
            val write =
                when (m.policy) {
                    Policy.Owned -> true
                    // Missing but recorded: the user deleted it.
                    Policy.Seeded -> if (current == null) before == null else current == before
                }
            if (!write) {
                // Edited or deleted defaults keep what we last wrote on record, so they stay the user's, removal included.
                before?.let { next[key] = it }
                continue
            }
            try {
                val bytes = m.content()
                val sha = sha256Hex(bytes)
                if (current != sha) writeAtomically(m.file, bytes, m.executable)
                next[key] = sha
            } catch (e: Exception) {
                // Keeps the old record: the next sync tries again, and nothing counts as removed.
                Log.w("managed", "can't write ${m.file}", e)
                failed = true
                before?.let { next[key] = it }
            }
        }
        if (failed) return save(recorded + next)
        for ((key, sha) in recorded - next.keys) {
            val file = File(base, key)
            if (hash(file) == sha) file.delete()
        }
        // Most syncs change nothing; skip the fsync then.
        if (next != recorded) save(next)
    }

    fun forgetAll() = manifest.delete()

    private fun load(): Map<String, String> {
        val json = Versioned.readFile(manifest, VERSION) ?: return emptyMap()
        return json.keys().asSequence().associateWith { json.getString(it) }
    }

    private fun save(files: Map<String, String>) =
        Versioned.writeFile(manifest, VERSION, JSONObject(files as Map<*, *>))

    private companion object {
        const val VERSION = 1

        fun hash(file: File): String? = if (file.isFile) sha256Hex(file.readBytes()) else null
    }
}

/**
 * Replaces [file] in one rename, so a process reading or exec'ing it (a
 * running guest) never sees it half written.
 */
fun writeAtomically(
    file: File,
    bytes: ByteArray,
    executable: Boolean = false,
) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, ".${file.name}.tmp")
    // Synced before the rename: after a power cut the file is the old one or the new one, never empty.
    tmp.outputStream().use {
        it.write(bytes)
        it.fd.sync()
    }
    if (executable) tmp.setExecutable(true, false)
    if (!tmp.renameTo(file)) {
        tmp.delete()
        error("can't replace $file")
    }
}
