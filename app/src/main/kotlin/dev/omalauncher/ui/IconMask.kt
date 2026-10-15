package dev.omalauncher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * An app icon as an alpha-only silhouette, for tinting in one color (monochrome icons).
 * Prefers the adaptive icon's monochrome layer (Android's themed icons), then its
 * foreground layer (the logo without its backdrop), then the whole icon.
 */
fun iconMask(
    drawable: Drawable,
    size: Int,
): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val adaptive = drawable as? AdaptiveIconDrawable
    val mono = if (Build.VERSION.SDK_INT >= 33) adaptive?.monochrome else null
    val layer = mono ?: adaptive?.foreground
    if (layer != null) {
        // Adaptive layers are 108dp with a 72dp safe zone; scale so the safe zone fills the bitmap.
        val inset = size / 4
        layer.setBounds(-inset, -inset, size + inset, size + inset)
        layer.draw(canvas)
    } else {
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
    }
    // The monochrome layer is already a mask; anything else keeps its detail as brightness.
    if (mono == null) brightnessToAlpha(bmp)
    return bmp.extractAlpha().also { bmp.recycle() }
}

/** Silhouette of a plain bitmap (Linux icons). */
fun bitmapMask(source: Bitmap): Bitmap {
    val bmp = source.copy(Bitmap.Config.ARGB_8888, true)
    brightnessToAlpha(bmp)
    return bmp.extractAlpha().also { bmp.recycle() }
}

/**
 * Bright pixels become opaque, dark ones transparent, scaled so the brightest part
 * is fully opaque: dark logos stay visible, and details survive as shades. A black
 * logo has no brightness to scale (Revolut's shortcuts: a black arrow on a white
 * backdrop), so it keeps its own shape instead.
 */
private fun brightnessToAlpha(bmp: Bitmap) {
    val w = bmp.width
    val h = bmp.height
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
    val values = IntArray(pixels.size)
    var max = 1
    for (i in pixels.indices) {
        val p = pixels[i]
        val lum = (0.3 * ((p shr 16) and 0xff) + 0.59 * ((p shr 8) and 0xff) + 0.11 * (p and 0xff)).toInt()
        val v = (p ushr 24) * lum / 255
        values[i] = v
        if (v > max) max = v
    }
    if (max < BLACK_LOGO) return
    for (i in pixels.indices) pixels[i] = ((values[i] * 255 / max) shl 24) or 0xffffff
    bmp.setPixels(pixels, 0, w, 0, 0, w, h)
}

/** Below this brightness at its brightest (of 255), a logo counts as black and keeps its shape. */
private const val BLACK_LOGO = 16
