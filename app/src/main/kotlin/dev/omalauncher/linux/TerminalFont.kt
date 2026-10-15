package dev.omalauncher.linux

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.theme.Style
import dev.omalauncher.theme.StyleStore
import dev.omalauncher.wayland.XdgToplevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The font foot draws text in. foot.ini includes [PATH] rather than fixing
 * one, so every foot (opened from the launcher or from a shell) gets the
 * user's [family], at a size that follows the screen's density and Android's
 * font size, as the launcher's own text does, in the user's [text] size: more
 * columns or bigger letters. foot reads it as it starts; one already open
 * catches up on the size when it's shown again, with its own zoom keys
 * ([catchUp]), but keeps its family. Main thread.
 */
class TerminalFont(
    private val proot: Proot,
    /** Where [family] and [em] are saved; Runtime calls [write] when they change. */
    private val style: StyleStore,
) {
    val family get() = style.style[Style.TERMINAL_FONT]

    /** The text size: an em in dp, before Android's font size; [TerminalText] has the Style menu's. */
    val em get() = style.style[Style.TERMINAL_TEXT].toFloat()

    /** The guest's monospace families, [family] among them; just it until [refreshFamilies]. */
    var families by mutableStateOf(listOf(family))
        private set

    /** The point size new terminals open at. */
    var size by mutableDoubleStateOf(DEFAULT_SIZE)
        private set

    /** foot's wl_output scale, from the last [update]. */
    private var scale = 1

    private var density = 0f
    private var fontScale = 1f

    /** The screen's width in pixels, from the last [update]. */
    private var widthPx = 0

    /** App ids of terminals opened at a size of their own (btop), which the text size doesn't change. */
    private val ownSize = HashSet<String>()

    /** The size each open terminal that follows the text size is at; see [catchUp]. */
    private val shown = WeakHashMap<XdgToplevel, Double>()

    /** What the file holds; null until written, as a write can fail before Linux is unpacked. */
    private var written: String? = null

    /**
     * Android's display metrics and font size; [scale] is the integer
     * wl_output scale foot multiplies point sizes by.
     */
    fun update(
        density: Float,
        fontScale: Float,
        scale: Int,
        widthPx: Int,
    ) {
        this.density = density
        this.fontScale = fontScale
        this.scale = scale
        this.widthPx = widthPx
        write()
    }

    /** Lists the guest's monospace fonts into [families], as omarchy-font-list does; one installed with pacman shows up here. */
    suspend fun refreshFamilies() {
        val found =
            withContext(Dispatchers.IO) {
                sortedSetOf<String>().also { found ->
                    runCatching { proot.run(LIST_FAMILIES) { it.trim().takeIf(::isTextFont)?.let(found::add) } }
                        .onFailure { Log.w(TAG, "can't list fonts", it) }
                }
            }
        families = (found + family).sorted()
    }

    /**
     * foot's `-o` option for [command]'s terminal [appId], or empty for foot.ini's
     * font. btop, opened as an app, gets the size its full layout's 80 columns
     * need where the usual size leaves fewer (a phone in portrait); it's glanced
     * at more than read, and keeps that size whatever the text size does.
     */
    fun optionFor(
        appId: String,
        command: String?,
    ): String {
        val fit = if (command == "btop") fitting(BTOP_COLUMNS, widthPx, scale, size) else size
        if (fit >= size) {
            ownSize -= appId
            return ""
        }
        ownSize += appId
        return " -o " + shellQuote("main.font=$family:size=${format(fit)}")
    }

    /** Whether [appId]'s terminal was opened at a size of its own by [optionFor]. */
    fun hasOwnSize(appId: String) = appId in ownSize

    /** A terminal that takes its size from foot.ini opened, at the current [size]. */
    fun track(window: XdgToplevel) {
        shown[window] = size
    }

    /**
     * A tracked terminal is shown again: the steps of foot's zoom keys that
     * catch it up on a text size changed meanwhile, counted as taken.
     */
    fun catchUp(window: XdgToplevel): Int {
        val at = shown[window] ?: return 0
        val steps = ((size - at) / STEP).roundToInt()
        shown[window] = at + steps * STEP
        return steps
    }

    /** The columns a terminal [widthPx] wide gets at [size]. */
    fun columns(widthPx: Int) = columns(widthPx, scale, size)

    /**
     * Writes foot-font.ini if it changed or is gone: Linux removed and
     * installed again in the same process starts without it.
     */
    fun write() {
        if (density == 0f) return
        size = pointSize(density, fontScale, scale, em)
        val alpha = String.format(Locale.ROOT, "%.2f", style.style[Style.TERMINAL_OPACITY])
        val content = "[main]\nfont=$family:size=${format(size)}\n\n[colors-dark]\nalpha=$alpha\n\n[colors-light]\nalpha=$alpha\n"
        val file = File(proot.home, PATH.removePrefix("~/"))
        if (content == written && file.isFile) return
        written = content
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
        }.onFailure {
            written = null
            Log.w(TAG, "can't write the terminal font", it)
        }
    }

    companion object {
        /** foot.ini's include; the launcher owns this file and rewrites it. */
        const val PATH = "~/.local/state/omalauncher/foot-font.ini"

        /** omarchy-font-list's: one family per monospace font file. */
        private const val LIST_FAMILIES = "fc-list :spacing=100 -f '%{family[0]}\\n'"

        /** foot's font-size-adjustment: what its Ctrl+= and Ctrl+- change the size by, in points. */
        const val STEP = 0.5

        /** foot's pixels per point at scale 1 (96 dpi, without dpi-aware). */
        private const val PX_PER_PT = 96f / 72f

        /** JetBrains Mono's advance, in ems. */
        private const val ADVANCE = 0.6f

        /** foot.ini's pad, in logical pixels on each side. */
        private const val PAD = 8

        /** What btop's full layout needs. */
        private const val BTOP_COLUMNS = 80

        private const val DEFAULT_SIZE = 8.0
        private const val MIN_SIZE = 4.0
        private const val MAX_SIZE = 40.0
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

        /** Not emoji or icon-only fonts (Symbols Nerd Font, which foot falls back to for glyphs). */
        fun isTextFont(family: String) = family.isNotEmpty() && !Regex("emoji|signwriting|omarchy|symbols", RegexOption.IGNORE_CASE).containsMatchIn(family)

        fun format(size: Double): String = String.format(Locale.ROOT, "%.1f", size)

        private fun halves(pt: Float) = (pt * 2).roundToInt() / 2.0
    }
}

/** The Style menu's terminal text sizes, as an em in dp at Android's default font size. */
enum class TerminalText(
    /** What launchers before style.toml saved. */
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
        /** From what launchers before style.toml saved. */
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Default
    }
}
