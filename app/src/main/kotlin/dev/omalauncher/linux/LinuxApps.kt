package dev.omalauncher.linux

import java.io.File

data class LinuxApp(
    val name: String,
    /** The command, without the terminal wrapper for terminal apps. */
    val exec: String,
    val terminal: Boolean,
    /** Desktop file id (foot.desktop -> "foot"); GUI apps usually use it as their Wayland app_id. */
    val id: String,
    val wmClass: String?,
    /** Icon= value: a theme icon name or an absolute guest path. */
    val icon: String? = null,
    /** The file types it opens (MimeType=). */
    val mimeTypes: List<String> = emptyList(),
    /** Exec= as written, field codes and all: [commandFor] fills in a file. */
    val execTemplate: String = exec,
)

/** The command that opens [paths] (guest paths): the entry's file or URL field code filled in, else the paths appended. */
fun LinuxApp.commandFor(paths: List<String>): String {
    val files = paths.map(::shellQuote)
    // file:///path, percent-encoded: URI("file", null, path, null) would give file:/path, which some apps refuse.
    val urls = paths.map { shellQuote("file://" + java.net.URI(null, null, it, null).toASCIIString()) }
    var used = false
    val cmd =
        FIELD_CODE.replace(execTemplate) { m ->
            when (m.value) {
                "%f" -> files.first().also { used = true }
                "%F" -> files.joinToString(" ").also { used = true }
                "%u" -> urls.first().also { used = true }
                "%U" -> urls.joinToString(" ").also { used = true }
                else -> ""
            }
        }.trim()
    return if (used) cmd else (listOf(cmd) + files).joinToString(" ")
}

/** Whether a window with Wayland [appId] belongs to this app: by desktop id, StartupWMClass, or reverse-DNS tail. */
fun LinuxApp.matches(appId: String): Boolean {
    val id = appId.lowercase()
    return this.id.lowercase() == id || wmClass?.lowercase() == id || this.id.substringAfterLast('.').lowercase() == id
}

private val FIELD_CODE = Regex("%[fFuUdDnNickvm]")

/**
 * Reads launchable entries from the guest's user and system application dirs.
 * A user entry replaces the system one with the same file name (XDG), so a
 * NoDisplay=true file in ~/.local/share/applications hides a package's entry.
 */
fun loadLinuxApps(proot: Proot): List<LinuxApp> {
    val dirs = listOf(proot.applications, File(proot.rootfs, "usr/share/applications"))
    return dirs
        .flatMap { it.listFiles { f -> f.extension == "desktop" }.orEmpty().toList() }
        .distinctBy { it.name }
        // An entry may be a link to an absolute guest path (AUR packages link from /opt), which the host can't open.
        .mapNotNull { runCatching { parseDesktopEntry(it) }.getOrNull() }
        .distinctBy { it.name }
        .sortedBy { it.name.lowercase() }
}

private fun parseDesktopEntry(file: File): LinuxApp? {
    var inEntry = false
    val fields = mutableMapOf<String, String>()
    file.forEachLine { raw ->
        val line = raw.trim()
        if (line.startsWith("[")) {
            inEntry = line == "[Desktop Entry]"
        } else if (inEntry && '=' in line) {
            val key = line.substringBefore('=')
            fields.putIfAbsent(key, line.substringAfter('='))
        }
    }
    if (fields["Type"] != "Application") return null
    if (fields["NoDisplay"] == "true" || fields["Hidden"] == "true") return null
    val name = fields["Name"] ?: return null
    val template = fields["Exec"] ?: return null
    val exec = template.replace(FIELD_CODE, "").trim()
    val mimes = fields["MimeType"]?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    return LinuxApp(name, exec, fields["Terminal"] == "true", file.nameWithoutExtension, fields["StartupWMClass"], fields["Icon"], mimes, template)
}

private val ICON_SIZES = listOf("256x256", "192x192", "128x128", "96x96", "72x72", "64x64", "48x48")

/**
 * Finds an icon file for [icon] in the guest: an absolute path, or a hicolor/pixmaps
 * lookup by name, largest PNG first, then SVG (many apps only ship hicolor/scalable).
 */
fun resolveLinuxIcon(
    rootfs: File,
    icon: String,
): File? {
    if (icon.startsWith("/")) return File(rootfs, icon.removePrefix("/")).takeIf { it.isFile && it.extension in ICON_TYPES }
    val candidates =
        ICON_SIZES.map { "usr/share/icons/hicolor/$it/apps/$icon.png" } +
            "usr/share/pixmaps/$icon.png" +
            "root/.local/share/icons/hicolor/256x256/apps/$icon.png" +
            "usr/share/icons/hicolor/scalable/apps/$icon.svg" +
            "usr/share/pixmaps/$icon.svg" +
            "root/.local/share/icons/hicolor/scalable/apps/$icon.svg"
    return candidates.map { File(rootfs, it) }.firstOrNull { it.isFile }
}

private val ICON_TYPES = setOf("png", "svg")
