package dev.omalauncher

import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.ActivityResult
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * A newer launcher on Google Play, for settings' updates box. Play is asked when settings opens and on its "check",
 * never in the background; "update" opens Play's own screen, which asks before it downloads, installs and restarts
 * the launcher. Only an install Play made can take Play's updates, so other installs never ask. The foss build has
 * a twin of this class that asks GitHub releases instead.
 */
class LauncherUpdates(
    context: Context,
) {
    private val fromPlay = installerOf(context) == PLAY_STORE
    private val manager by lazy { AppUpdateManagerFactory.create(context) }

    /** What the last check found; one of these starts only one update screen. */
    private var info: AppUpdateInfo? = null

    /** Play has a newer launcher that this install can take. */
    var available by mutableStateOf(false)
        private set

    var checking by mutableStateOf(false)
        private set

    /** Play's update screen said the update failed. */
    var failed by mutableStateOf(false)
        private set

    /** Play doesn't say the newer version's name. */
    val version: String? = null

    /** Asks Play; Play answers from its own cache, so every call asks, [force] or not. */
    @Suppress("UNUSED_PARAMETER")
    fun check(force: Boolean = false) {
        if (!fromPlay || checking) return
        checking = true
        manager.appUpdateInfo
            .addOnSuccessListener {
                val availability = it.updateAvailability()
                // In progress: an update started earlier that Play hasn't finished; the screen picks it up again.
                val usable =
                    (availability == UpdateAvailability.UPDATE_AVAILABLE || availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) &&
                        it.isUpdateTypeAllowed(IMMEDIATE)
                info = it.takeIf { usable }
                available = usable
            }
            // Offline, or Play is out of date: there's nothing to show, as without an update.
            .addOnFailureListener { Log.w(TAG, "checking Google Play for an update failed", it) }
            .addOnCompleteListener { checking = false }
    }

    /** Opens Play's update screen, which answers through [launcher]; give that answer to [onResult]. */
    fun start(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val info = info ?: return check()
        this.info = null
        failed = false
        if (!manager.startUpdateFlowForResult(info, launcher, IMMEDIATE)) check()
    }

    /** Play's update screen closed without restarting the launcher: canceled, or failed. */
    fun onResult(resultCode: Int) {
        failed = resultCode == ActivityResult.RESULT_IN_APP_UPDATE_FAILED
        check()
    }

    private companion object {
        const val TAG = "LauncherUpdates"
        const val PLAY_STORE = "com.android.vending"
        val IMMEDIATE: AppUpdateOptions = AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE)
    }
}
