package dev.omalauncher.linux

import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontStyle
import android.util.Log
import androidx.compose.ui.text.font.FontFamily
import dev.omalauncher.R
import dev.omalauncher.theme.Style
import dev.omalauncher.theme.StyleStore
import dev.omalauncher.ui.LauncherStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * The font the launcher's own text and widgets draw in: Android's monospace
 * for [Style.FONT]'s "system", or one of the guest's monospace families,
 * loaded from its files in the rootfs. The files found are remembered in
 * [cache], so the next start draws in it from the first frame without asking
 * fontconfig again. A family Linux doesn't have (or no Linux) falls back to
 * Android's. Either way, symbols the font lacks come from noto_symbols.ttf
 * and nerd_symbols.ttf before Android's own fallback, so an agent's ⏵⏵ or ⏺
 * isn't a box. Main thread.
 */
class LauncherFont(
    private val proot: Proot,
    private val style: StyleStore,
    private val resources: Resources,
) {
    private val cache = File(proot.filesDir, "launcher-font")

    /** Android's monospace with the symbols; Android's alone if its file isn't where AOSP keeps it. */
    private val system: FontFamily by lazy {
        SYSTEM_MONO.takeIf { File(it).isFile }?.let { withSymbols(listOf(Face(400, false, it)), guestPaths = false) } ?: FontFamily.Monospace
    }

    /** The symbol fonts behind every family, read once: the same for all of them. */
    private val symbols by lazy { listOf(single(R.font.noto_symbols), single(R.font.nerd_symbols)) }

    /**
     * The guest faces [LauncherStyle.font] is, null for [system]: applying the same ones again keeps it, since a new
     * font lays out all text anew.
     */
    private var shown: List<Face>? = null

    /** Sets [LauncherStyle.font] from what [cache] remembers, without the guest; at start, before the first frame. */
    fun restore() {
        val family = style.style[Style.FONT]
        show(if (family == Style.SYSTEM_FONT) null else cached(family), null)
    }

    /** Sets [LauncherStyle.font] for [Style.FONT], asking the guest for the family's files unless [cache] has them. */
    suspend fun apply() {
        val family = style.style[Style.FONT]
        if (family == Style.SYSTEM_FONT) return show(null, null)
        val faces = withContext(Dispatchers.IO) { cached(family) ?: find(family).also { save(family, it) } }
        if (faces == shown) return
        val font = withContext(Dispatchers.IO) { load(faces) }
        // The setting may have changed while the guest answered.
        if (style.style[Style.FONT] == family) show(faces, font)
    }

    /** Shows [faces], loaded as [font] or here when null; [system] without them or when they don't load. */
    private fun show(
        faces: List<Face>?,
        font: FontFamily?,
    ) {
        val built = font ?: faces?.let(::load)
        LauncherStyle.font = built ?: system
        shown = faces.takeIf { built != null }
    }

    /** Back to Android's font, as Linux and its font files are deleted; the setting stays for when it's back. */
    fun forget() {
        show(null, null)
        cache.delete()
        loaded.clear()
    }

    /** What [family] loaded, so going back to one is instant. */
    private val loaded = HashMap<String, FontFamily?>()

    /**
     * Any of the guest's families, as the terminal preview draws in the terminal's;
     * null without Linux or the family, or if Android can't read its files.
     */
    suspend fun family(name: String): FontFamily? {
        if (name in loaded) return loaded[name]
        return withContext(Dispatchers.IO) { load(cached(name) ?: find(name)) }.also { loaded[name] = it }
    }

    /** The faces [family] has in the guest, as fontconfig lists them; empty without Linux or the family. */
    private fun find(family: String): List<Face> {
        val faces = mutableListOf<Face>()
        runCatching { proot.run("fc-list -f '%{weight}\\t%{slant}\\t%{file}\\n' " + shellQuote(":spacing=100:family=" + escape(family))) { line -> parse(line)?.let(faces::addAll) } }
            .onFailure { Log.w(TAG, "can't list $family's files", it) }
        return faces.distinctBy { it.weight to it.italic }
    }

    /** What [cache] holds for [family], if every file it names is still there. */
    private fun cached(family: String): List<Face>? {
        val lines = runCatching { cache.readLines() }.getOrNull() ?: return null
        if (lines.firstOrNull() != family) return null
        val faces = lines.drop(1).mapNotNull(Face::decode)
        return faces.takeIf { it.isNotEmpty() && it.all { face -> proot.guestFile(face.path)?.isFile == true } }
    }

    private fun save(
        family: String,
        faces: List<Face>,
    ) {
        runCatching { cache.writeText((listOf(family) + faces.map(Face::encode)).joinToString("\n", postfix = "\n")) }
            .onFailure { Log.w(TAG, "can't remember the font", it) }
    }

    /** [faces] (guest files) as a family, leaving out files Android can't read; null if that's all of them. */
    private fun load(faces: List<Face>): FontFamily? = withSymbols(faces, guestPaths = true)

    /**
     * [faces] with noto_symbols.ttf and Nerd Font's icons behind them, then Android's fallback;
     * [guestPaths] when their paths are the guest's. noto_symbols.ttf is Noto Sans Symbols 2's
     * Miscellaneous Technical and Dingbats (U+2300–23FF, U+2700–27BF) without the characters
     * that default to emoji, which stay color, and with Android monospace's ascent and descent
     * (928/-244 of 1000), so a line with one is no taller; made with fontTools; SIL OFL 1.1. Compose's FontFamily picks a face by weight
     * and never falls back per character, so it's one Android Typeface with fallbacks.
     */
    private fun withSymbols(
        faces: List<Face>,
        guestPaths: Boolean,
    ): FontFamily? {
        val fonts =
            faces.mapNotNull { face ->
                // Paths fontconfig in the guest gave, so read as the guest sees them.
                val file = (if (guestPaths) proot.guestFile(face.path) else File(face.path)) ?: return@mapNotNull null
                // A variable font's file is every weight; the axis picks this one, as Compose's Font(file) does.
                runCatching {
                    Font.Builder(file)
                        .setWeight(face.weight)
                        .setSlant(if (face.italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT)
                        .setFontVariationSettings("'wght' ${face.weight}")
                        .build()
                }.getOrNull()
            }
        if (fonts.isEmpty()) return null
        return runCatching {
            val family = android.graphics.fonts.FontFamily.Builder(fonts.first()).apply { fonts.drop(1).forEach(::addFont) }.build()
            val typeface =
                Typeface.CustomFallbackBuilder(family)
                    .apply { symbols.forEach(::addCustomFallback) }
                    .setSystemFallback("monospace")
                    .build()
            FontFamily(androidx.compose.ui.text.font.Typeface(typeface))
        }.onFailure { Log.w(TAG, "can't build the font", it) }.getOrNull()
    }

    private fun single(font: Int) = android.graphics.fonts.FontFamily.Builder(Font.Builder(resources, font).build()).build()

    /** One weight and slant of a family, in [path] (a guest path); a variable font's file serves several. */
    data class Face(
        val weight: Int,
        val italic: Boolean,
        val path: String,
    ) {
        fun encode() = "$weight\t$italic\t$path"

        companion object {
            fun decode(line: String): Face? {
                val (weight, italic, path) = line.split('\t', limit = 3).takeIf { it.size == 3 } ?: return null
                return Face(weight.toIntOrNull() ?: return null, italic.toBooleanStrict(), path)
            }
        }
    }

    companion object {
        private const val TAG = "launcher-font"

        /** The file AOSP's fonts.xml names for "monospace". */
        private const val SYSTEM_MONO = "/system/fonts/DroidSansMono.ttf"

        /** fontconfig's weights and the CSS weights they are, thin to black. */
        private val WEIGHTS = listOf(0 to 100, 40 to 200, 50 to 300, 80 to 400, 100 to 500, 180 to 600, 200 to 700, 205 to 800, 210 to 900)

        /** Android reads TrueType and OpenType files; fontconfig also lists bitmap fonts. */
        private val READABLE = Regex("\\.(ttf|otf|ttc)$", RegexOption.IGNORE_CASE)

        /**
         * The faces one line of fc-list gives: `80\t0\t/usr/share/fonts/a.ttf`, or
         * for a variable font `[0 210]\t0\t...`, every weight in its range.
         */
        fun parse(line: String): List<Face>? {
            val (weight, slant, path) = line.split('\t', limit = 3).takeIf { it.size == 3 } ?: return null
            if (!READABLE.containsMatchIn(path)) return null
            val italic = (range(slant)?.first ?: return null) > 0
            val (low, high) = range(weight) ?: return null
            val weights =
                if (low == high) {
                    listOf(WEIGHTS.minBy { abs(it.first - low) }.second)
                } else {
                    WEIGHTS.filter { it.first in low..high }.map { it.second }
                }
            return weights.map { Face(it, italic, path) }
        }

        /** `80` or `[0 210]` as a range. */
        private fun range(value: String): Pair<Int, Int>? {
            val numbers = value.trim('[', ']', ' ').split(' ').map { it.toDoubleOrNull()?.toInt() ?: return null }
            return numbers.firstOrNull()?.let { it to numbers.last() }
        }

        /** fontconfig's pattern syntax gives `\`, `-`, `:` and `,` meanings; a family name keeps them literal. */
        fun escape(family: String) = family.replace(Regex("[\\\\:,\\-]")) { "\\" + it.value }
    }
}
