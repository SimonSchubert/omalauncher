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
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.PathParser
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
    /** The profile it's installed in: the launcher's own, or one beside it like a work profile, where the same app can be installed again. */
    val user: UserHandle,
    /** [user]'s serial number, unlike its id never reused; null for the launcher's own profile. */
    val profile: Long? = null,
    /** Preinstalled and never updated-in-place away from that: can't be uninstalled, only disabled. */
    val system: Boolean = false,
    /** [user] is a clone profile, holding a second copy of an app the owner has too, rather than a work profile. */
    val clone: Boolean = false,
    /** [user] is the private space (Android 15 on), which only the default launcher holding ACCESS_HIDDEN_PROFILES sees. */
    val private: Boolean = false,
) {
    /** Stable across restarts and unique across profiles; icons, usage and pinned apps go by it. */
    val key get() = androidAppKey(component, profile)

    /** What screen readers say: "Work WhatsApp", "Clone WhatsApp" or "Private WhatsApp" beside "WhatsApp". */
    val spoken get() = spokenLabel(label, profile, clone, private)

    /** In a work profile: neither the launcher's own, a clone nor the private space. */
    val work get() = profile != null && !clone && !private
}

/** [label] as screen readers say it, worded as Android's own badged labels; [profile], [clone] and [private] as in [AndroidApp]. */
fun spokenLabel(
    label: String,
    profile: Long?,
    clone: Boolean = false,
    private: Boolean = false,
) = when {
    profile == null -> label
    clone -> "Clone $label"
    private -> "Private $label"
    else -> "Work $label"
}

/** The activity alone in the launcher's own profile, as keys were before other profiles were listed; `@serial` in any other. */
fun androidAppKey(
    component: ComponentName,
    profile: Long?,
) = component.flattenToShortString() + (profile?.let { "@$it" } ?: "")

/**
 * The phone's launchable apps, loaded once per process and kept current through
 * LauncherApps callbacks, so the drawer never waits on PackageManager.
 * Labels arrive first; icons fill in behind them. Apps in a work profile are listed beside the
 * owner's with a briefcase on their icons, apps in a clone profile with the clone mark stock
 * launchers use, and the private space's with its mark; Android only shows that one to the default
 * launcher holding ACCESS_HIDDEN_PROFILES. Locked, its apps are to show nowhere.
 */
class AndroidAppCatalog(
    private val context: Context,
) {
    val apps = mutableStateListOf<AndroidApp>()
    /** By [AndroidApp.key]. */
    val icons = mutableStateMapOf<String, ImageBitmap>()

    /** Alpha-only silhouettes of the same icons, for the monochrome icon style. */
    val masks = mutableStateMapOf<String, ImageBitmap>()

    /** Serial numbers of the profiles whose apps are paused (work apps turned off); their apps show dimmed. */
    var pausedProfiles by mutableStateOf(emptySet<Long>())
        private set

    /** Whether an app in [profile] (as in [AndroidApp.profile]) is paused. */
    fun paused(profile: Long?) = profile != null && profile in pausedProfiles

    /** Serial numbers of the clone profiles, whose apps are second copies of the owner's, not work apps. */
    var cloneProfiles by mutableStateOf(emptySet<Long>())
        private set

    /** Whether [profile] (as in [AndroidApp.profile]) is a clone profile. */
    fun cloned(profile: Long?) = profile != null && profile in cloneProfiles

    /** Serial numbers of the work profiles (Android allows one); the drawer gives their apps a tab of their own, even while there are none. */
    var workProfiles by mutableStateOf(emptySet<Long>())
        private set

    /** Serial number of the private space (one at most), while there is one. Locked is paused: both are quiet mode. */
    var privateProfiles by mutableStateOf(emptySet<Long>())
        private set

    /** True for the private space's apps. */
    fun isPrivate(profile: Long?) = profile != null && profile in privateProfiles

    /** True while [profile] is the locked private space: its apps aren't to be listed or found, as Android asks of launchers. */
    fun isLocked(profile: Long?) = isPrivate(profile) && paused(profile)

    /** True while there's a private space and it's locked. */
    val privateLocked: Boolean get() = privateProfiles.any { it in pausedProfiles }

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val prefs = context.getSharedPreferences("apps", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** False until the first reload: which profile is which isn't known yet. */
    private var loaded by mutableStateOf(false)

    private var hiding by mutableStateOf(prefs.getBoolean(HIDE_PRIVATE, false))

    /**
     * The private space vanishes while it's locked: no tab, no home tiles; typing "priv" offers to unlock it.
     * Android's own switch for that (hide_privatespace_entry_point) can't be read by other apps, so the launcher keeps its own.
     */
    var hidePrivate: Boolean
        get() = hiding
        set(value) {
            hiding = value
            prefs.edit().putBoolean(HIDE_PRIVATE, value).apply()
        }

    /** True while the private space shows nowhere: locked, and set to hide. */
    val privateHidden: Boolean get() = hidePrivate && privateLocked

    /**
     * True while [profile]'s apps show nowhere, not even dimmed on home: the hidden private space's.
     * Until the profiles are loaded, any profile but the phone's own might be that.
     */
    fun concealed(profile: Long?) = profile != null && hidePrivate && (!loaded || isLocked(profile))

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
        // A work profile added, removed, paused or resumed; the package callback doesn't tell.
        val profileChanged =
            IntentFilter().apply {
                addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
                addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                if (Build.VERSION.SDK_INT >= 35) {
                    addAction(Intent.ACTION_PROFILE_ADDED)
                    addAction(Intent.ACTION_PROFILE_REMOVED)
                    addAction(Intent.ACTION_PROFILE_AVAILABLE)
                    addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
                }
            }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) = reload()
            }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, profileChanged, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, profileChanged)
        reload()
    }

    /**
     * Opens [app] in its profile. One in a paused work profile asks to turn work apps back on
     * instead (the system may ask for the work PIN first); a second tap then opens it.
     */
    fun open(app: AndroidApp) = open(app.component, app.user)

    /** Opens a pinned app; [profile] as in [AndroidApp.profile]. */
    fun open(
        component: ComponentName,
        profile: Long?,
    ) {
        val user = if (profile == null) Process.myUserHandle() else userManager.getUserForSerialNumber(profile)
        if (user == null) {
            Log.w(TAG, "cannot open $component: its profile $profile is gone")
            return
        }
        open(component, user)
    }

    private fun open(
        component: ComponentName,
        user: UserHandle,
    ) {
        if (userManager.isQuietModeEnabled(user)) {
            runCatching { userManager.requestQuietModeEnabled(false, user) }.onFailure { Log.w(TAG, "cannot resume profile $user", it) }
            return
        }
        // Uninstalled or disabled since the list was loaded.
        runCatching { launcherApps.startMainActivity(component, user, null, null) }
            .onFailure { Log.w(TAG, "cannot open $component in $user", it) }
    }

    /**
     * Pauses the work profiles or turns them back on, like stock launchers' work apps switch.
     * The system only lets the default launcher, while it's in front; turning them on may ask for the work PIN.
     */
    fun pauseWork(pause: Boolean) = quiet(workProfiles, pause)

    /** Locks or unlocks the private space, like stock launchers' lock button; unlocking may ask for its PIN or fingerprint. */
    fun lockPrivate(lock: Boolean) = quiet(privateProfiles, lock)

    private fun quiet(
        profiles: Set<Long>,
        quiet: Boolean,
    ) {
        for (profile in profiles) {
            val user = userManager.getUserForSerialNumber(profile) ?: continue
            runCatching { userManager.requestQuietModeEnabled(quiet, user) }
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
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
            .onFailure { Log.w(TAG, "cannot open info for ${app.component}", it) }
    }

    /** Asks the system to uninstall; the list updates through the package callback. */
    fun uninstall(app: AndroidApp) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.component.packageName))
                    .putExtra(Intent.EXTRA_USER, app.user)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "cannot uninstall ${app.component}", it) }
    }

    private fun reload(evict: String? = null) {
        loading?.cancel()
        loading =
            scope.launch {
                if (evict != null) {
                    // In every profile: an update in one doesn't say which, and reloading an icon is cheap.
                    icons.keys.removeAll { it.startsWith("$evict/") }
                    masks.keys.removeAll { it.startsWith("$evict/") }
                }
                var paused = emptySet<Long>()
                var clones = emptySet<Long>()
                var works = emptySet<Long>()
                var privates = emptySet<Long>()
                val infos =
                    withContext(Dispatchers.IO) {
                        val me = Process.myUserHandle()
                        // Ours first; the system lists the profiles it lets a launcher see, ours included.
                        val profiles = listOf(me) + runCatching { launcherApps.profiles }.getOrDefault(emptyList()).filter { it != me }
                        profiles
                            .flatMap { user ->
                                val profile = if (user == me) null else userManager.getSerialNumberForUser(user)
                                val type = if (profile == null) null else userType(user)
                                val clone = type == UserManager.USER_TYPE_PROFILE_CLONE
                                val private = type == UserManager.USER_TYPE_PROFILE_PRIVATE
                                if (profile != null && userManager.isQuietModeEnabled(user)) paused += profile
                                if (profile != null && clone) clones += profile
                                if (profile != null && private) privates += profile
                                if (profile != null && !clone && !private) works += profile
                                runCatching { launcherApps.getActivityList(null, user) }
                                    .onFailure { Log.w(TAG, "cannot list apps in $user", it) }
                                    .getOrDefault(emptyList())
                                    .filter { it.componentName.packageName != context.packageName }
                                    .map { it to AndroidApp(it.label.toString(), it.componentName, user, profile, it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0, clone = clone, private = private) }
                            }
                            // The same app in two profiles: the owner's one first.
                            .sortedWith(compareBy({ it.second.label.lowercase() }, { it.second.profile != null }))
                    }
                pausedProfiles = paused
                cloneProfiles = clones
                workProfiles = works
                privateProfiles = privates
                loaded = true
                val list = infos.map { it.second }
                if (list != apps.toList()) {
                    apps.clear()
                    apps += list
                }
                val present = list.mapTo(HashSet()) { it.key }
                icons.keys.retainAll(present)
                masks.keys.retainAll(present)
                for ((info, app) in infos) {
                    if (app.key in icons) continue
                    launch {
                        // Both styles are rendered up front so switching is instant; masks are 1 byte per pixel.
                        val rendered =
                            withContext(iconDispatcher) {
                                runCatching {
                                    val d = info.getIcon(0)
                                    val bmp = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
                                    d.setBounds(0, 0, iconPx, iconPx)
                                    d.draw(Canvas(bmp))
                                    var mask = iconMask(d, iconPx)
                                    if (app.profile != null) {
                                        mask = addBadge(bmp, mask, if (app.clone) cloneMark else if (app.private) privateMark else briefcase)
                                    }
                                    bmp.asImageBitmap() to mask.asImageBitmap()
                                }.getOrNull()
                            }
                        if (rendered != null) {
                            icons[app.key] = rendered.first
                            masks[app.key] = rendered.second
                        }
                    }
                }
            }
    }

    /**
     * [user]'s kind, as in UserManager.USER_TYPE_PROFILE_CLONE. Only Android 15 on tells a launcher;
     * before, it's null and every profile beside ours counts as work (there's no private space yet).
     */
    private fun userType(user: UserHandle): String? =
        if (Build.VERSION.SDK_INT >= 35) runCatching { launcherApps.getLauncherUserInfo(user)?.userType }.getOrNull() else null

    /**
     * Marks an icon from another profile with [mark] (a briefcase, or the clone mark) in its corner, and its
     * silhouette too, where a gap cut around the mark keeps it apart from the logo in one color. Drawn here rather
     * than taken from the system (PackageManager.getUserBadgedIcon), which leaves some work profiles unbadged.
     */
    private fun addBadge(
        icon: Bitmap,
        silhouette: Bitmap,
        mark: Path,
    ): Bitmap {
        val mask = if (silhouette.isMutable) silhouette else silhouette.copy(Bitmap.Config.ALPHA_8, true)
        val r = iconPx * 0.2f
        val cx = iconPx - r
        val cy = iconPx - r
        val glyph = Path(mark)
        val scale = r * 1.2f / 24f
        glyph.transform(Matrix().apply { setScale(scale, scale); postTranslate(cx - 12 * scale, cy - 12 * scale) })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        Canvas(icon).run {
            drawCircle(cx, cy, r, paint.apply { color = BADGE_BACKGROUND })
            drawPath(glyph, paint.apply { color = Color.WHITE })
        }
        Canvas(mask).run {
            drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
            drawPath(glyph, paint)
        }
        return mask
    }

    companion object {
        private const val TAG = "apps"

        /** Material's "work" glyph, 24 units square. */
        private val briefcase by lazy {
            PathParser()
                .parsePathString(
                    "M20,6h-4V4c0,-1.11 -0.89,-2 -2,-2h-4c-1.11,0 -2,0.89 -2,2v2H4c-1.11,0 -1.99,0.89 -1.99,2L2,19c0,1.11 0.89,2 2,2h16" +
                        "c1.11,0 2,-0.89 2,-2V8c0,-1.11 -0.89,-2 -2,-2zM14,6h-4V4h4v2z",
                ).toPath()
                .asAndroidPath()
        }

        /** Launcher3's clone mark (ic_clone_app_badge), 24 units square: a disc, and a ring overlapping it. */
        private val cloneMark by lazy {
            val disc =
                PathParser()
                    .parsePathString("M22,9.5C22,13.642 18.642,17 14.5,17C10.358,17 7,13.642 7,9.5C7,5.358 10.358,2 14.5,2C18.642,2 22,5.358 22,9.5Z")
                    .toPath()
                    .asAndroidPath()
            val ring =
                PathParser()
                    .parsePathString(
                        "M9.5,20.333C12.722,20.333 15.333,17.722 15.333,14.5C15.333,11.278 12.722,8.667 9.5,8.667C6.278,8.667 3.667,11.278 3.667,14.5" +
                            "C3.667,17.722 6.278,20.333 9.5,20.333ZM9.5,22C13.642,22 17,18.642 17,14.5C17,10.358 13.642,7 9.5,7C5.358,7 2,10.358 2,14.5" +
                            "C2,18.642 5.358,22 9.5,22Z",
                    ).toPath()
                    .asAndroidPath()
                    .apply { fillType = Path.FillType.EVEN_ODD }
            // Two paths in Launcher3, one here: the ring's even-odd fill must not cut into the disc.
            disc.apply { op(ring, Path.Op.UNION) }
        }

        /** Launcher3's private space mark (ic_private_profile_app_badge), 24 units square: a shield with a keyhole. */
        private val privateMark by lazy {
            val shield =
                PathParser()
                    .parsePathString(
                        "M12,2L4,5V11.09C4,16.14 7.41,20.85 12,22C16.59,20.85 20,16.14 20,11.09V5L12,2ZM15,15V17H13V18H11V12.84" +
                            "C9.56,12.41 8.5,11.09 8.5,9.5C8.5,7.57 10.07,6 12,6C13.93,6 15.5,7.57 15.5,9.5C15.5,11.08 14.44,12.41 13,12.84V15H15Z",
                    ).toPath()
                    .asAndroidPath()
                    .apply { fillType = Path.FillType.EVEN_ODD }
            val bow =
                PathParser()
                    .parsePathString("M12,11C12.828,11 13.5,10.328 13.5,9.5C13.5,8.672 12.828,8 12,8C11.172,8 10.5,8.672 10.5,9.5C10.5,10.328 11.172,11 12,11Z")
                    .toPath()
                    .asAndroidPath()
            // Two paths in Launcher3, one here, as with the clone mark.
            shield.apply { op(bow, Path.Op.UNION) }
        }

        private const val HIDE_PRIVATE = "hide_private"

        private const val BADGE_BACKGROUND = 0xFF3C4043.toInt()
    }
}
