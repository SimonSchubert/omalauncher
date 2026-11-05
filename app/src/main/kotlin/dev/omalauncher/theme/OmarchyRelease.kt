package dev.omalauncher.theme

import dev.omalauncher.LauncherData
import dev.omalauncher.httpGet
import dev.omalauncher.upgrade.writeAtomically
import java.io.File

/** What we keep from the Omarchy repo: themes, config templates and app configs. */
private val KEEP_PATHS =
    listOf(
        "themes/", "default/themed/", "default/bash/", "config/btop/", "config/starship.toml",
        "config/lazygit/", "config/tmux/", "etc/fastfetch/", "icon.txt",
    )

/**
 * Omarchy's repo at [ThemeStore.OMARCHY_REF], unpacked where Omarchy keeps it
 * (~/.local/share/omarchy). A download replaces the whole directory in one
 * rename once complete: an interrupted one leaves the previous version, and
 * nothing lingers from it after a successful one.
 */
class OmarchyRelease(
    val dir: File,
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
        background?.let { fetch("themes/$name/backgrounds/$it", File(theme, "backgrounds/$it")) }
        fetch("themes/$name/colors.toml", File(theme, "colors.toml"))
    }

    private fun fetch(
        path: String,
        target: File,
    ) {
        val url = "https://raw.githubusercontent.com/omacom/omarchy/${ThemeStore.OMARCHY_REF}/$path"
        writeAtomically(target, httpGet(url, connectTimeout = 15_000, readTimeout = 30_000))
    }

    /**
     * Blocking. [onProgress] gets the megabytes read so far. [picked] is the
     * theme in use: if this release drops it, it moves to [userThemes].
     */
    fun download(
        picked: String,
        userThemes: File,
        onProgress: (Int) -> Unit,
    ) {
        val staging = File(dir.parentFile, ".omarchy.download").also(LauncherData::deleteTree)
        unpackTarball("https://codeload.github.com/omacom/omarchy/tar.gz/${ThemeStore.OMARCHY_REF}", staging, onProgress) { path ->
            // The lock-screen images and the boot intro videos (4.5) are unused here and add ~20 MB and ~100 MB.
            KEEP_PATHS.any { path.startsWith(it) } && !path.endsWith("unlock.png") && "/backgrounds/intros/" !in path
        }
        keepDroppedTheme(File(dir, "themes"), File(staging, "themes"), userThemes, picked)
        File(staging, marker.name).writeText(ThemeStore.OMARCHY_REF + "\n")
        LauncherData.deleteTree(dir)
        check(staging.renameTo(dir)) { "can't move the themes into place" }
    }
}

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
