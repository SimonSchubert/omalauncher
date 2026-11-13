package dev.omalauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * A newer launcher on GitHub releases, for settings' updates box: the GitHub and F-Droid build's twin of the play
 * build's LauncherUpdates. GitHub is asked when settings opens (at most hourly) and on its "check", never in the
 * background; "update" opens the release's page, with its APK. An install an F-Droid client made never asks:
 * F-Droid ships each release some days later and updates it itself.
 */
class LauncherUpdates(
    private val context: Context,
) {
    private val fromFdroid = installer(context) in FDROID_CLIENTS
    private val scope = MainScope()
    private var checkedAt = 0L

    /** The newer release's page. */
    private var page: String? = null

    /** GitHub has a newer release. */
    var available by mutableStateOf(false)
        private set

    var checking by mutableStateOf(false)
        private set

    /** Never: the update happens outside the launcher. */
    val failed = false

    /** The newer release's version, like "1.5.0". */
    var version by mutableStateOf<String?>(null)
        private set

    /** Asks GitHub unless it was asked within the hour; [force] asks anyway. */
    fun check(force: Boolean = false) {
        if (fromFdroid || checking) return
        if (!force && System.currentTimeMillis() - checkedAt < CHECK_EVERY) return
        checking = true
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { String(httpGet(RELEASES)) }
                val newer = newerRelease(json, BuildConfig.VERSION_NAME)
                page = newer?.second
                version = newer?.first
                available = newer != null
                checkedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                // Offline or rate-limited: nothing to show, as without an update; the next settings visit asks again.
                Log.w(TAG, "checking GitHub for a release failed", e)
            } finally {
                checking = false
            }
        }
    }

    /** Opens the newer release's page in the browser. */
    @Suppress("UNUSED_PARAMETER")
    fun start(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val page = page ?: return
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Suppress("UNUSED_PARAMETER")
    fun onResult(resultCode: Int) {}

    internal companion object {
        const val TAG = "LauncherUpdates"
        const val RELEASES = "https://api.github.com/repos/SimonSchubert/omalauncher/releases?per_page=20"
        const val CHECK_EVERY = 60 * 60_000L

        /** F-Droid and the clients of its repositories. */
        val FDROID_CLIENTS = setOf("org.fdroid.fdroid", "org.fdroid.basic", "com.looker.droidify", "com.machiav3lli.fdroid")

        private val TAG_VERSION = Regex("""v(\d+(?:\.\d+)*)""")

        /**
         * The newest release in GitHub's releases [json] that's newer than [current], as its version and page; only
         * the app's own releases count (tags like v1.5.0, not drafts or pre-releases, nor others like mesa-zink-…).
         */
        fun newerRelease(
            json: String,
            current: String,
        ): Pair<String, String>? {
            val releases = JSONArray(json)
            val versions =
                (0 until releases.length()).mapNotNull { i ->
                    val r = releases.getJSONObject(i)
                    if (r.optBoolean("draft") || r.optBoolean("prerelease")) return@mapNotNull null
                    val version = TAG_VERSION.matchEntire(r.optString("tag_name"))?.groupValues?.get(1) ?: return@mapNotNull null
                    version to r.optString("html_url")
                }
            return versions.filter { compareVersions(it.first, current) > 0 }.maxWithOrNull { a, b -> compareVersions(a.first, b.first) }
        }

        /** Compares dotted versions part by part as numbers: 1.10.0 is after 1.9.2; a missing part is 0. */
        fun compareVersions(
            a: String,
            b: String,
        ): Int {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }

        fun installer(context: Context): String? =
            runCatching {
                val pm = context.packageManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    pm.getInstallSourceInfo(context.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(context.packageName)
                }
            }.getOrNull()
    }
}
