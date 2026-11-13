package dev.omalauncher.theme

import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import dev.omalauncher.ui.decodeForScreen
import java.util.concurrent.Executors

/**
 * Android live wallpaper showing the current Omarchy background. Its main
 * purpose is [Engine.onComputeColors]: Android 12+ derives the system color
 * scheme (quick settings, notifications, Settings, keyboard, dynamic-color
 * apps) from the colors the wallpaper reports, so we report the theme's
 * palette instead of colors sampled from the image.
 */
class OmarchyWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = OmarchyEngine()

    private inner class OmarchyEngine : Engine() {
        private val main = Handler(Looper.getMainLooper())
        private val decoder = Executors.newSingleThreadExecutor()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        private var colors: Map<String, String> = emptyMap()
        private var bitmap: Bitmap? = null
        private var loadedPath: String? = null
        private var framing = WallpaperFraming()

        /** Where the launcher's pages are scrolled, 0 to 1: the background slides along. */
        private var xOffset = 0.5f

        private val onThemeChanged: () -> Unit = { main.post { reload() } }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(true)
            ThemeStore.changeListeners.add(onThemeChanged)
            reload()
        }

        override fun onDestroy() {
            ThemeStore.changeListeners.remove(onThemeChanged)
            decoder.shutdownNow()
            super.onDestroy()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            draw()
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            if (xOffset == this.xOffset) return
            this.xOffset = xOffset
            if (isVisible) draw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) draw()
        }

        /** Reads the saved theme off the main thread, then redraws with it. */
        private fun reload() {
            // A change posted just before the engine was destroyed.
            if (decoder.isShutdown) return
            decoder.execute {
                val (themeColors, file) = ThemeStore.savedSelection(this@OmarchyWallpaperService) ?: (emptyMap<String, String>() to null)
                val savedFraming = StyleStore.read(this@OmarchyWallpaperService).wallpaperFraming
                val decoded = if (file?.path != loadedPath) file?.let { decodeForScreen(it, this@OmarchyWallpaperService) } else null
                main.post {
                    val colorsChanged = themeColors != colors
                    colors = themeColors
                    framing = savedFraming
                    if (file?.path != loadedPath) {
                        loadedPath = file?.path
                        bitmap?.recycle()
                        bitmap = decoded
                    }
                    draw()
                    if (colorsChanged) notifyColorsChanged()
                }
            }
        }

        private fun draw() {
            val holder = surfaceHolder
            val canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: return
            try {
                canvas.drawColor(color("background", Color.BLACK))
                bitmap?.let { bmp ->
                    val placement = framing.placement(bmp.width.toFloat(), bmp.height.toFloat(), canvas.width.toFloat(), canvas.height.toFloat(), xOffset)
                    val src = placement.sourcePixels(bmp.width, bmp.height)
                    val dst = placement.destinationPixels(canvas.width, canvas.height)
                    canvas.drawBitmap(bmp, Rect(src.left, src.top, src.right, src.bottom), Rect(dst.left, dst.top, dst.right, dst.bottom), paint)
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun color(
            key: String,
            fallback: Int,
        ): Int = colors[key]?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: fallback

        /** The theme palette, accent first: Android seeds its system colors from the most prominent one. */
        override fun onComputeColors(): WallpaperColors? {
            if (colors.isEmpty()) return null
            val primary = Color.valueOf(color("accent", Color.BLUE))
            val secondary = Color.valueOf(color("magenta", color("accent", Color.BLUE)))
            val tertiary = Color.valueOf(color("background", Color.BLACK))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return WallpaperColors(primary, secondary, tertiary)
            val hints =
                if (colors["mode"] == "light") WallpaperColors.HINT_SUPPORTS_DARK_TEXT else WallpaperColors.HINT_SUPPORTS_DARK_THEME
            return WallpaperColors(primary, secondary, tertiary, hints)
        }
    }
}
