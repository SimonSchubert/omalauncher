package dev.omadroid.theme

/** The subset of Omarchy's template syntax that doesn't depend on Hyprland. */
object Templates {
    private val TOKEN = Regex("""\{\{\s*([^}]+?)\s*\}\}""")

    fun render(
        template: String,
        colors: Map<String, String>,
    ): String =
        TOKEN.replace(template) { m ->
            val parts = m.groupValues[1].split(Regex("\\s+"))
            value(parts, colors) ?: m.value
        }

    private fun value(
        parts: List<String>,
        colors: Map<String, String>,
    ): String? {
        if (parts.size == 1) {
            val key = parts[0]
            colors[key]?.let { return it }
            val base = key.substringBeforeLast('_')
            val hex = colors[base]?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: return null
            return when (key.substringAfterLast('_')) {
                "strip" -> hex.removePrefix("#")
                "rgb" -> ThemeColors.rgb(hex).joinToString(",")
                else -> null
            }
        }
        val fn = parts[0]
        if (fn !in setOf("mix", "mix_strip", "mix_rgb") || parts.size != 4) return null
        val a = colors[parts[1]] ?: return null
        val b = colors[parts[2]] ?: return null
        val amount = parts[3].removeSuffix("%").toDoubleOrNull()?.let { if (it > 1) it / 100 else it } ?: return null
        // A color that isn't #rrggbb (a hand-made theme) leaves the token as is.
        val mixed = runCatching { ThemeColors.mix(a, b, amount.coerceIn(0.0, 1.0)) }.getOrNull() ?: return null
        return when (fn) {
            "mix_strip" -> mixed.removePrefix("#")
            "mix_rgb" -> ThemeColors.rgb(mixed).joinToString(",")
            else -> mixed
        }
    }
}
