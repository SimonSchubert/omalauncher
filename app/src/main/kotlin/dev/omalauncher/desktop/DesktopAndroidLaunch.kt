package dev.omalauncher.desktop

import android.app.ActivityOptions
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.os.UserManager
import android.os.Process

/** Public Android launch path. Window placement and decorations belong to the system. */
internal fun launchOnAndroidDesktop(context: Context, displayId: Int, component: ComponentName, user: UserHandle, serial: Long) {
    val users = context.getSystemService(UserManager::class.java)
    check(serial >= 0 && users.getSerialNumberForUser(user) == serial) { "App profile is unavailable" }
    check(!users.isQuietModeEnabled(user)) { "Enable this app profile on the phone first" }
    if (user == Process.myUserHandle()) {
        check(context.getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(context, displayId,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component))) {
            "Android does not allow this app on the requested display"
        }
    }
    context.getSystemService(LauncherApps::class.java).startMainActivity(component, user, null,
        ActivityOptions.makeBasic().apply { launchDisplayId = displayId }.toBundle())
}
