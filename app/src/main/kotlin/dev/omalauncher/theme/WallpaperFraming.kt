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
) {
    /** Which controls can change this crop; scrolling can already use all the spare width. */
    fun adjustments(
        bmpW: Int,
        bmpH: Int,
        w: Int,
        h: Int,
    ): Adjustments {
        val origin = copy(x = 0f, y = 0f).crop(bmpW, bmpH, w, h, 0.5f)
        return Adjustments(
            horizontal = origin.left != copy(x = 1f).crop(bmpW, bmpH, w, h, 0.5f).left,
            vertical = origin.top != copy(y = 1f).crop(bmpW, bmpH, w, h, 0.5f).top,
            scrolling = crop(bmpW, bmpH, w, h, 0f).left != crop(bmpW, bmpH, w, h, 1f).left,
        )
    }

    @Immutable
    data class Adjustments(
        val horizontal: Boolean = false,
        val vertical: Boolean = false,
        val scrolling: Boolean = false,
    )

    /** Source pixels that fill the window, without stretching or showing past the picture's edges. */
    fun crop(
        bmpW: Float,
        bmpH: Float,
        w: Float,
        h: Float,
        offset: Float,
    ): Rect {
        if (bmpW <= 0 || bmpH <= 0 || w <= 0 || h <= 0) return Rect.Zero
        val scale = maxOf(w / bmpW, h / bmpH) * zoom.coerceAtLeast(1f)
        val srcW = (w / scale).coerceAtMost(bmpW)
        val srcH = (h / scale).coerceAtMost(bmpH)
        val travel = if (scroll) minOf(bmpW - srcW, srcW / 2) else 0f
        val left = (bmpW - srcW - travel) * x.coerceIn(0f, 1f) + travel * offset.coerceIn(0f, 1f)
        val top = (bmpH - srcH) * y.coerceIn(0f, 1f)
        return Rect(left, top, left + srcW, top + srcH)
    }

    /** Integer pixels for the full-sized bitmap; glass uses [crop]'s floats on its small blurred copy. */
    fun crop(
        bmpW: Int,
        bmpH: Int,
        w: Int,
        h: Int,
        offset: Float,
    ): IntRect {
        val r = crop(bmpW.toFloat(), bmpH.toFloat(), w.toFloat(), h.toFloat(), offset)
        if (r.isEmpty) return IntRect.Zero
        val left = r.left.roundToInt().coerceIn(0, bmpW - 1)
        val top = r.top.roundToInt().coerceIn(0, bmpH - 1)
        return IntRect(left, top, r.right.roundToInt().coerceIn(left + 1, bmpW), r.bottom.roundToInt().coerceIn(top + 1, bmpH))
    }
}
