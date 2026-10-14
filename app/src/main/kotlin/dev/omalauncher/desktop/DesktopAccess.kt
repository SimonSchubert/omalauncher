package dev.omalauncher.desktop

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import java.security.MessageDigest
import java.util.UUID

/** No client-supplied package names or certificates are used for authorization. Main thread only. */
class DesktopAccess(private val context: Context) {
    private val prefs = context.getSharedPreferences("external_desktop_access", Context.MODE_PRIVATE)
    private data class Request(val identity: DesktopIdentity, val created: Long)
    private val requests = LinkedHashMap<String, Request>()
    internal val approvedPackages = mutableStateListOf<String>().apply { addAll(prefs.all.keys.sorted()) }
    internal val revocations = mutableSetOf<(String) -> Unit>()

    internal fun identity(uid: Int): DesktopIdentity? {
        // Shared UIDs cannot prove which package is calling; decline rather than widen an approval.
        val packages = context.packageManager.getPackagesForUid(uid) ?: return null
        val packageName = packages.singleOrNull() ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            if (info.applicationInfo?.uid != uid) return null
            val signatures = info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() } ?: return null
            DesktopIdentity(uid, packageName, signatures.map { digest(it.toByteArray()) }.sorted().joinToString(":"))
        }.getOrNull()
    }

    internal fun approved(identity: DesktopIdentity) = prefs.getString(identity.packageName, null) == identity.certificate

    internal fun consent(identity: DesktopIdentity): PendingIntent {
        val now = SystemClock.elapsedRealtime()
        requests.entries.removeAll { now - it.value.created > 120_000 }
        require(requests.size < 16) { "too many pending desktop requests" }
        val token = UUID.randomUUID().toString()
        requests[token] = Request(identity, now)
        // Unique data and immutable extras keep one caller from replacing another caller's request.
        val intent = Intent(context, DesktopConsentActivity::class.java)
            .setData(android.net.Uri.parse("omalauncher-consent:$token"))
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
    }

    internal fun request(token: String): DesktopIdentity? = requests[token]?.takeIf {
        SystemClock.elapsedRealtime() - it.created <= 120_000 && identity(it.identity.uid) == it.identity
    }?.identity

    internal fun grant(token: String): Boolean {
        val identity = request(token) ?: return false
        requests.remove(token)
        prefs.edit().putString(identity.packageName, identity.certificate).apply()
        if (identity.packageName !in approvedPackages) approvedPackages += identity.packageName
        return true
    }

    internal fun deny(token: String) { requests.remove(token) }

    internal fun revoke(packageName: String) {
        prefs.edit().remove(packageName).apply()
        approvedPackages.remove(packageName)
        requests.entries.removeAll { it.value.identity.packageName == packageName }
        revocations.toList().forEach { it(packageName) }
    }

    internal companion object {
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
