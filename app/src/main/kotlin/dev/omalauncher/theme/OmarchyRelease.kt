package dev.omalauncher.theme

import dev.omalauncher.net.httpGet
import dev.omalauncher.storage.LauncherData
import dev.omalauncher.upgrade.writeAtomically
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** One file [OmarchyRelease.download] fetches: its path in the repo and its size in bytes. */
data class ReleaseFile(
    val path: String,
    val size: Long,
)

/**
 * The files to fetch from Omarchy at [ref]: themes, config templates and app
 * configs, the list scripts/fetch-theme-colors.sh writes to assets/omarchy-files.txt.
 */
data class ReleaseFiles(
    val ref: String,
    val files: List<ReleaseFile>,
) {
    companion object {
        /** Lines of "size path" after one "ref <commit>"; "#" lines are comments. */
        fun parse(text: String): ReleaseFiles {
            var ref = ""
            val files = mutableListOf<ReleaseFile>()
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val (first, rest) = line.substringBefore(' ') to line.substringAfter(' ', "")
                if (first == "ref") ref = rest else files += ReleaseFile(rest, first.toLong())
            }
            return ReleaseFiles(ref, files)
        }
    }
}

/**
 * Omarchy's repo at [ThemeStore.OMARCHY_REF], the parts the launcher uses,
 * where Omarchy keeps it (~/.local/share/omarchy). A download replaces the
 * whole directory in one rename once complete: an interrupted one leaves the
 * previous version, and nothing lingers from it after a successful one.
 * [fetch] gets a file's bytes by its path in the repo.
 */
class OmarchyRelease(
    val dir: File,
    private val fetch: (String) -> ByteArray = ::fetchRaw,
) {
    private val marker = File(dir, ".omalauncher-ref")

    fun isCurrent() = runCatching { marker.readText().trim() }.getOrNull() == ThemeStore.OMARCHY_REF

    /**
     * Fetches one theme's colors and one of its backgrounds alone, from
     * raw.githubusercontent.com at the same commit: a few MB instead of the
     * release, so first run doesn't wait for it. [download] replaces them
     * with the full release later. Blocking.
     */
    fun downloadTheme(
        name: String,
        background: String?,
    ) {
        val theme = File(dir, "themes/$name")
        // The background first: a theme dir counts as installed once colors.toml is there.
        background?.let { writeAtomically(File(theme, "backgrounds/$it"), fetch("themes/$name/backgrounds/$it")) }
        writeAtomically(File(theme, "colors.toml"), fetch("themes/$name/colors.toml"))
    }

    /**
     * Blocking. Fetches [release]'s files one by one, a few at a time, into a
     * staging directory that outlives a failed or interrupted attempt: the
     * next one fetches only what's missing, where a tarball started over.
     * [onProgress] gets the megabytes on hand so far. [picked] is the theme
     * in use: if this release drops it, it moves to [userThemes].
     */
    fun download(
        release: ReleaseFiles,
        picked: String,
        userThemes: File,
        onProgress: (Int) -> Unit,
    ) {
        val staging = File(dir.parentFile, ".omarchy.download")
        // The marker names the commit a staging directory holds; one for another commit, or from a tarball, starts over.
        val stagingMarker = File(staging, marker.name)
        if (runCatching { stagingMarker.readText().trim() }.getOrNull() != release.ref) {
            LauncherData.deleteTree(staging)
            staging.mkdirs()
            stagingMarker.writeText(release.ref + "\n")
        }
        val root = staging.canonicalFile
        val have = AtomicLong()
        var reported = -1L
        val report = { bytes: Long ->
            synchronized(have) {
                val megabytes = have.addAndGet(bytes) shr 20
                if (megabytes != reported) {
                    reported = megabytes
                    onProgress(megabytes.toInt())
                }
            }
        }
        val pool = Executors.newFixedThreadPool(PARALLEL)
        try {
            release.files
                .map { file ->
                    val target = requireNotNull(root.inside(file.path)) { "bad path ${file.path}" }
                    pool.submit {
                        // Written atomically, so a file with the listed size is complete.
                        if (target.length() != file.size || !target.isFile) {
                            val bytes = fetch(file.path)
                            if (bytes.size.toLong() != file.size) throw IOException("${file.path}: ${bytes.size} bytes, expected ${file.size}")
                            writeAtomically(target, bytes)
                        }
                        report(file.size)
                    }
                }.forEach {
                    try {
                        it.get()
                    } catch (e: ExecutionException) {
                        throw e.cause ?: e
                    }
                }
        } finally {
            pool.shutdownNow()
        }
        keepDroppedTheme(File(dir, "themes"), File(staging, "themes"), userThemes, picked)
        LauncherData.deleteTree(dir)
        check(staging.renameTo(dir)) { "can't move the themes into place" }
    }

    private companion object {
        /** Files fetched at once: most are small, so one at a time would mostly wait on round trips. */
        const val PARALLEL = 4
    }
}

/** A file of Omarchy's repo at [ThemeStore.OMARCHY_REF]. Blocking. */
private fun fetchRaw(path: String): ByteArray =
    httpGet("https://raw.githubusercontent.com/omacom/omarchy/${ThemeStore.OMARCHY_REF}/$path", connectTimeout = 15_000, readTimeout = 30_000)

/**
 * Copies theme [name] from the [old] release's themes into [userThemes] when
 * the [new] one no longer has it, as Omarchy 4.5 dropped Osaka Jade. Omarchy
 * keeps whoever uses such a theme on it, since the current theme is a copy
 * there; here the release is replaced whole, so it becomes the user's own.
 * Files the user already has in their directory of it stay.
 */
internal fun keepDroppedTheme(
    old: File,
    new: File,
    userThemes: File,
    name: String,
) {
    if (!ThemeDirs.isValidName(name)) return
    val from = File(old, name)
    if (!from.isDirectory || File(new, name).exists()) return
    val to = File(userThemes, name)
    from.walkTopDown().filter { it.isFile }.forEach { f ->
        val target = File(to, f.relativeTo(from).path)
        if (!target.exists()) f.copyTo(target)
    }
}
