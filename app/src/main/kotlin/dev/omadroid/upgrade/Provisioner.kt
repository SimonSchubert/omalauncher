package dev.omadroid.upgrade

import android.util.Log
import java.io.File

/**
 * Keeps a set of launcher-provided files in step with this APK: shims,
 * default configs, desktop entries, widgets. Each part of the app
 * contributes its files through [sources]; [sync] always writes the whole
 * set, since anything left out is treated as removed. The launcher has two:
 * one for its own data, one for the guest's (which goes with Linux).
 */
class Provisioner(
    filesDir: File,
    manifest: String,
    private val sources: List<() -> List<Managed>>,
) {
    private val files = ManagedFiles(filesDir, File(filesDir, manifest))

    /** Cheap when nothing changed: it only hashes files. Call after anything that changes the desired set. */
    @Synchronized
    fun sync() {
        runCatching { files.sync(sources.flatMap { it() }) }.onFailure { Log.e("provision", "sync failed", it) }
    }

    /** A new rootfs holds none of the recorded files; forget them rather than treat them as deleted by the user. */
    @Synchronized
    fun forgetAll() = files.forgetAll()
}
