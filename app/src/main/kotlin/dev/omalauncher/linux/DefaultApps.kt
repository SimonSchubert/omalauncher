package dev.omalauncher.linux

import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File

/** Exec of the Files entry; the launcher opens its own Files app for it (see OpenBridge's shim). */
const val FILES_COMMAND = "omarchy-files"

/** Exec of the Editor entry and Linux's $EDITOR; the launcher's own editor (see OpenBridge's shim). */
const val EDIT_COMMAND = "omarchy-edit"

private fun entry(
    name: String,
    exec: String,
    terminal: Boolean = true,
    icon: String? = null,
) = buildString {
    append("[Desktop Entry]\nType=Application\nName=$name\nExec=$exec\nTerminal=$terminal\n")
    if (icon != null) append("Icon=$icon\n")
}

/**
 * The drawer's small, touch-friendly set of Linux apps: all of them take taps and
 * scrolling. Keyed by desktop file id: a file in ~/.local/share/applications
 * overrides the package's entry of the same name, as XDG specifies.
 */
private val CURATED =
    mapOf(
        "foot" to entry("Terminal", "foot", terminal = false, icon = "foot"),
        "btop" to entry("btop", "btop", icon = "btop"),
        "omarchy-files" to entry("Files", FILES_COMMAND, terminal = false, icon = bundledIcon("files")),
        "micro" to entry("Editor", EDIT_COMMAND, terminal = false, icon = bundledIcon("editor")),
        "cliamp" to entry("Cliamp", "cliamp", icon = bundledIcon("cliamp")),
    )

/**
 * Entries packages bring along that make no sense on a phone: avahi's browsers (a
 * pulseaudio dependency), foot's server/client pair, and keyboard-only editors.
 * They stay installed; the drawer just doesn't list them.
 */
private val HIDDEN = listOf("avahi-discover", "bssh", "bvnc", "footclient", "foot-server", "vim", "nvim", "yazi")

/** The curated and hiding entries; ones the user edited are left alone. */
fun defaultApps(applications: File): List<Managed> {
    val hidden = HIDDEN.associateWith { "[Desktop Entry]\nType=Application\nName=$it\nNoDisplay=true\n" }
    return (CURATED + hidden).map { (id, content) -> Managed.text(File(applications, "$id.desktop"), Policy.Seeded) { content } }
}
