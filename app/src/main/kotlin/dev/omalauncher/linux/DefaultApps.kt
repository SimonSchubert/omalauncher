package dev.omalauncher.linux

import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File

/** Exec of the Files entry; the launcher opens its own Files app for it (see OpenBridge's shim). */
const val FILES_COMMAND = "omarchy-files"

/** Exec of the Voice entry; the launcher opens its voice screen for it (see OpenBridge's shim). */
const val VOICE_COMMAND = "omarchy-voice"

/** Exec of the Terminal entry: foot, or the built-in terminal where style.toml picks it (see Runtime.launch). */
const val TERMINAL_COMMAND = "foot"

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
        "foot" to entry("Terminal", TERMINAL_COMMAND, terminal = false, icon = "foot"),
        "btop" to entry("btop", "btop", icon = "btop"),
        "omarchy-files" to entry("Files", FILES_COMMAND, terminal = false, icon = bundledIcon("files")),
        "micro" to entry("Editor", EDIT_COMMAND, terminal = false, icon = bundledIcon("editor")),
        "omarchy-voice" to entry("Voice", VOICE_COMMAND, terminal = false, icon = bundledIcon("voice")),
        "cliamp" to entry("Cliamp", "cliamp", icon = bundledIcon("cliamp")),
    )

/**
 * Entries packages bring along that make no sense on a phone: avahi's browsers (a
 * pulseaudio dependency), foot's server/client pair, and keyboard-only editors.
 * They stay installed; the drawer just doesn't list them.
 */
private val HIDDEN = listOf("avahi-discover", "bssh", "bvnc", "footclient", "foot-server", "vim", "nvim", "yazi")

/**
 * Chromium and VS Code refuse to start as root without these, and the guest only has root: tapping their
 * icon did nothing. Both read a flags file: Arch's chromium launcher, and the `code` wrapper of the AUR's
 * visual-studio-code-bin (Arch Linux ARM packages no VS Code). VS Code also wants its data directory
 * named, so it gets the one it uses anyway.
 */
private val ROOT_FLAGS =
    mapOf(
        "chromium-flags.conf" to "--no-sandbox\n",
        "code-flags.conf" to "--no-sandbox\n--user-data-dir=${Proot.GUEST_HOME}/.config/Code\n",
    )

/** The flags files of [ROOT_FLAGS] in [home]'s ~/.config; the user's own flags go into the same files. */
fun rootFlags(home: File): List<Managed> = ROOT_FLAGS.map { (name, content) -> Managed.text(File(home, ".config/$name"), Policy.Seeded) { content } }

/** The curated and hiding entries; ones the user edited are left alone. */
fun defaultApps(applications: File): List<Managed> {
    val hidden = HIDDEN.associateWith { "[Desktop Entry]\nType=Application\nName=$it\nNoDisplay=true\n" }
    return (CURATED + hidden).map { (id, content) -> Managed.text(File(applications, "$id.desktop"), Policy.Seeded) { content } }
}
