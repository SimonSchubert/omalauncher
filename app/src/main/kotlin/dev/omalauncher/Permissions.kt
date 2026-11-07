package dev.omalauncher

import android.content.Context
import android.content.pm.PackageManager

/** Whether Android allows the launcher [permission] now. */
fun Context.hasPermission(permission: String): Boolean = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
