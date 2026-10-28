package dev.omalauncher.ui

import android.net.Uri
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import dev.omalauncher.Runtime
import dev.omalauncher.wayland.XdgToplevel
import kotlinx.coroutines.launch

/**
 * Android's photo picker for pickImages(), the key bar's image key: the picked images go into the Linux window that had
 * the keyboard as their paths, which agents attach (see [KeyboardBridge.insertImages]). Each activity that shows the key
 * bar has one, made with it, since the key bar is gone while the picker covers it and couldn't take the result.
 * Needs no permission: Android hands over only what's picked.
 */
class ImagePicker(
    private val activity: ComponentActivity,
    private val runtime: () -> Runtime,
) {
    /** The window that had the keyboard when the picker opened, and the view that raised the soft keyboard for it. */
    private var target: XdgToplevel? = null
    private var sink: KeyboardSink? = null

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(), ::picked)

    fun launch() {
        target = runtime().wm.focused ?: return
        sink = activity.currentFocus as? KeyboardSink
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** Also with none picked, so the window has the keyboard back as it had before, the soft one too. */
    private fun picked(uris: List<Uri>) {
        val window = target ?: return
        val sink = sink
        target = null
        this.sink = null
        // The result comes before onResume, which takes the keyboard from home's terminal tiles.
        activity.lifecycleScope.launch {
            activity.withResumed {
                val runtime = runtime()
                if (!runtime.terminals.refocus(window)) return@withResumed
                sink?.takeIf { it.isAttachedToWindow }?.let(::showKeyboard)
                if (uris.isNotEmpty()) runtime.keyboard.insertImages(uris)
            }
        }
    }

    /** The soft keyboard comes up only once the window has focus again, a moment after it resumes. */
    private fun showKeyboard(sink: KeyboardSink) {
        if (sink.hasWindowFocus()) return sink.show()
        sink.viewTreeObserver.addOnWindowFocusChangeListener(
            object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (!hasFocus) return
                    sink.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                    sink.show()
                }
            },
        )
    }
}
