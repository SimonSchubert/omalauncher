package dev.omalauncher.theme

import java.io.File

/**
 * Parses an Omarchy `colors.toml` and resolves the derived names that
 * templates use, mirroring the cascade in omarchy-theme-color.
 */
object ThemeColors {
    private val LINE = Regex("""^\s*([A-Za-z0-9_]+)\s*=\s*"([^"]*)"""")

    fun load(file: File): Map<String, String> = resolve(parse(file.readText()))

    fun parse(text: String): Map<String, String> =
        text.lineSequence().mapNotNull { LINE.find(it)?.destructured?.let { (k, v) -> k to v } }.toMap()

    fun resolve(raw: Map<String, String>): Map<String, String> {
        val c = raw.toMutableMap()
        fun alias(
            key: String,
            from: String,
        ) {
            if (c[key].isNullOrEmpty()) c[from]?.let { c[key] = it }
        }
        fun default(
            key: String,
            value: () -> String?,
        ) {
            if (c[key].isNullOrEmpty()) value()?.let { c[key] = it }
        }

        default("background") { c["color0"] }
        default("foreground") { c["color7"] }
        // Legacy themes only define ANSI colorN.
        listOf(
            "red" to 1, "green" to 2, "yellow" to 3, "blue" to 4, "magenta" to 5, "cyan" to 6,
            "bright_red" to 9, "bright_green" to 10, "bright_yellow" to 11, "bright_blue" to 12,
            "bright_magenta" to 13, "bright_cyan" to 14,
        ).forEach { (key, n) -> alias(key, "color$n") }
        alias("magenta", "purple")
        alias("bright_magenta", "bright_purple")

        default("light_foreground") { c["color7"] ?: c["foreground"] }
        default("bright_foreground") { c["color15"] ?: c["foreground"] }
        c["cursor"] = c["bright_foreground"] ?: c["foreground"] ?: "#ffffff"
        default("lighter_background") { c["color0"] ?: c["background"] }
        default("dark_foreground") { c["color8"] ?: c["foreground"] }
        default("muted") { c["color8"] ?: c["dark_foreground"] }
        default("selection") { c["selection_background"] ?: c["color8"] ?: c["color0"] ?: c["background"] }
        default("selection_background") { c["selection"] }
        default("selection_foreground") { c["bright_foreground"] }
        default("brown") { c["orange"]?.let { mix(it, "#000000", 0.5) } }
        default("dark_background") { c["background"]?.let { mix(it, "#000000", 0.25) } }
        default("darker_background") { c["background"]?.let { mix(it, "#000000", 0.5) } }
        for (name in listOf("red", "yellow", "green", "cyan", "blue", "magenta")) {
            default("bright_$name") { c[name]?.let { mix(it, "#ffffff", 0.2) } }
        }
        alias("purple", "magenta")
        alias("bright_purple", "bright_magenta")
        listOf(
            "background", "red", "green", "yellow", "blue", "magenta", "cyan", "foreground",
            "muted", "bright_red", "bright_green", "bright_yellow", "bright_blue", "bright_magenta",
            "bright_cyan", "bright_foreground",
        ).forEachIndexed { n, key -> alias("color$n", key) }
        // theme_type is mode's older name: a theme may set either, and templates read both (Claude Code's base).
        alias("mode", "theme_type")
        default("mode") { if (luminance(c["background"] ?: "#000000") > 0.5) "light" else "dark" }
        c["theme_type"] = c.getValue("mode")
        return c
    }

    private val SECTION = Regex("""^\s*\[([^\]]*)]\s*$""")
    private val HEX_VALUE = Regex("""^(?:["'](?:0[xX]|#)?([0-9a-fA-F]{6})["'](?:\s*#.*)?|(?:0[xX])?([0-9a-fA-F]{6})(?:\s*#.*)?)$""")

    /**
     * The palette of a theme older than colors.toml, from its alacritty.toml,
     * as omarchy-theme-colors-from-alacritty reads it. Null without all eight
     * normal colors.
     */
    fun fromAlacritty(text: String): Map<String, String>? {
        val direct = HashMap<String, String>()
        val dotted = HashMap<String, String>()
        var section = ""
        for (line in text.lineSequence()) {
            if (line.trimStart().startsWith("[")) {
                section = SECTION.find(line)?.groupValues?.get(1) ?: ""
                continue
            }
            if (section.isEmpty()) continue
            val eq = line.indexOf('=').takeIf { it > 0 } ?: continue
            val key = line.substring(0, eq).trim()
            if (key.isEmpty() || key.startsWith("#")) continue
            val m = HEX_VALUE.find(line.substring(eq + 1).trim()) ?: continue
            val hex = "#" + (m.groupValues[1].ifEmpty { m.groupValues[2] }).lowercase()
            // `normal.black = …` under [colors] is colors.normal.black, below the section form.
            if (section == "colors" && '.' in key) dotted.putIfAbsent("$section.$key", hex) else direct.putIfAbsent("$section.$key", hex)
        }
        val all = dotted + direct
        val names = listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white")
        val normal = names.map { all["colors.normal.$it"] ?: return null }
        val bright = names.mapIndexed { i, n -> all["colors.bright.$n"] ?: normal[i] }
        val background = all["colors.primary.background"] ?: normal[0]
        val foreground = all["colors.primary.foreground"] ?: normal[7]
        val out = linkedMapOf("accent" to normal[4], "selection" to (all["colors.selection.background"] ?: foreground), "background" to background, "foreground" to foreground)
        val colors = listOf(background) + normal.subList(1, 7) + foreground + bright
        colors.forEachIndexed { i, c -> out["color$i"] = c }
        return out
    }

    fun mix(
        a: String,
        b: String,
        amount: Double,
    ): String {
        val x = rgb(a)
        val y = rgb(b)
        val out = IntArray(3) { i -> (x[i] * (1 - amount) + y[i] * amount + 0.5).toInt() }
        return "#%02x%02x%02x".format(out[0], out[1], out[2])
    }

    fun rgb(hex: String): IntArray {
        val h = hex.removePrefix("#")
        return IntArray(3) { i -> h.substring(i * 2, i * 2 + 2).toInt(16) }
    }

    fun luminance(hex: String): Double = rgb(hex).let { (0.2126 * it[0] + 0.7152 * it[1] + 0.0722 * it[2]) / 255 }
}
