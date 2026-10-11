package dev.omalauncher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.omalauncher.ui.iconMask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AndroidApp(
    val label: String,
    val component: ComponentName,
    /** Preinstalled and never updated-in-place away from that: can't be uninstalled, only disabled. */
    val system: Boolean = false,
    /** The profile it's installed in; null for this launcher's own user. */
    val user: UserHandle? = null,
) {
    /** Lives in Private Space: kept out of home, the recent strip and search ranking. */
    val isPrivate get() = user != null

    val intent: Intent
        get() =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/**
 * The phone's launchable apps, loaded once per process and kept current through
 * LauncherApps callbacks, so the drawer never waits on PackageManager.
 * Labels arrive first; icons fill in behind them.
 *
 * Android 15's Private Space is a hidden profile: its apps are listed apart in
 * [privateApps], and only while it's unlocked.
 */
class AndroidAppCatalog(
    private val context: Context,
) {
    val apps = mutableStateListOf<AndroidApp>()
    val icons = mutableStateMapOf<ComponentName, ImageBitmap>()

    /** Alpha-only silhouettes of the same icons, for the monochrome icon style. */
    val masks = mutableStateMapOf<ComponentName, ImageBitmap>()

    /** Private Space's apps, empty while it's locked; icons are keyed apart, as an app can be installed in both. */
    val privateApps = mutableStateListOf<AndroidApp>()
    val privateIcons = mutableStateMapOf<ComponentName, ImageBitmap>()
    val privateMasks = mutableStateMapOf<ComponentName, ImageBitmap>()

    /** The Private Space profile, or null when there's none (or before Android 15). */
    var privateSpace by mutableStateOf<UserHandle?>(null)
        private set

    /** Private Space is locked: its apps aren't running and aren't listed. */
    var privateLocked by mutableStateOf(true)
        private set

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val iconDispatcher = Dispatchers.IO.limitedParallelism(4)
    private var loading: Job? = null

    /** Icon edge in px: the drawer's 52dp icon at this screen's density. */
    private val iconPx = (52 * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)

    fun start() {
        launcherApps.registerCallback(
            object : LauncherApps.Callback() {
                override fun onPackageAdded(packageName: String, user: UserHandle) = reload()

                override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()

                // Updates can change the icon, so drop the cached one.
                override fun onPackageChanged(packageName: String, user: UserHandle) = reload(evict = packageName)

                override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()

                override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
            },
        )
        // Private Space being locked, unlocked, set up or removed.
        if (Build.VERSION.SDK_INT >= 35) {
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_PROFILE_AVAILABLE)
                    addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
                    addAction(Intent.ACTION_PROFILE_ADDED)
                    addAction(Intent.ACTION_PROFILE_REMOVED)
                }
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) = reload()
                }
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }
        reload()
    }

    /**
     * Reloads if Private Space came or went unnoticed: it's only visible to the default home app,
     * and becoming that (right after install, say) sends no broadcast.
     */
    fun refreshPrivate() {
        if (Build.VERSION.SDK_INT < 35) return
        scope.launch {
            val (space, locked) = withContext(Dispatchers.IO) { privateState() }
            if (space != privateSpace || locked != privateLocked) reload()
        }
    }

    /** Locks or unlocks Private Space; unlocking asks for the screen lock or the space's own, if it has one. */
    fun lockPrivate(locked: Boolean) {
        val user = privateSpace ?: return
        runCatching { userManager.requestQuietModeEnabled(locked, user) }
            .onFailure { Log.w(TAG, "cannot ${if (locked) "lock" else "unlock"} private space", it) }
    }

    /** Private Space's page in system settings. */
    fun openPrivateSettings() {
        if (Build.VERSION.SDK_INT < 35) return
        runCatching {
            val sender = launcherApps.privateSpaceSettingsIntent ?: return
            context.startIntentSender(sender, null, Intent.FLAG_ACTIVITY_NEW_TASK, Intent.FLAG_ACTIVITY_NEW_TASK, 0)
        }.onFailure { Log.w(TAG, "cannot open private space settings", it) }
    }

    /** Opens [app]; Private Space ones go through LauncherApps, which can start activities in other profiles. */
    fun open(app: AndroidApp) {
        runCatching {
            if (app.user == null) {
                context.startActivity(app.intent)
            } else {
                launcherApps.startMainActivity(app.component, app.user, null, null)
            }
        }.onFailure { Log.w(TAG, "cannot open ${app.component}", it) }
    }

    fun icon(
        app: AndroidApp,
        mono: Boolean,
    ): ImageBitmap? =
        when {
            app.isPrivate -> if (mono) privateMasks[app.component] else privateIcons[app.component]
            else -> if (mono) masks[app.component] else icons[app.component]
        }

    /** The system's App info page, as stock launchers open it. */
    fun openInfo(app: AndroidApp) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user ?: Process.myUserHandle(), null, null) }
            .onFailure { Log.w(TAG, "cannot open info for ${app.component}", it) }
    }

    /** Asks the system to uninstall; the list updates through the package callback. */
    fun uninstall(app: AndroidApp) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.component.packageName))
                    // The package installer's (hidden) Intent.EXTRA_USER: uninstall from Private Space, not from here.
                    .apply { if (app.user != null) putExtra("android.intent.extra.USER", app.user) }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "cannot uninstall ${app.component}", it) }
    }

    /** The Private Space profile, if there is one and we may see it (the default launcher holding ACCESS_HIDDEN_PROFILES). */
    private fun findPrivateSpace(): UserHandle? {
        if (Build.VERSION.SDK_INT < 35) return null
        return launcherApps.profiles.firstOrNull { user ->
            runCatching { launcherApps.getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE }.getOrDefault(false)
        }
    }

    /** The Private Space profile and whether it's locked; blocking. */
    private fun privateState(): Pair<UserHandle?, Boolean> {
        val user = runCatching { findPrivateSpace() }.onFailure { Log.w(TAG, "cannot list profiles", it) }.getOrNull()
        return user to (user == null || userManager.isQuietModeEnabled(user))
    }

    private fun reload(evict: String? = null) {
        loading?.cancel()
        loading =
            scope.launch {
                if (evict != null) {
                    for (map in listOf(icons, masks, privateIcons, privateMasks)) map.keys.removeAll { it.packageName == evict }
                }
                val (space, locked) = withContext(Dispatchers.IO) { privateState() }
                privateSpace = space
                privateLocked = locked
                load(Process.myUserHandle(), null, apps, icons, masks)
                load(space.takeUnless { locked }, space, privateApps, privateIcons, privateMasks)
            }
    }

    /** Lists [user]'s apps into [into] and renders the icons it lacks, behind; a null [user] just empties it all. */
    private suspend fun CoroutineScope.load(
        user: UserHandle?,
        tag: UserHandle?,
        into: MutableList<AndroidApp>,
        icons: MutableMap<ComponentName, ImageBitmap>,
        masks: MutableMap<ComponentName, ImageBitmap>,
    ) {
        val infos =
            if (user == null) {
                emptyList()
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { launcherApps.getActivityList(null, user) }
                        .onFailure { Log.w(TAG, "cannot list apps", it) }
                        .getOrDefault(emptyList())
                        .filter { it.componentName.packageName != context.packageName }
                        .map { it to AndroidApp(it.label.toString(), it.componentName, it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0, tag) }
                        .sortedBy { it.second.label.lowercase() }
                }
            }
        val list = infos.map { it.second }
        if (list != into.toList()) {
            into.clear()
            into += list
        }
        val present = list.mapTo(HashSet()) { it.component }
        icons.keys.retainAll(present)
        masks.keys.retainAll(present)
        for ((info, app) in infos) {
            if (app.component in icons) continue
            launch {
                // Both styles are rendered up front so switching is instant; masks are 1 byte per pixel.
                val rendered =
                    withContext(iconDispatcher) {
                        runCatching {
                            val d = info.getIcon(0)
                            // Private Space apps carry the system's badge, so they read apart in search results.
                            val shown = if (app.isPrivate) info.getBadgedIcon(0) else d
                            val bmp = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
                            shown.setBounds(0, 0, iconPx, iconPx)
                            shown.draw(Canvas(bmp))
                            bmp.asImageBitmap() to iconMask(d, iconPx).asImageBitmap()
                        }.getOrNull()
                    }
                if (rendered != null) {
                    icons[app.component] = rendered.first
                    masks[app.component] = rendered.second
                }
            }
        }
    }

    companion object {
        private const val TAG = "apps"
    }
}
