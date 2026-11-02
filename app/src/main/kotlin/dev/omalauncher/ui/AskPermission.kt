package dev.omalauncher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit

/**
 * Asks for one runtime permission for a widget: the microphone for speech("start") (see
 * [dev.omalauncher.widgets.runtime.Dictation]), the location for location("request") (see
 * [dev.omalauncher.widgets.runtime.PhoneLocation]), Bluetooth for airpods(...) (see
 * [dev.omalauncher.widgets.runtime.AirPods]). Each activity that shows widgets has one of each, made with it, as a
 * launcher must be registered before the activity starts. With [orSettings], a tap once Android won't show its dialog
 * any more opens the app's own settings, where the switch then is, so the tap still does something.
 */
class AskPermission(
    private val activity: ComponentActivity,
    private val permission: String,
    private val orSettings: Boolean = false,
) {
    private var then: ((Boolean) -> Unit)? = null

    /** Whether Android would explain itself as it asked: true after one refusal, false again after the second. */
    private var refusedOnce = false

    /**
     * Whether it was ever refused: Android says false for "explain" both before the first ask and once it won't ask
     * any more, and a dialog backed out of says no without a refusal.
     */
    private val refused get() = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val launcher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val then = then
            this.then = null
            then?.invoke(granted)
            val explain = activity.shouldShowRequestPermissionRationale(permission)
            if (!granted && explain) refused.edit { putBoolean(permission, true) }
            // After two refusals Android stops showing the dialog and answers no at once. False before and after, once
            // refused: no dialog came, whereas a second refusal just now was true before, and the user has said no.
            if (!granted && orSettings && !refusedOnce && !explain && refused.getBoolean(permission, false)) {
                runCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
                    )
                }
            }
        }

    fun ask(then: (Boolean) -> Unit) {
        this.then = then
        refusedOnce = activity.shouldShowRequestPermissionRationale(permission)
        launcher.launch(permission)
    }

    private companion object {
        const val PREFS = "permissions_refused"
    }
}
