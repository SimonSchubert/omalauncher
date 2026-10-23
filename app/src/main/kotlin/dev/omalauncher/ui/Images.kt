package dev.omalauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import java.io.File
import kotlin.math.roundToInt

/**
 * Decodes [file] as small as it can go, halving while both sides stay at
 * least [minWidth] by [minHeight]. Images are often far larger than where
 * they're shown (4K backgrounds, photos), and a full decode costs memory and time.
 * Null rather than a bitmap over [maxBytes]: checked before decoding, as running
 * out of memory while decoding kills the process.
 */
fun decodeSampled(
    file: File,
    minWidth: Int,
    minHeight: Int = minWidth,
    maxBytes: Long = Long.MAX_VALUE,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= minWidth && bounds.outHeight / (sample * 2) >= minHeight) sample *= 2
    if (4L * ceilDiv(bounds.outWidth, sample) * ceilDiv(bounds.outHeight, sample) > maxBytes) return null
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** [decodeSampled] for an image already in memory, like a download. */
fun decodeSampled(
    bytes: ByteArray,
    minWidth: Int,
    minHeight: Int = minWidth,
    maxBytes: Long = Long.MAX_VALUE,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= minWidth && bounds.outHeight / (sample * 2) >= minHeight) sample *= 2
    if (4L * ceilDiv(bounds.outWidth, sample) * ceilDiv(bounds.outHeight, sample) > maxBytes) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun ceilDiv(
    a: Int,
    b: Int,
) = (a + b - 1) / b

/** Decodes an app icon at about [size] px square: a PNG via [decodeSampled], an SVG rendered at exactly [size]. */
fun decodeIcon(
    file: File,
    size: Int,
): Bitmap? {
    if (file.extension != "svg") return decodeSampled(file, size)
    return runCatching { renderSvg(file.inputStream().use { SVG.getFromInputStream(it) }, size, null) }.getOrNull()
}

/** An owl app's `icon:` SVG at [size] px square, with `currentColor` in [color] (the theme's accent). */
fun decodeSvgIcon(
    svg: String,
    size: Int,
    color: Int,
): Bitmap? = runCatching { renderSvg(SVG.getFromString(svg), size, RenderOptions().css("svg { color: #%06x }".format(color and 0xffffff))) }.getOrNull()

/** An owl app's `icon:` image (a PNG asset) at [size] px square: scaled to fit, centered, never stretched. */
fun decodeImageIcon(
    bytes: ByteArray,
    size: Int,
): Bitmap? {
    // A small PNG can hold a huge flat image: halve while the longer side stays at least [size].
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
    val src = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull() ?: return null
    val scale = size.toFloat() / maxOf(src.width, src.height)
    val w = (src.width * scale).roundToInt().coerceAtLeast(1)
    val h = (src.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { out ->
        Canvas(out).drawBitmap(src, null, Rect((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
    }
}

private fun renderSvg(
    svg: SVG,
    size: Int,
    options: RenderOptions?,
): Bitmap {
    // Scale to the bitmap through the viewBox, made from width/height when the icon has none.
    if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
        svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
    }
    svg.setDocumentWidth("100%")
    svg.setDocumentHeight("100%")
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it), options) }
}

/**
 * Decodes a full-screen image (background, photo) at about screen resolution: enough to center-crop in either
 * orientation. Scaled to size rather than halved, which would keep a 4K background at full size, and kept in
 * graphics memory: these are only ever drawn, and a hardware bitmap needs no upload on the first frame.
 */
fun decodeForScreen(
    file: File,
    context: Context,
): Bitmap? {
    val screen = context.resources.displayMetrics
    val side = maxOf(screen.widthPixels, screen.heightPixels) * 3 / 4
    return runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val (w, h) = info.size.width to info.size.height
            val scale = side.toFloat() / minOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
        }
    }.getOrNull()
}

/**
 * The part of a [bmpW]×[bmpH] background that fills a [w]×[h] screen: center-cropped, then slid sideways by
 * [offset] (0 = first home page, 1 = last), like a stock launcher's wallpaper. The slide stays within half a
 * screen so the background trails the pages instead of racing them.
 */
fun wallpaperCrop(
    bmpW: Int,
    bmpH: Int,
    w: Int,
    h: Int,
    offset: Float,
): Rect {
    val scale = maxOf(w.toFloat() / bmpW, h.toFloat() / bmpH)
    val srcW = (w / scale).toInt()
    val srcH = (h / scale).toInt()
    val travel = minOf(bmpW - srcW, srcW / 2)
    val left = (bmpW - srcW - travel) / 2 + (travel * offset.coerceIn(0f, 1f)).roundToInt()
    val top = (bmpH - srcH) / 2
    return Rect(left, top, left + srcW, top + srcH)
}
