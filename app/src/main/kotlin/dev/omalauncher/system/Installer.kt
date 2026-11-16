package dev.omalauncher.system

import android.content.Context
import android.os.Build

/** The package of the app that installed the launcher (Play, an F-Droid client), or null for adb and the like. */
fun installerOf(context: Context): String? =
    runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()
