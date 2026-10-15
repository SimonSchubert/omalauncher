package dev.omalauncher.theme

import android.system.Os
import dev.omalauncher.linux.Proot
import dev.omalauncher.linux.TerminalFont
import dev.omalauncher.linux.run
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File
import kotlin.math.roundToInt

/**
 * What omarchy-theme-set would write inside the guest: the current theme's
 * rendered templates, plus configs for Omarchy's terminal apps that pick
 * them up. The configs are defaults the user may edit (see [Policy.Seeded]).
 */
class GuestThemeConfig(
    private val proot: Proot,
    private val omarchyDir: File,
) {
    private val home = proot.home
    private val templatesDir = File(omarchyDir, "default/themed")
    private val currentDir = File(home, ".local/state/omarchy/current")

    /**
     * Renders [theme] into current/theme, which foot, btop and the rest
     * include. A file the theme ships wins over the template's.
     */
    fun render(theme: OmarchyTheme) {
        val themeDir = File(currentDir, "theme")
        themeDir.deleteRecursively()
        themeDir.mkdirs()
        theme.guestFiles().forEach { (name, file) -> file.copyTo(File(themeDir, name)) }
        File(currentDir, "theme.name").writeText(theme.name + "\n")
        templatesDir.listFiles { f -> f.extension == "tpl" }.orEmpty().forEach { tpl ->
            val out = File(themeDir, tpl.nameWithoutExtension)
            val colors = if (tpl.name == "foot.ini.tpl") footColors(theme.colors) else theme.colors
            if (!out.exists()) out.writeText(Templates.render(tpl.readText(), colors))
        }
        symlink("${Proot.GUEST_HOME}/.local/state/omarchy/current/theme/btop.theme", File(home, ".config/btop/themes/current.theme"))
    }

    /** Mirrors Omarchy's ~/.local/state/omarchy/current/background symlink. */
    fun linkBackground(file: File) {
        proot.guestPath(file)?.let { symlink(it, File(currentDir, "background")) }
    }

    private fun symlink(
        target: String,
        link: File,
    ) {
        link.parentFile?.mkdirs()
        link.delete()
        runCatching { Os.symlink(target, link.path) }
    }

    /** Configs for Omarchy's terminal apps and GTK, the latter in [theme]'s colors. */
    fun managed(theme: OmarchyTheme?): List<Managed> {
        val files = ArrayList<Managed>()
        fun seed(
            path: String,
            content: () -> String,
        ) = files.add(Managed.text(File(home, path), Policy.Seeded, content = content))

        /** Omarchy's own config for an app, as its installer seeds it. */
        fun copy(
            from: String,
            to: String,
        ) {
            val src = File(omarchyDir, from)
            if (src.isFile) files.add(Managed(File(home, to), Policy.Seeded) { src.readBytes() })
        }

        seed(".config/foot/foot.ini") { FOOT_INI }
        // Omarchy's bash setup (aliases, starship, zoxide, fzf) reads everything from $OMARCHY_PATH.
        seed(".bashrc") { BASHRC }
        File(omarchyDir, "etc/fastfetch/config.jsonc").takeIf { it.isFile }?.let { src ->
            // In portrait the side-by-side logo leaves ~20 columns for the info; stack it on top.
            seed(".config/fastfetch/config.jsonc") { src.readText().replaceFirst("\"type\": \"file\",", "\"type\": \"file\",\n    \"position\": \"top\",") }
        }
        // btop rewrites its config on exit; from then on it's the user's.
        copy("config/btop/btop.conf", ".config/btop/btop.conf")
        files.add(Managed.text(File(proot.rootfs, "usr/local/bin/btop"), Policy.Owned, executable = true) { BTOP })
        copy("icon.txt", ".config/omarchy/branding/about.txt")
        copy("config/starship.toml", ".config/starship.toml")
        copy("config/lazygit/config.yml", ".config/lazygit/config.yml")
        copy("config/tmux/tmux.conf", ".config/tmux/tmux.conf")
        if (theme != null) {
            val dark = !theme.isLight
            val accent = theme.colors["accent"] ?: theme.colors["blue"]
            for (gtk in listOf("gtk-3.0", "gtk-4.0")) {
                seed(".config/$gtk/settings.ini") {
                    "[Settings]\ngtk-application-prefer-dark-theme=${if (dark) 1 else 0}\ngtk-theme-name=Adwaita${if (dark) "-dark" else ""}\n"
                }
                seed(".config/$gtk/gtk.css") {
                    "@define-color accent_color $accent;\n@define-color accent_bg_color $accent;\n@define-color theme_selected_bg_color $accent;\n"
                }
            }
        }
        return files
    }

    /** Live-recolors running foot terminals with OSC sequences, like omarchy-theme-set-foot. Blocking. */
    fun recolorTerminals(
        theme: OmarchyTheme,
        /** foot's background alpha (terminal.opacity); 1 leaves it opaque. */
        alpha: Double = 1.0,
    ) {
        val c = footColors(theme.colors)
        val percent = (alpha.coerceIn(0.0, 1.0) * 100).roundToInt()
        val osc =
            buildString {
                fun emit(
                    code: String,
                    key: String,
                ) = c[key]?.let { append("\u001b]$code;$it\u0007") }
                emit("10", "foreground")
                c["background"]?.let { append("\u001b]11;" + (if (percent < 100) "[$percent]" else "") + it + "\u0007") }
                emit("12", "cursor")
                emit("17", "selection_background")
                emit("19", "selection_foreground")
                for (i in 0..15) c["color$i"]?.let { append("\u001b]4;$i;$it\u0007") }
            }
        proot.hostFile("/tmp/omarchy-osc").writeText(osc)
        val script =
            "for p in \$(pgrep -x foot); do for c in \$(pgrep -P \$p); do " +
                "t=\$(readlink /proc/\$c/fd/1); case \$t in /dev/pts/*) cat /tmp/omarchy-osc > \$t;; esac; done; done; " +
                // Like omarchy-restart-btop: btop reloads its theme on SIGUSR2.
                "pkill -SIGUSR2 btop; true"
        runCatching { proot.run(script) }
    }

    private companion object {
        /**
         * Themes pick a selection a shade off the background, made for a mouse on a big screen;
         * on a phone it's barely visible. foot selects in the accent instead, as inverted text.
         */
        fun footColors(c: Map<String, String>): Map<String, String> {
            val accent = c["accent"] ?: c["blue"] ?: return c
            val background = c["background"] ?: return c
            return c + mapOf("selection_background" to accent, "selection_foreground" to background)
        }

        /**
         * Omarchy's foot.ini, sized for a phone: the font comes from [TerminalFont], which
         * follows the screen and Android's font size. Nerd glyphs come via fontconfig fallback.
         */
        val FOOT_INI =
            """
            [main]
            include=~/.local/state/omarchy/current/theme/foot.ini
            term=xterm-256color
            include=${TerminalFont.PATH}
            pad=8x8
            workers=0
            # Copy on select: on a phone a selection is a deliberate long press, and Android shows what was copied.
            selection-target=both

            [scrollback]
            lines=10000
            multiplier=7.0

            [cursor]
            style=block
            blink=no

            [key-bindings]
            clipboard-copy=Control+Insert Control+Shift+c XF86Copy
            primary-paste=none
            clipboard-paste=Shift+Insert Control+Shift+v XF86Paste
            """.trimIndent() + "\n"

        /**
         * btop's full layout needs 80 columns, more than a terminal on a phone in
         * portrait has at a readable size, and btop then draws nothing: it opens
         * with less instead. Its first preset, cpu and processes, needs 60; under
         * that only the process list fits (44), from a copy of the config.
         * /usr/local/bin precedes /usr/bin.
         */
        val BTOP =
            """
            #!/bin/sh
            # omalauncher: btop with fewer boxes in a narrow terminal (see GuestThemeConfig.kt)
            [ ${'$'}# -eq 0 ] || exec /usr/bin/btop "${'$'}@"
            cols=${'$'}(stty size 2>/dev/null | cut -d' ' -f2)
            cols=${'$'}{cols:-80}
            [ "${'$'}cols" -ge 80 ] && exec /usr/bin/btop
            [ "${'$'}cols" -ge 60 ] && exec /usr/bin/btop -p 1
            conf=${'$'}{XDG_CONFIG_HOME:-${'$'}HOME/.config}/btop/btop.conf
            narrow=/tmp/omalauncher-btop-narrow.conf
            { grep -v -e '^shown_boxes' -e '^presets' "${'$'}conf" 2>/dev/null; echo 'shown_boxes = "proc"'; echo 'presets = "proc:0:default"'; } > "${'$'}narrow"
            exec /usr/bin/btop -c "${'$'}narrow"
            """.trimIndent() + "\n"

        val BASHRC =
            """
            export OMARCHY_PATH=/root/.local/share/omarchy
            export EDITOR=omarchy-edit VISUAL=omarchy-edit
            [[ $- != *i* ]] && return
            [[ -f ${'$'}OMARCHY_PATH/default/bash/rc ]] && source "${'$'}OMARCHY_PATH/default/bash/rc"
            """.trimIndent() + "\n"
    }
}
