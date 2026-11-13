package dev.omalauncher

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest

/**
 * The GitHub and F-Droid build's twin of the play build's LauncherUpdates: without Play's closed-source library it
 * never finds an update. Those installs are updated by whatever installed them.
 */
@Suppress("UNUSED_PARAMETER", "unused")
class LauncherUpdates(
    context: Context,
) {
    val available = false
    val checking = false
    val failed = false

    fun check() {}

    fun start(launcher: ActivityResultLauncher<IntentSenderRequest>) {}

    fun onResult(resultCode: Int) {}
}
