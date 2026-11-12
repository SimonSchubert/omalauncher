package dev.omalauncher.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import kotlin.math.roundToInt

/** The same framing for home, Android's live wallpaper and the glass over it. */
@Immutable
data class WallpaperFraming(
    val scroll: Boolean = true,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val zoom: Float = 1f,
    val fit: Boolean = false,
) {
    /** Which controls can change the visible image, including placement within fit's borders. */
    fun adjustments(bmpW: Int, bmpH: Int, w: Int, h: Int): Adjustments {
        fun pixels(framing: WallpaperFraming, offset: Float): Pair<IntRect, IntRect> {
            val p = framing.placement(bmpW.toFloat(), bmpH.toFloat(), w.toFloat(), h.toFloat(), offset)
            return p.sourcePixels(bmpW, bmpH) to p.destinationPixels(w, h)
        }
        val origin = pixels(copy(x = 0f, y = 0f), 0.5f)
        return Adjustments(
            horizontal = origin != pixels(copy(x = 1f, y = 0f), 0.5f),
            vertical = origin != pixels(copy(x = 0f, y = 1f), 0.5f),
            scrolling = pixels(this, 0f) != pixels(this, 1f),
        )
    }

    @Immutable
    data class Adjustments(val horizontal: Boolean = false, val vertical: Boolean = false, val scrolling: Boolean = false)

    @Immutable
    data class Placement(val source: Rect, val destination: Rect) {
        fun sourcePixels(w: Int, h: Int) = pixels(source, w, h)
        fun destinationPixels(w: Int, h: Int) = pixels(destination, w, h)

        private fun pixels(rect: Rect, w: Int, h: Int): IntRect {
            if (rect.isEmpty || w <= 0 || h <= 0) return IntRect.Zero
            val left = rect.left.roundToInt().coerceIn(0, w - 1)
            val top = rect.top.roundToInt().coerceIn(0, h - 1)
            return IntRect(left, top, rect.right.roundToInt().coerceIn(left + 1, w), rect.bottom.roundToInt().coerceIn(top + 1, h))
        }
    }

    /** Visible source and destination, preserving aspect ratio and clipping a zoomed image to the window. */
    fun placement(bmpW: Float, bmpH: Float, w: Float, h: Float, offset: Float): Placement {
        if (bmpW <= 0 || bmpH <= 0 || w <= 0 || h <= 0) return Placement(Rect.Zero, Rect.Zero)
        val base = if (fit) minOf(w / bmpW, h / bmpH) else maxOf(w / bmpW, h / bmpH)
        val scale = base * zoom.coerceAtLeast(1f)
        val width = bmpW * scale
        val height = bmpH * scale
        val overflow = (width - w).coerceAtLeast(0f)
        val travel = if (scroll) minOf(overflow, w / 2) else 0f
        val left = if (width > w) -((overflow - travel) * x.coerceIn(0f, 1f) + travel * offset.coerceIn(0f, 1f)) else (w - width) * x.coerceIn(0f, 1f)
        val top = (h - height) * y.coerceIn(0f, 1f)
        val dst = Rect(maxOf(0f, left), maxOf(0f, top), minOf(w, left + width), minOf(h, top + height))
        val src = Rect(
            ((dst.left - left) / scale).coerceIn(0f, bmpW),
            ((dst.top - top) / scale).coerceIn(0f, bmpH),
            ((dst.right - left) / scale).coerceIn(0f, bmpW),
            ((dst.bottom - top) / scale).coerceIn(0f, bmpH),
        )
        return Placement(src, dst)
    }

    /** Source pixels, kept for callers that only need the crop. */
    fun crop(bmpW: Float, bmpH: Float, w: Float, h: Float, offset: Float): Rect = placement(bmpW, bmpH, w, h, offset).source

    fun crop(bmpW: Int, bmpH: Int, w: Int, h: Int, offset: Float): IntRect =
        placement(bmpW.toFloat(), bmpH.toFloat(), w.toFloat(), h.toFloat(), offset).sourcePixels(bmpW, bmpH)
}
