package dev.omalauncher.net

import android.Manifest
import android.content.Context
import android.os.Build
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import dev.omalauncher.system.hasPermission

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
            !context.hasPermission(PERMISSION)

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

    /**
     * [url]'s host, or null if it has none or [url] doesn't parse as one. Parsed the same way the real
     * connection is ([java.net.URL], not the stricter [java.net.URI]): a path character [java.net.URI] rejects
     * (a space, `{`, `|`, a bad `%`...) but [java.net.URL] tolerates would otherwise make this return null —
     * "doesn't parse, so let it through" — for a URL the real connection goes ahead and makes anyway.
     */
    private fun hostOf(url: String): String? =
        runCatching { URL(url).host }.getOrNull()?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() }

    /** [host]'s addresses, resolved; empty if it doesn't resolve. */
    private fun addressesOf(host: String): List<InetAddress> = runCatching { InetAddress.getAllByName(host) }.getOrNull().orEmpty()

    /** A private, link-local or unique local address: on the local network, not the open internet. */
    private val InetAddress.isLocalAddress: Boolean
        get() = isSiteLocalAddress || isLinkLocalAddress || (this is Inet6Address && (address[0].toInt() and 0xfe) == 0xfc)

    /** Whether [url]'s host is on the local network: a private, link-local or unique local address. */
    fun isLocal(url: String): Boolean = hostOf(url)?.let(::addressesOf).orEmpty().any { it.isLocalAddress }

    /**
     * Why a widget's connection to [url] is refused before it's ever attempted, or null to let it through.
     * Resolved, not matched against the URL's literal text, so a hostname that answers with a local address
     * is caught the same as a literal one would be. Resolving twice, once here and once more for the real
     * connection, leaves a narrow DNS-rebinding window open (a server answering this lookup with a public
     * address and the next one, moments later, with a local one); closing that fully would mean connecting
     * to the address checked here rather than letting the real connection re-resolve the host, which is more
     * than this check does.
     *
     * Loopback is refused unconditionally: a third-party widget (often installed from a store or a shared
     * link, so effectively untrusted code) has no legitimate reason to reach the phone's own loopback
     * interface, and Android's [PERMISSION] doesn't cover it either, so nothing else is standing in the way.
     * The rest of the local network ([isLocal]) is refused unconditionally too, for now: widgets have no way
     * to declare "I talk to a LAN device" the way a `secret` is declared in the widget language, so there's
     * no sound way to let some of them through while keeping others off a smart-home hub or someone else's
     * app listening on the LAN. That trades away legitimate local-network widgets; a consent declaration at
     * install time, checked here instead of a blanket refusal, would be the better long-term fix.
     *
     * Looks the host up, so not on the main thread.
     */
    fun refuse(url: String): String? {
        val host = hostOf(url) ?: return null
        val addresses = addressesOf(host)
        // 0.0.0.0/:: ("any" address) is never a real remote host either: on Linux, the kernel a widget's
        // request ultimately runs on treats connecting *to* it as connecting to loopback, the same quirk
        // multiple browsers were caught by in 2024 ("0.0.0.0 Day"). Refused the same way loopback is.
        if (addresses.any { it.isLoopbackAddress || it.isAnyLocalAddress }) return "$host is this device's own loopback address"
        if (addresses.any { it.isLocalAddress }) return "$host is on the local network, which widgets can't reach"
        return null
    }
}
