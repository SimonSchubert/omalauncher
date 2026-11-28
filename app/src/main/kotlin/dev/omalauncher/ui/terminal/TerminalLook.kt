package dev.omalauncher.ui.terminal

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.util.Log
import dev.omalauncher.linux.Proot
import dev.omalauncher.linux.run
import dev.omalauncher.linux.shellQuote
import java.io.File

/**
 * The faces a built-in terminal draws text in: the guest's font [family] as foot finds it with fontconfig, its bold
 * and italic files where it has them, then the first fonts of fontconfig's fallback list for it (where foot finds
 * Claude Code's ⏵ and Nerd Font icons), then Android's own (emoji, scripts the guest lacks). [Fallback] where Linux
 * has no fonts.
 */
class TerminalFaces(
    val family: String,
    val regular: Typeface,
    val bold: Typeface,
    val italic: Typeface,
    val boldItalic: Typeface,
) {
    fun of(
        bold: Boolean,
        italic: Boolean,
    ) = when {
        bold && italic -> boldItalic
        bold -> this.bold
        italic -> this.italic
        else -> regular
    }

    companion object {
        private const val TAG = "terminal-faces"

        val Fallback =
            TerminalFaces(
                "monospace",
                Typeface.MONOSPACE,
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
                Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC),
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC),
            )

        /** Fonts after the family's own that a glyph is looked for in, before Android's. */
        private const val FALLBACKS = 12

        /** What fontconfig picks for each style of the family, then its fallback list, one file per line. */
        private fun script(family: String): String {
            val f = shellQuote(family)
            return "for s in '' ':bold' ':italic' ':bold:italic'; do fc-match -f '%{file}\\n' $f\"\$s\"; done; " +
                "fc-match -s -f '%{file}\\n' $f"
        }

        /** [family]'s faces from the guest; blocks on proot, so not on the main thread. [Fallback] if it fails. */
        fun load(
            proot: Proot,
            family: String,
        ): TerminalFaces {
            val files = ArrayList<String>()
            runCatching { proot.run(script(family)) { line -> files += line.trim() } }.onFailure { Log.w(TAG, "can't ask fontconfig", it) }
            if (files.size < 4) return Fallback
            val (regular, bold, italic, boldItalic) = files.take(4).map { it.takeIf(String::isNotEmpty)?.let(proot::hostFile) }
            val base = regular?.let(::font) ?: return Fallback
            val own = setOf(regular, bold, italic, boldItalic)
            val fallbacks =
                files
                    .drop(4)
                    .asSequence()
                    .filter { it.isNotEmpty() }
                    .map(proot::hostFile)
                    .filter { it !in own }
                    .distinct()
                    .take(FALLBACKS)
                    .mapNotNull { file -> font(file)?.let { FontFamily.Builder(it).build() } }
                    .toList()
            fun face(
                file: File?,
                weight: Int,
                slant: Int,
            ): Typeface {
                val style = FontStyle(weight, slant)
                // A style the family has no file for: the regular one, which Android then makes bold or slanted itself.
                val own = file?.takeIf { it != regular }?.let { font(it, weight, slant) }
                val fonts = FontFamily.Builder(base)
                if (own != null) runCatching { fonts.addFont(own) }
                return runCatching {
                    val builder = Typeface.CustomFallbackBuilder(fonts.build()).setStyle(style).setSystemFallback("monospace")
                    fallbacks.forEach(builder::addCustomFallback)
                    builder.build()
                }.getOrElse {
                    Log.w(TAG, "can't build $family", it)
                    Typeface.MONOSPACE
                }
            }
            return TerminalFaces(
                family,
                face(null, FontStyle.FONT_WEIGHT_NORMAL, FontStyle.FONT_SLANT_UPRIGHT),
                face(bold, FontStyle.FONT_WEIGHT_BOLD, FontStyle.FONT_SLANT_UPRIGHT),
                face(italic, FontStyle.FONT_WEIGHT_NORMAL, FontStyle.FONT_SLANT_ITALIC),
                face(boldItalic, FontStyle.FONT_WEIGHT_BOLD, FontStyle.FONT_SLANT_ITALIC),
            )
        }

        private fun font(
            file: File,
            weight: Int? = null,
            slant: Int? = null,
        ): Font? =
            runCatching {
                Font.Builder(file).apply {
                    weight?.let(::setWeight)
                    slant?.let(::setSlant)
                }.build()
            }.onFailure { Log.w(TAG, "can't read $file", it) }.getOrNull()
    }
}

/**
 * A terminal's colours, as foot.ini gets them from the Omarchy theme: text and background, the cursor (and the text
 * under it), the 16 ANSI colours, and the selection, in the accent as foot's (GuestThemeConfig's footColors).
 * All ARGB.
 */
class TerminalColors(
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val cursorText: Int,
    val palette: IntArray,
    val selectionBackground: Int,
    val selectionForeground: Int,
) {
    override fun equals(other: Any?) =
        other is TerminalColors && foreground == other.foreground && background == other.background && cursor == other.cursor &&
            cursorText == other.cursorText && palette.contentEquals(other.palette) &&
            selectionBackground == other.selectionBackground && selectionForeground == other.selectionForeground

    override fun hashCode() = palette.contentHashCode() * 31 + foreground * 7 + background

    companion object {
        /** xterm's 16, where a theme leaves one out. */
        private val XTERM =
            intArrayOf(
                0x000000, 0xcd0000, 0x00cd00, 0xcdcd00, 0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
                0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00, 0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff,
            )

        /** From an Omarchy theme's resolved colours ([dev.omalauncher.theme.ThemeColors]): "#rrggbb" by name. */
        fun from(theme: Map<String, String>): TerminalColors {
            fun color(name: String) = theme[name]?.let(::parse)
            val palette = IntArray(16) { color("color$it") ?: (0xff000000.toInt() or XTERM[it]) }
            val fg = color("foreground") ?: palette[7]
            val bg = color("background") ?: palette[0]
            val accent = color("accent") ?: color("blue") ?: fg
            return TerminalColors(fg, bg, color("cursor") ?: fg, bg, palette, accent, bg)
        }

        fun parse(hex: String): Int? {
            val h = hex.trim().removePrefix("#").removePrefix("0x")
            if (h.length != 6) return null
            return h.toLongOrNull(16)?.let { (0xff000000 or it).toInt() }
        }
    }
}
