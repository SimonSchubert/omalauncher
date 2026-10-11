package dev.omalauncher

import android.app.ActivityOptions
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
    /** Serial number of the work profile (Shelter's, say) or private space it's installed in; null in the phone's own profile. */
    val profile: Long? = null,
) {
    /** Tells the same app in two profiles apart: each has its own icon, usage and pin. */
    val id: String get() = androidAppId(component, profile)
}

/**
 * The phone's launchable apps, loaded once per process and kept current through
 * LauncherApps callbacks, so the drawer never waits on PackageManager.
 * Labels arrive first; icons fill in behind them. A work profile's apps (Shelter's)
 * and the private space's are listed too, as stock launchers do; other kinds of profile stay out.
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

    /** The private space by serial number (Android 15 on, one at most), true while locked. */
    val privateProfiles = mutableStateMapOf<Long, Boolean>()

    /** Runs after each reload, once [apps] and the profiles are current. */
    var onReload: () -> Unit = {}

    /** Serial numbers of profiles Android announced as removed while it's still tearing them down. */
    private val removedProfiles = HashSet<Long>()

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val prefs = context.getSharedPreferences("apps", Context.MODE_PRIVATE)

    /** False until the first reload: which profile is which isn't known yet. */
    private var loaded by mutableStateOf(false)

    private var hiding by mutableStateOf(prefs.getBoolean(HIDE_PRIVATE, false))

    /** The private space vanishes while it's locked: no tab, no home tiles; typing "private" offers to unlock it. */
    var hidePrivate: Boolean
        get() = hiding
        set(value) {
            hiding = value
            prefs.edit().putBoolean(HIDE_PRIVATE, value).apply()
        }
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
        // Profiles come and go, pause and resume (lock and unlock); only registered receivers hear of it.
        // The private space only tells through the newer broadcasts that name any kind of profile.
        val profileChanges =
            IntentFilter().apply {
                addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
                addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
                if (Build.VERSION.SDK_INT >= 34) {
                    addAction(Intent.ACTION_PROFILE_ADDED)
                    addAction(Intent.ACTION_PROFILE_REMOVED)
                }
                if (Build.VERSION.SDK_INT >= 35) {
                    addAction(Intent.ACTION_PROFILE_AVAILABLE)
                    addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
                }
            }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    // Android says so before it's done removing: until then the profile still answers to its serial number.
                    if (intent.action == Intent.ACTION_MANAGED_PROFILE_REMOVED || intent.action == Intent.ACTION_PROFILE_REMOVED) {
                        val user =
                            if (Build.VERSION.SDK_INT >= 33) {
                                intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(Intent.EXTRA_USER)
                            }
                        user?.let { users.getSerialNumberForUser(it) }?.takeIf { it >= 0 }?.let { removedProfiles += it }
                    }
                    reload()
                }
            }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, profileChanges, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, profileChanges)
        reload()
    }

    /** True while apps of [profile] can't open: it's a paused work profile or the locked private space. */
    fun isPaused(profile: Long?) = profile != null && (workProfiles[profile] == true || privateProfiles[profile] == true)

    /** True for the private space's apps. */
    fun isPrivate(profile: Long?) = profile != null && profile in privateProfiles

    /** True while [profile] is the locked private space: its apps aren't to be listed or found, as Android asks of launchers. */
    fun isLocked(profile: Long?) = profile != null && privateProfiles[profile] == true

    /** True while there's a private space and it's locked. */
    val privateLocked: Boolean get() = privateProfiles.values.any { it }

    /** True while the private space shows nowhere: locked, and set to hide. */
    val privateHidden: Boolean get() = hidePrivate && privateLocked

    /**
     * True while [profile]'s apps show nowhere, not even greyed on home: the hidden private space's.
     * Until the profiles are loaded, any profile but the phone's own might be that.
     */
    fun concealed(profile: Long?) = profile != null && hidePrivate && (!loaded || privateProfiles[profile] == true)

    /** False once [profile] is deleted, for good: Android never hands its serial number out again. Paused still exists. */
    fun exists(profile: Long) = profile !in removedProfiles && users.getUserForSerialNumber(profile) != null

    fun open(app: AndroidApp) = open(app.component, app.profile)

    /**
     * Opens [component] in [profile] (null: the phone's own). A paused work profile or locked
     * private space is asked to turn back on instead; the system may want its PIN first.
     */
    fun open(
        component: ComponentName,
        profile: Long?,
    ) {
        if (isPaused(profile)) return resume(profile)
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
    fun pauseWork(pause: Boolean) = quiet(workProfiles.keys, pause)

    /** Locks or unlocks the private space, like stock launchers' lock button; unlocking may ask for its PIN or fingerprint. */
    fun lockPrivate(lock: Boolean) = quiet(privateProfiles.keys, lock)

    /** Brings [profile] back: resumes work apps, or unlocks the private space. */
    fun resume(profile: Long?) = if (isPrivate(profile)) lockPrivate(false) else pauseWork(false)

    private fun quiet(
        profiles: Collection<Long>,
        quiet: Boolean,
    ) {
        for (profile in profiles.toList()) {
            runCatching { users.requestQuietModeEnabled(quiet, user(profile)) }
                .onFailure { Log.w(TAG, "cannot ${if (quiet) "pause" else "resume"} profile $profile", it) }
        }
    }

    /** The system's private space settings, which stock launchers' private space menu opens; Android 16 on. */
    fun openPrivateSettings() {
        if (Build.VERSION.SDK_INT < 36) return
        runCatching {
            val sender = checkNotNull(launcherApps.privateSpaceSettingsIntent) { "no private space settings" }
            // The system made the PendingIntent, so the start counts as from the background unless we, visible, vouch for it.
            val options = ActivityOptions.makeBasic().apply { setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE) }
            context.startIntentSender(sender, null, Intent.FLAG_ACTIVITY_NEW_TASK, Intent.FLAG_ACTIVITY_NEW_TASK, 0, options.toBundle())
        }.onFailure { Log.w(TAG, "cannot open private space settings", it) }
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

    private data class Profile(
        val user: UserHandle,
        /** Null for the phone's own. */
        val serial: Long?,
        val private: Boolean = false,
    )

    /** The phone's own profile, its work profiles and its private space. */
    private fun profiles(): List<Profile> {
        val me = Process.myUserHandle()
        return listOf(Profile(me, null)) +
            launcherApps.profiles.filter { it != me }.mapNotNull { user ->
                val private = isPrivateProfile(user) ?: return@mapNotNull null
                users.getSerialNumberForUser(user).takeIf { it >= 0 }?.let { Profile(user, it, private) }
            }
    }

    /**
     * True for the private space, false for a work profile, null for other kinds (a clone), which stay out.
     * Before Android 15 there's no private space, and nothing public tells a work profile from other kinds, so any counts as work.
     */
    private fun isPrivateProfile(user: UserHandle): Boolean? {
        if (Build.VERSION.SDK_INT < 35) return false
        return when (launcherApps.getLauncherUserInfo(user)?.userType) {
            UserManager.USER_TYPE_PROFILE_MANAGED -> false
            UserManager.USER_TYPE_PROFILE_PRIVATE -> true
            else -> null
        }
    }

    private fun reload(evict: String? = null) {
        loading?.cancel()
        loading =
            scope.launch {
                if (evict != null) {
                    // Ids start with the component, so with the package.
                    icons.keys.removeAll { it.substringBefore('/') == evict }
                    masks.keys.removeAll { it.substringBefore('/') == evict }
                }
                val (infos, others) =
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
                        // Paused or locked: both are quiet mode.
                        infos to profiles.filter { it.serial != null }.map { it to users.isQuietModeEnabled(it.user) }
                    }
                workProfiles.replaceWith(others.filter { !it.first.private }.associate { it.first.serial!! to it.second })
                privateProfiles.replaceWith(others.filter { it.first.private }.associate { it.first.serial!! to it.second })
                val list = infos.map { it.second }
                if (list != apps.toList()) {
                    apps.clear()
                    apps += list
                }
                loaded = true
                onReload()
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

    /** Takes [new]'s contents only if they differ, so what reads the map doesn't recompose for nothing. */
    private fun <K, V> MutableMap<K, V>.replaceWith(new: Map<K, V>) {
        if (new == toMap()) return
        clear()
        putAll(new)
    }

    companion object {
        private const val TAG = "apps"
        private const val HIDE_PRIVATE = "hide_private"
    }
}
