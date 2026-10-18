package dev.omalauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 17 keeps apps that target it off the local network (192.168.x, printers, Home Assistant, Cast)
 * until they hold ACCESS_LOCAL_NETWORK: TCP connections there time out, UDP fails with EPERM. That covers
 * widgets and everything Linux runs. Most people never need it, so it isn't asked for on its own: settings
 * shows a row for it while it's missing.
 */
object LocalNetwork {
    const val PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK

    /** Whether local addresses are blocked for us: Android 17+ without the permission. */
    fun missing(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED
}
