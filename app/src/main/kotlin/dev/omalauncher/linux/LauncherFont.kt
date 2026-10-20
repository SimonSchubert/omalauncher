package dev.omalauncher.linux

import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
 * Android's. Main thread.
 */
class LauncherFont(
    private val proot: Proot,
    private val style: StyleStore,
) {
    private val cache = File(proot.filesDir, "launcher-font")

    /** Sets [LauncherStyle.font] from what [cache] remembers, without the guest; at start, before the first frame. */
    fun restore() {
        val family = style.style[Style.FONT]
        if (family == Style.SYSTEM_FONT) return
        val faces = cached(family) ?: return
        LauncherStyle.font = load(faces) ?: return
    }

    /** Sets [LauncherStyle.font] for [Style.FONT], asking the guest for the family's files unless [cache] has them. */
    suspend fun apply() {
        val family = style.style[Style.FONT]
        if (family == Style.SYSTEM_FONT) {
            LauncherStyle.font = FontFamily.Monospace
            return
        }
        val loaded =
            withContext(Dispatchers.IO) {
                val faces = cached(family) ?: find(family).also { save(family, it) }
                load(faces)
            }
        // The setting may have changed while the guest answered.
        if (style.style[Style.FONT] == family) LauncherStyle.font = loaded ?: FontFamily.Monospace
    }

    /** Back to Android's font, as Linux and its font files are deleted; the setting stays for when it's back. */
    fun forget() {
        LauncherStyle.font = FontFamily.Monospace
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
        return faces.takeIf { it.isNotEmpty() && it.all { face -> proot.hostFile(face.path).isFile } }
    }

    private fun save(
        family: String,
        faces: List<Face>,
    ) {
        runCatching { cache.writeText((listOf(family) + faces.map(Face::encode)).joinToString("\n", postfix = "\n")) }
            .onFailure { Log.w(TAG, "can't remember the font", it) }
    }

    /** [faces] as a family, leaving out files Android can't read; null if that's all of them. */
    private fun load(faces: List<Face>): FontFamily? {
        val fonts =
            faces.mapNotNull { face ->
                val file = proot.hostFile(face.path)
                val readable = runCatching { Typeface.Builder(file).build() != null }.getOrDefault(false)
                if (readable) Font(file, FontWeight(face.weight), if (face.italic) FontStyle.Italic else FontStyle.Normal) else null
            }
        return if (fonts.isEmpty()) null else FontFamily(fonts)
    }

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
