package dev.omalauncher.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * Frosted glass, like Hyprland's blur behind Omarchy's translucent windows:
 * home's tiles show a blurred slice of the wallpaper under their glass. One
 * small blurred copy of the wallpaper serves every tile; each draws the part
 * that lines up with the wallpaper behind it, its slide between pages included.
 */
object Backdrop {
    /** How much smaller than the wallpaper the blurred copy is; the blur hides the lost detail. */
    const val SCALE = 8

    /** The blurred wallpaper; null draws plain glass (no blur set, a live wallpaper, or none decoded yet). */
    var image by mutableStateOf<ImageBitmap?>(null)

    /** Where the wallpaper has slid to (0 first page, 1 last), read while drawing; Launcher sets it. */
    var offset: () -> Float = { 0.5f }

    /** The window the wallpaper fills, in pixels; Launcher sets it as it draws the wallpaper. */
    var screen = IntSize.Zero
}

/** Draws the [Backdrop] behind what follows; a no-op without one. Goes inside the tile's clip. */
fun Modifier.frosted(): Modifier =
    composed {
        val image = Backdrop.image ?: return@composed this
        var origin by remember { mutableStateOf(Offset.Zero) }
        onGloballyPositioned { origin = it.positionInWindow() }
            .drawBehind {
                val screen = Backdrop.screen
                if (screen.width == 0 || screen.height == 0) return@drawBehind
                // The copy keeps the wallpaper's proportions, so the wallpaper's own crop works on it directly.
                val crop = wallpaperCrop(image.width, image.height, screen.width, screen.height, Backdrop.offset())
                val sx = crop.width().toFloat() / screen.width
                val sy = crop.height().toFloat() / screen.height
                val w = (size.width * sx).roundToInt().coerceIn(1, image.width)
                val h = (size.height * sy).roundToInt().coerceIn(1, image.height)
                val x = (crop.left + origin.x * sx).roundToInt().coerceIn(0, image.width - w)
                val y = (crop.top + origin.y * sy).roundToInt().coerceIn(0, image.height - h)
                drawImage(
                    image,
                    srcOffset = IntOffset(x, y),
                    srcSize = IntSize(w, h),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.High,
                )
            }
    }

/**
 * A [Backdrop.SCALE]-times smaller copy of [source], blurred by [radius] of its
 * own pixels: three box blurs each way, close to a Gaussian. Slow; off the main thread.
 */
fun blurredCopy(
    source: Bitmap,
    radius: Int,
): Bitmap {
    // Wallpapers are decoded into hardware memory, which can be neither scaled on a canvas nor read.
    val readable = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
    val w = (readable.width / Backdrop.SCALE).coerceAtLeast(1)
    val h = (readable.height / Backdrop.SCALE).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(readable, w, h, true)
    if (readable !== source) readable.recycle()
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    val tmp = IntArray(px.size)
    val r = radius.coerceIn(1, maxOf(w, h))
    repeat(3) {
        boxBlur(px, tmp, w, h, r, horizontal = true)
        boxBlur(tmp, px, w, h, r, horizontal = false)
    }
    val out = small.copy(Bitmap.Config.ARGB_8888, true)
    if (out !== small) small.recycle()
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

/** One running-sum box blur pass from [src] into [dst], along rows or columns, edges clamped. Opaque pixels. */
private fun boxBlur(
    src: IntArray,
    dst: IntArray,
    w: Int,
    h: Int,
    r: Int,
    horizontal: Boolean,
) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val div = 2 * r + 1
    for (line in 0 until lines) {
        fun at(i: Int) = src[if (horizontal) line * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + line]
        var rs = 0
        var gs = 0
        var bs = 0
        for (i in -r..r) {
            val c = at(i)
            rs += c shr 16 and 0xff
            gs += c shr 8 and 0xff
            bs += c and 0xff
        }
        for (i in 0 until len) {
            dst[if (horizontal) line * w + i else i * w + line] = (0xff shl 24) or (rs / div shl 16) or (gs / div shl 8) or (bs / div)
            val add = at(i + r + 1)
            val drop = at(i - r)
            rs += (add shr 16 and 0xff) - (drop shr 16 and 0xff)
            gs += (add shr 8 and 0xff) - (drop shr 8 and 0xff)
            bs += (add and 0xff) - (drop and 0xff)
        }
    }
}
