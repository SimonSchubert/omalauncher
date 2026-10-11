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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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

/**
 * An app's id across profiles: its component, and outside the phone's own profile that
 * profile's serial number too. Apps of the phone's own profile keep the bare component they always had.
 */
fun androidAppId(
    component: ComponentName,
    profile: Long?,
): String = component.flattenToShortString() + profile?.let { "@$it" }.orEmpty()

data class AndroidApp(
    val label: String,
    val component: ComponentName,
    /** Preinstalled and never updated-in-place away from that: can't be uninstalled, only disabled. */
    val system: Boolean = false,
    /** Serial number of the work profile it's installed in (Shelter's, say); null in the phone's own profile. */
    val profile: Long? = null,
) {
    /** Tells the same app in two profiles apart: each has its own icon, usage and pin. */
    val id: String get() = androidAppId(component, profile)
}

/**
 * The phone's launchable apps, loaded once per process and kept current through
 * LauncherApps callbacks, so the drawer never waits on PackageManager.
 * Labels arrive first; icons fill in behind them. A work profile's apps (Shelter's)
 * are listed too, as stock launchers do; other kinds of profile stay out.
 */
class AndroidAppCatalog(
    private val context: Context,
) {
    val apps = mutableStateListOf<AndroidApp>()

    /** Icons by [AndroidApp.id]. */
    val icons = mutableStateMapOf<String, ImageBitmap>()

    /** Alpha-only silhouettes of the same icons, for the monochrome icon style. */
    val masks = mutableStateMapOf<String, ImageBitmap>()

    /** The work profiles by serial number (Android allows one), each true while paused. */
    val workProfiles = mutableStateMapOf<Long, Boolean>()

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)
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
        // Work profiles come and go, pause and resume; only registered receivers hear of it.
        val profileChanges =
            IntentFilter().apply {
                addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
                addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
            }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) = reload()
            }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, profileChanges, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, profileChanges)
        reload()
    }

    /** True while apps of [profile] can't open: it's a paused work profile. */
    fun isPaused(profile: Long?) = profile != null && workProfiles[profile] == true

    fun open(app: AndroidApp) = open(app.component, app.profile)

    /**
     * Opens [component] in [profile] (null: the phone's own). A paused work profile is
     * asked to turn back on instead; the system may want its PIN first.
     */
    fun open(
        component: ComponentName,
        profile: Long?,
    ) {
        if (isPaused(profile)) return pauseWork(false)
        runCatching {
            if (profile == null) {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                )
            } else {
                // Another profile's activities only start through LauncherApps.
                launcherApps.startMainActivity(component, user(profile), null, null)
            }
        }.onFailure { Log.w(TAG, "cannot open $component", it) }
    }

    /**
     * Pauses or resumes the work profiles, like stock launchers' work apps switch.
     * The system only lets the default launcher, while it's in front.
     */
    fun pauseWork(pause: Boolean) {
        for (profile in workProfiles.keys.toList()) {
            runCatching { users.requestQuietModeEnabled(pause, user(profile)) }
                .onFailure { Log.w(TAG, "cannot ${if (pause) "pause" else "resume"} work profile $profile", it) }
        }
    }

    /** The system's App info page, as stock launchers open it. */
    fun openInfo(app: AndroidApp) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, user(app.profile), null, null) }
            .onFailure { Log.w(TAG, "cannot open info for ${app.component}", it) }
    }

    /** Asks the system to uninstall; the list updates through the package callback. */
    fun uninstall(app: AndroidApp) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.component.packageName))
                    // The uninstaller takes it from our work profile when told which.
                    .apply { if (app.profile != null) putExtra(Intent.EXTRA_USER, user(app.profile)) }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "cannot uninstall ${app.component}", it) }
    }

    /** The profile behind a serial number (null: the phone's own); throws once it's gone, for callers' runCatching. */
    private fun user(profile: Long?): UserHandle = if (profile == null) Process.myUserHandle() else checkNotNull(users.getUserForSerialNumber(profile)) { "no profile $profile" }

    /** The phone's own profile, with serial number null, and its work profiles. */
    private fun profiles(): List<Pair<UserHandle, Long?>> {
        val me = Process.myUserHandle()
        val work = launcherApps.profiles.filter { it != me && isWork(it) }
        return listOf<Pair<UserHandle, Long?>>(me to null) + work.mapNotNull { user -> users.getSerialNumberForUser(user).takeIf { it >= 0 }?.let { user to it } }
    }

    // Before Android 15 nothing public tells a work profile from other kinds (a clone), so any counts as work.
    private fun isWork(user: UserHandle) = if (Build.VERSION.SDK_INT >= 35) launcherApps.getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_MANAGED else true

    private fun reload(evict: String? = null) {
        loading?.cancel()
        loading =
            scope.launch {
                if (evict != null) {
                    // Ids start with the component, so with the package.
                    icons.keys.removeAll { it.substringBefore('/') == evict }
                    masks.keys.removeAll { it.substringBefore('/') == evict }
                }
                val (infos, paused) =
                    withContext(Dispatchers.IO) {
                        val profiles = profiles()
                        val infos =
                            profiles
                                .flatMap { (user, profile) ->
                                    runCatching { launcherApps.getActivityList(null, user) }
                                        .onFailure { Log.w(TAG, "cannot list apps of $user", it) }
                                        .getOrDefault(emptyList())
                                        .filter { it.componentName.packageName != context.packageName }
                                        .map { it to AndroidApp(it.label.toString(), it.componentName, it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0, profile) }
                                }.sortedBy { it.second.label.lowercase() }
                        infos to profiles.mapNotNull { (user, profile) -> profile?.let { it to users.isQuietModeEnabled(user) } }.toMap()
                    }
                if (paused != workProfiles.toMap()) {
                    workProfiles.clear()
                    workProfiles.putAll(paused)
                }
                val list = infos.map { it.second }
                if (list != apps.toList()) {
                    apps.clear()
                    apps += list
                }
                val present = list.mapTo(HashSet()) { it.id }
                icons.keys.retainAll(present)
                masks.keys.retainAll(present)
                for ((info, app) in infos) {
                    if (app.id in icons) continue
                    launch {
                        // Both styles are rendered up front so switching is instant; masks are 1 byte per pixel.
                        val rendered =
                            withContext(iconDispatcher) {
                                runCatching {
                                    val d = info.getIcon(0)
                                    val bmp = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
                                    d.setBounds(0, 0, iconPx, iconPx)
                                    d.draw(Canvas(bmp))
                                    bmp.asImageBitmap() to iconMask(d, iconPx).asImageBitmap()
                                }.getOrNull()
                            }
                        if (rendered != null) {
                            icons[app.id] = rendered.first
                            masks[app.id] = rendered.second
                        }
                    }
                }
            }
    }

    companion object {
        private const val TAG = "apps"
    }
}
