package dev.omalauncher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.content.edit
import dev.omalauncher.Runtime

/**
 * Asks for one runtime permission for a widget: the microphone for speech("start") (see
 * [dev.omalauncher.widgets.runtime.Dictation]), the location for location("request") (see
 * [dev.omalauncher.widgets.runtime.PhoneLocation]), calendar for calendar("request") (see
 * [dev.omalauncher.widgets.runtime.PhoneCalendar]), Bluetooth for airpods(...) (see
 * [dev.omalauncher.widgets.runtime.AirPods]). Each activity that shows widgets has one of each, in its [WidgetAsks], made
 * with it, as a launcher must be registered before the activity starts. With [orSettings], a tap once Android won't show its dialog
 * any more opens the app's own settings, where the switch then is, so the tap still does something.
 */
class AskPermission(
    activity: ComponentActivity,
    private val permission: String,
    orSettings: Boolean = false,
) {
    private var then: ((Boolean) -> Unit)? = null
    private val refusals = Refusals(activity, permission) { if (orSettings) appDetails(activity) else null }

    private val launcher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val then = then
            this.then = null
            then?.invoke(granted)
            refusals.answered(granted)
        }

    fun ask(then: (Boolean) -> Unit) {
        this.then = then
        refusals.asking()
        launcher.launch(permission)
    }
}

/**
 * [AskPermission] for a screen in compose: asks for [permission], then calls [then]. Once Android won't show its dialog
 * any more, a tap opens [settings] (the app's own page if null), where the switch then is.
 */
@Composable
fun rememberAskPermission(
    permission: String,
    settings: Intent? = null,
    then: (Boolean) -> Unit = {},
): () -> Unit {
    val activity = LocalActivity.current ?: return {}
    val refusals = remember(permission) { Refusals(activity, permission) { settings ?: appDetails(activity) } }
    val answered by rememberUpdatedState(then)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            answered(granted)
            refusals.answered(granted)
        }
    return {
        refusals.asking()
        launcher.launch(permission)
    }
}

/** The launcher's own page in Android's settings, App info, where each runtime permission has its switch. */
fun appDetails(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Tells a refusal from a dialog Android no longer shows, and opens [settings] (if any) for the latter, where the switch is. */
private class Refusals(
    private val activity: Activity,
    private val permission: String,
    /** Made only when needed: an [AskPermission] is made with its activity, before it has a package name. */
    private val settings: () -> Intent?,
) {
    /** Whether Android would explain itself as it asked: true after one refusal, false again after the second. */
    private var refusedOnce = false

    /**
     * Whether it was ever refused: Android says false for "explain" both before the first ask and once it won't ask
     * any more, and a dialog backed out of says no without a refusal.
     */
    private val refused get() = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun asking() {
        refusedOnce = activity.shouldShowRequestPermissionRationale(permission)
    }

    fun answered(granted: Boolean) {
        val explain = activity.shouldShowRequestPermissionRationale(permission)
        if (!granted && explain) refused.edit { putBoolean(permission, true) }
        // After two refusals Android stops showing the dialog and answers no at once. False before and after, once
        // refused: no dialog came, whereas a second refusal just now was true before, and the user has said no.
        if (!granted && !refusedOnce && !explain && refused.getBoolean(permission, false)) {
            settings()?.let { runCatching { activity.startActivity(it) } }
        }
    }

    private companion object {
        const val PREFS = "permissions_refused"
    }
}

/**
 * The permissions a widget's tap can ask for, one [AskPermission] each, for an activity that shows widgets: made with
 * it, handed to the runtime as it resumes and taken back as it pauses, so a dialog only opens over the activity in
 * front. Each grant is checked again as it resumes, as one may have been given or taken back in Android's settings.
 */
class WidgetAsks(
    activity: ComponentActivity,
) {
    private val mic = AskPermission(activity, Manifest.permission.RECORD_AUDIO)
    private val place = AskPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION, orSettings = true)
    private val calendar = AskPermission(activity, Manifest.permission.READ_CALENDAR, orSettings = true)

    // Asked for only from Android 12 on: before it, an install-time permission covers AirPods, which then never ask.
    @SuppressLint("InlinedApi")
    private val bluetooth = AskPermission(activity, Manifest.permission.BLUETOOTH_CONNECT, orSettings = true)

    fun resumed(runtime: Runtime) {
        runtime.dictation.askMic = mic::ask
        runtime.location.ask = place::ask
        runtime.location.recheck()
        runtime.calendar.ask = calendar::ask
        runtime.calendar.recheck()
        runtime.widgets.airpods.ask = bluetooth::ask
        runtime.widgets.airpods.recheck()
    }

    fun paused(runtime: Runtime) {
        runtime.dictation.askMic = null
        runtime.location.ask = null
        runtime.calendar.ask = null
        runtime.widgets.airpods.ask = null
    }
}
