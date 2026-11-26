package dev.omalauncher.ui

import android.util.Log

/** JNI bindings, see cpp/pixels.c: work over a picture's pixels, each answering that it couldn't where the library isn't there. */
object PixelsNative {
    private val loaded by lazy {
        try {
            System.loadLibrary("pixels")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w("pixels", "pixels are worked on without the pixels library", e)
            false
        }
    }

    /** FrostCache's blur of [px], in place; false when it couldn't. */
    fun frostBlur(
        px: IntArray,
        w: Int,
        h: Int,
        radius: Int,
    ) = loaded && blur(px, w, h, radius)

    /** IconMask's brightnessToAlpha of [px] in place: 1, 0 for a black logo (left as it was), -1 when it couldn't. */
    fun iconMask(
        px: IntArray,
        blackLogo: Int,
    ) = if (loaded) brightnessToAlpha(px, blackLogo) else -1

    @JvmStatic external fun blur(
        px: IntArray,
        w: Int,
        h: Int,
        radius: Int,
    ): Boolean

    @JvmStatic external fun brightnessToAlpha(
        px: IntArray,
        blackLogo: Int,
    ): Int
}
