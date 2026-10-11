package dev.omalauncher.linux

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The size foot draws text at. foot.ini includes [PATH] rather than fixing a
 * size, so every foot (opened from the launcher or from a shell) follows the
 * screen's density and Android's font size, as the launcher's own text does,
 * in the user's [text] size: more columns or bigger letters. Main thread.
 */
class TerminalFont(
    context: Context,
    private val proot: Proot,
) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var text by mutableStateOf(TerminalText.of(prefs.getString(TEXT, null)))
        private set

    /** The point size new terminals open at. */
    var size by mutableDoubleStateOf(DEFAULT_SIZE)
        private set

    /** foot's wl_output scale, from the last [update]. */
    var scale = 1
        private set

    private var density = 0f
    private var fontScale = 1f

    /** Whether the file holds [size]; a write can fail before Linux is unpacked. */
    private var written = false

    /**
     * Android's display metrics and font size; [scale] is the integer
     * wl_output scale foot multiplies point sizes by. Terminals opened from
     * now on get the new size.
     */
    fun update(
        density: Float,
        fontScale: Float,
        scale: Int,
    ) {
        this.density = density
        this.fontScale = fontScale
        this.scale = scale
        write()
    }

    fun select(text: TerminalText) {
        this.text = text
        prefs.edit().putString(TEXT, text.id).apply()
        write()
    }

    /** The columns a terminal [widthPx] wide gets at [size]. */
    fun columns(widthPx: Int) = columns(widthPx, scale, size)

    private fun write() {
        if (density == 0f) return
        val next = pointSize(density, fontScale, scale, text.em)
        if (next == size && written) return
        size = next
        written = true
        runCatching {
            val file = File(proot.home, PATH.removePrefix("~/"))
            file.parentFile?.mkdirs()
            file.writeText("[main]\nfont=$FAMILY:size=${format(size)}\n")
        }.onFailure {
            written = false
            Log.w(TAG, "can't write the terminal font", it)
        }
    }

    companion object {
        /** foot.ini's include; the launcher owns this file and rewrites it. */
        const val PATH = "~/.local/state/omalauncher/foot-font.ini"

        /** The family foot.ini's font always was; an override (`-o main.font=`) must name it too. */
        const val FAMILY = "JetBrains Mono"

        /** foot's pixels per point at scale 1 (96 dpi, without dpi-aware). */
        private const val PX_PER_PT = 96f / 72f

        /** JetBrains Mono's advance, in ems. */
        private const val ADVANCE = 0.6f

        /** foot.ini's pad, in logical pixels on each side. */
        private const val PAD = 8

        private const val DEFAULT_SIZE = 8.0
        private const val MIN_SIZE = 4.0
        private const val MAX_SIZE = 40.0
        private const val TEXT = "terminal_text"
        private const val TAG = "terminal-font"

        /**
         * The point size that draws an em of [em] dp, times [fontScale], in a
         * foot at integer [scale] on a screen of [density]: foot multiplies
         * points by the scale, which rounds the density down (2.625 -> 2).
         * In half points, as foot.ini sizes go.
         */
        fun pointSize(
            density: Float,
            fontScale: Float,
            scale: Int,
            em: Float = TerminalText.Default.em,
        ): Double = (halves(em * fontScale * density / (scale * PX_PER_PT))).coerceIn(MIN_SIZE, MAX_SIZE)

        /** The columns across [widthPx] at [size]: foot rounds each cell to whole pixels. */
        fun columns(
            widthPx: Int,
            scale: Int,
            size: Double,
        ): Int {
            val cell = (ADVANCE * size * PX_PER_PT * scale).roundToInt().coerceAtLeast(1)
            return (widthPx - 2 * PAD * scale) / cell
        }

        /** The largest point size, at most [size], at which [columns] fit across [widthPx] at [scale]. */
        fun fitting(
            columns: Int,
            widthPx: Int,
            scale: Int,
            size: Double,
        ): Double {
            val cellPerPt = ADVANCE * PX_PER_PT * scale
            // One column spare, for foot rounding each cell to whole pixels.
            val fit = (widthPx - 2 * PAD * scale) / (cellPerPt * (columns + 1)).toDouble()
            return minOf(size, floor(fit * 2.0) / 2).coerceAtLeast(MIN_SIZE)
        }

        fun format(size: Double): String = String.format(Locale.ROOT, "%.1f", size)

        private fun halves(pt: Float) = (pt * 2).roundToInt() / 2.0
    }
}

/** Terminal text sizes, as an em in dp at Android's default font size. */
enum class TerminalText(
    /** Saved in preferences: never rename. */
    val id: String,
    val label: String,
    val em: Float,
) {
    Smaller("smaller", "smaller", 10f),

    /** The launcher's 12sp minimum: ~55 columns on a phone in portrait. */
    Default("default", "default", 12f),
    Larger("larger", "larger", 14f),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Default
    }
}
