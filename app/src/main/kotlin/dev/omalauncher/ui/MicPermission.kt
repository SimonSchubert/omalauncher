package dev.omalauncher.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Asks for the microphone for a widget's speech("start"), see [dev.omalauncher.widgets.runtime.Dictation]. Each
 * activity that shows widgets has one, made with it, as a launcher must be registered before the activity starts.
 */
class MicPermission(
    activity: ComponentActivity,
) {
    private var then: ((Boolean) -> Unit)? = null

    private val launcher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val then = then
            this.then = null
            then?.invoke(granted)
        }

    fun ask(then: (Boolean) -> Unit) {
        this.then = then
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}
