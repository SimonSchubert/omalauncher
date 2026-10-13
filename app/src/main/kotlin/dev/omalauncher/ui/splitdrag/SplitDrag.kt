package dev.omalauncher.ui.splitdrag

import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import dev.omalauncher.AndroidApp

/**
 * Which apps can be dragged out of the recent strip, and what this phone can open them as. The launcher drags them
 * itself; see [SplitDragSession] and [SplitOpen].
 */
object SplitDrag {
    /**
     * Android 15 and later, personal apps: apps in a work or clone profile can't be started next to another from here
     * yet (see the spec's next round).
     */
    @ChecksSdkIntAtLeast(api = 35)
    fun canDrag(app: AndroidApp) = Build.VERSION.SDK_INT >= 35 && app.profile == null

    /**
     * Splitting starts the second app a moment after the first, when the launcher is no longer in front; Android allows
     * that only for the home app, so only as the default launcher.
     */
    fun canSplit(context: Context) = context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true

    /** One UI opens apps in pop-up windows; elsewhere only with desktop-style windows. */
    fun canPopUp(context: Context) = isOneUi() || context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)

    private fun isOneUi() =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            runCatching { Class.forName("com.samsung.android.multiwindow.MultiWindowManager") }.isSuccess
}
