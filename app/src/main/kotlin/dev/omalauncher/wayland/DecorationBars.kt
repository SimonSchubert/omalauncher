package dev.omalauncher.wayland

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The launcher's bars over floating windows that left decorating to the
 * compositor: a title and a close button, drawn into a [PixelStore] like a
 * client's buffer so they're part of the window's frame and its hit testing
 * (see WindowRenderer). Redrawn only when the title, width or colors change.
 * Used on one window's render thread.
 */
class DecorationBars {
    private class Bar(
        val key: List<Any>,
        val store: PixelStore,
    )

    private val bars = HashMap<XdgToplevel, Bar>()

    /** The bar over [window], [width] pixels wide; null when there's no room for one or it couldn't be drawn. */
    fun store(
        window: XdgToplevel,
        width: Int,
        hooks: WindowManagerHooks,
    ): PixelStore? {
        val height = hooks.decorationHeight
        if (height <= 0 || width <= height) return null
        val colors = hooks.decorationColors
        val key = listOf(window.title, width, height, colors.toList())
        bars[window]?.takeIf { it.key == key }?.let { return it.store }
        val store = render(window.title, width, height, colors) ?: return null
        bars.put(window, Bar(key, store))?.store?.free()
        // Windows that went away since leave their bars behind otherwise.
        bars.keys.filter { it.surface.destroyed }.forEach { bars.remove(it)?.store?.free() }
        return store
    }

    fun release() {
        bars.values.forEach { it.store.free() }
        bars.clear()
    }

    private fun render(
        title: String,
        width: Int,
        height: Int,
        colors: IntArray,
    ): PixelStore? {
        val (background, foreground, muted) = colors
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val paint =
            TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.MONOSPACE
                textSize = height * 0.42f
                color = foreground
            }
        val pad = height * 0.35f
        val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2
        // The close button is the bar's last square.
        val text = TextUtils.ellipsize(title, paint, width - height - pad, TextUtils.TruncateAt.END).toString()
        canvas.drawText(text, pad, baseline, paint)
        paint.color = muted
        canvas.drawText("✕", width - height / 2f - paint.measureText("✕") / 2, baseline, paint)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return upload(pixels, width, height)
    }

    /**
     * Into a [PixelStore] the way a client's frame gets there: the native side copies from mapped memory, so
     * the pixels go through a file. ARGB ints, little-endian, are wl_shm's ARGB8888; the bar is opaque.
     */
    private fun upload(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): PixelStore? {
        val size = pixels.size * 4
        val bytes = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(pixels) }.array()
        val file = File.createTempFile("bar", null)
        try {
            file.writeBytes(bytes)
            val fd = Native.openReadOnly(file.path)
            if (fd < 0) return null
            try {
                val ptr = Native.mmap(fd, size)
                if (ptr == 0L || ptr == -1L) return null
                try {
                    val store = PixelStore(width, height, opaque = true)
                    if (Native.storeCopy(ptr, 0, width * 4, 0, store.ptr, intArrayOf(0, 0, width, height), 1) != 0) {
                        store.free()
                        return null
                    }
                    store.addDamage(0, 0, width, height)
                    return store
                } finally {
                    Native.munmap(ptr, size)
                }
            } finally {
                Native.close(fd)
            }
        } finally {
            file.delete()
        }
    }
}
