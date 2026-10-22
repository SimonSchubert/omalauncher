package dev.omalauncher.widgets

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.omalauncher.ui.KeybarPreview
import dev.omalauncher.ui.OwlAppWindow
import dev.omalauncher.ui.PanelPreview
import dev.omalauncher.ui.WidgetScreenPanel
import dev.omalauncher.widgets.lang.OwlKind
import dev.omalauncher.ui.WidgetTile
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Renders widgets to PNGs off screen, for agents editing them in the guest:
 * they can't see the phone, so `.preview/<file>.png` shows them what the
 * tile looks like. The tile is drawn exactly as on the home screen, on a
 * private virtual display the size of the tile, so it works while the home
 * screen is hidden behind the agent's terminal.
 */
internal class Previews(
    private val context: Context,
) {
    /**
     * Draws [spec] at [cells] ([widthPx] x [heightPx]) once [ready] returns
     * (or after a while if it doesn't) and writes the PNG to [out]. Main thread.
     */
    suspend fun render(
        spec: WidgetSpec,
        instance: dev.omalauncher.widgets.runtime.WidgetInstance,
        cells: Pair<Int, Int>,
        widthPx: Int,
        heightPx: Int,
        out: File,
        ready: suspend () -> Unit,
    ): Boolean =
        draw(spec, widthPx, heightPx, out, ready) {
            WidgetTile(
                spec = spec,
                instance = instance,
                cells = cells,
                focused = false,
                modifier = Modifier.fillMaxSize(),
                onTap = {},
                needs = emptyList(),
                install = null,
                onInstall = {},
                onFix = {},
            )
        }

    /** Draws [spec]'s screen { } as it opens over home (an app's window, for an app; a panel fully open), at [widthPx] x [heightPx], like [render]. */
    suspend fun renderScreen(
        spec: WidgetSpec,
        instance: dev.omalauncher.widgets.runtime.WidgetInstance,
        widthPx: Int,
        heightPx: Int,
        out: File,
        ready: suspend () -> Unit,
    ): Boolean =
        draw(spec, widthPx, heightPx, out, ready) {
            when (instance.program.kind) {
                OwlKind.App -> OwlAppWindow(spec, instance)
                OwlKind.Panel -> PanelPreview(instance)
                OwlKind.Keybar -> KeybarPreview(instance)
                else -> WidgetScreenPanel(spec, instance, onDismiss = {})
            }
        }

    private suspend fun draw(
        spec: WidgetSpec,
        widthPx: Int,
        heightPx: Int,
        out: File,
        ready: suspend () -> Unit,
        content: @Composable () -> Unit,
    ): Boolean {
        // A widget can declare a size of thousands of rows; that's no tile to draw, and its bitmap would run out of memory.
        if (widthPx !in 1..MAX_SIDE || heightPx !in 1..MAX_SIDE) return false
        val reader = ImageReader.newInstance(widthPx, heightPx, PixelFormat.RGBA_8888, 3)
        val handler = Handler(Looper.getMainLooper())
        var latest: Image? = null
        reader.setOnImageAvailableListener({ r ->
            // Keep only the newest frame, so the display never runs out of buffers.
            r.acquireLatestImage()?.let { image ->
                latest?.close()
                latest = image
            }
        }, handler)
        val dm = context.getSystemService(DisplayManager::class.java)
        val dpi = context.resources.displayMetrics.densityDpi
        // Private (no flags): only this app draws on it, and it needs no permission.
        val display = dm.createVirtualDisplay("omalauncher-preview", widthPx, heightPx, dpi, reader.surface, 0)
        val owner = Owner()
        var presentation: Presentation? = null
        try {
            presentation =
                Presentation(context, display.display).apply {
                    window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                    val view =
                        ComposeView(this.context).apply {
                            setContent { content() }
                        }
                    setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    window?.decorView?.let {
                        it.setViewTreeLifecycleOwner(owner)
                        it.setViewTreeSavedStateRegistryOwner(owner)
                    }
                    show()
                }
            withTimeoutOrNull(READY_TIMEOUT_MS) { ready() }
            // Time to lay out what just loaded, decode images and draw it.
            delay(SETTLE_MS)
            val image = latest ?: return false
            val bitmap = image.toBitmap(widthPx, heightPx)
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            return tmp.renameTo(out)
        } catch (e: Exception) {
            Log.w("widgets", "preview of ${spec.file.name} failed", e)
            return false
        } finally {
            presentation?.dismiss()
            owner.destroy()
            display.release()
            latest?.close()
            reader.close()
        }
    }

    private fun Image.toBitmap(
        w: Int,
        h: Int,
    ): Bitmap {
        val plane = planes[0]
        // Rows may be padded beyond the width: copy at the padded width, then crop.
        val full = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer)
        if (full.width == w) return full
        return Bitmap.createBitmap(full, 0, 0, w, h).also { full.recycle() }
    }

    /** What Compose needs from the window it's in; a Presentation, unlike an activity, provides none. */
    private class Owner :
        LifecycleOwner,
        SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry get() = saved.savedStateRegistry

        init {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        /** How long sources get to load before the preview is taken anyway. */
        const val READY_TIMEOUT_MS = 8_000L
        const val SETTLE_MS = 600L

        /** The largest preview side in pixels; a phone screen fits. */
        const val MAX_SIDE = 8192
    }
}
