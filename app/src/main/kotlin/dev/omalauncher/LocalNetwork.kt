package dev.omalauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

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

    /**
     * Why a widget's request to [url] may have failed, when it's on the local network and Android keeps us
     * off it: a timeout alone doesn't say. Null otherwise. Looks the host up, so not on the main thread.
     */
    fun hint(
        context: Context,
        url: String,
    ): String? {
        if (!missing(context)) return null
        return if (isLocal(url)) "Android keeps the launcher off the local network until it's allowed: settings, local network" else null
    }

    /** Whether [url]'s host is on the local network: a private, link-local or unique local address. */
    fun isLocal(url: String): Boolean {
        val host = runCatching { URI(url).host?.removeSurrounding("[", "]") }.getOrNull() ?: return false
        return runCatching { InetAddress.getAllByName(host) }.getOrNull().orEmpty().any {
            it.isSiteLocalAddress || it.isLinkLocalAddress || (it is Inet6Address && (it.address[0].toInt() and 0xfe) == 0xfc)
        }
    }
}
