package dev.omalauncher

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.LauncherUserInfo
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.compose.runtime.derivedStateOf
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
    /** [user] is the private space: listed only while it's unlocked, on the drawer's private tab, and never on home or in usage. */
    val privateSpace: Boolean = false,
) {
    /** Stable across restarts and unique across profiles; icons, usage and pinned apps go by it. */
    val key get() = androidAppKey(component, profile)

    /** What screen readers say: "Work WhatsApp", "Clone WhatsApp" or "Private WhatsApp" beside "WhatsApp". */
    val spoken get() = spokenLabel(label, profile, clone, privateSpace)

    /** In a work profile: not the launcher's own, a clone or the private space. */
    val work get() = profile != null && !clone && !privateSpace
}

/** [label] as screen readers say it, worded as Android's own badged labels; [profile], [clone] and [privateSpace] as in [AndroidApp]. */
fun spokenLabel(
    label: String,
    profile: Long?,
    clone: Boolean = false,
    privateSpace: Boolean = false,
) = when {
    profile == null -> label
    clone -> "Clone $label"
    privateSpace -> "Private $label"
    else -> "Work $label"
}

/** The activity alone in the launcher's own profile, as keys were before other profiles were listed; `@serial` in any other. */
fun androidAppKey(
    component: ComponentName,
    profile: Long?,
) = component.flattenToShortString() + (profile?.let { "@$it" } ?: "")

/** One of the shortcuts an app made: the app's package, and the shortcut's id there. */
data class ShortcutRef(
    val packageName: String,
    val id: String,
)

/**
 * A shortcut an app put on the home screen once the user agreed (ShortcutManager.requestPinShortcut): a website
 * installed from a browser, a chat, a contact. Listed and opened like an app until it's removed, or its app is.
 */
data class AndroidShortcut(
    val label: String,
    val ref: ShortcutRef,
    /** As in [AndroidApp.user]: the profile of the app that made it. */
    val user: UserHandle,
    /** As in [AndroidApp.profile]. */
    val profile: Long? = null,
    /** As in [AndroidApp.clone]. */
    val clone: Boolean = false,
    /** As in [AndroidApp.privateSpace]. */
    val privateSpace: Boolean = false,
) {
    /** As [AndroidApp.key]: icons, usage and pinned tiles go by it. */
    val key get() = androidShortcutKey(ref, profile)

    val spoken get() = spokenLabel(label, profile, clone, privateSpace)

    /** As in [AndroidApp.work]: on the drawer's work tab. */
    val work get() = profile != null && !clone && !privateSpace
}

/**
 * The package first, as in an app's key, so an update of the app reloads these icons along with its own. The profile
 * comes before the id, which may hold any character, so no two shortcuts share a key.
 */
fun androidShortcutKey(
    ref: ShortcutRef,
    profile: Long?,
) = "${ref.packageName}/#${profile ?: ""}#${ref.id}"

/** What a shortcut is called on home and in the drawer. */
fun shortcutLabel(info: ShortcutInfo) = (info.shortLabel ?: info.longLabel)?.toString()?.takeIf { it.isNotBlank() } ?: info.id

/**
 * A shortcut an app offers in its long-press menu (Maps' "Navigate home", a chat): opened from there, or pinned
 * to become an [AndroidShortcut]. Only kept while the menu shows.
 */
class OfferedShortcut(
    val label: String,
    val ref: ShortcutRef,
    /** As in [AndroidApp.user]. */
    val user: UserHandle,
    /** Its icon and silhouette, badged as its app's; null if it has none. */
    val icon: ImageBitmap?,
    val mask: ImageBitmap?,
)

/**
 * Which of an app's shortcuts its long-press menu shows, as Launcher3 picks them: those from its manifest before
 * those it adds as it runs (recent chats), each by the rank the app gave it, at most [MAX_OFFERED].
 */
fun <T> offeredOrder(
    shortcuts: List<T>,
    dynamic: (T) -> Boolean,
    rank: (T) -> Int,
): List<T> = shortcuts.sortedWith(compareBy(dynamic, rank)).take(MAX_OFFERED)

/** How many shortcuts an app's long-press menu shows at most, as on stock launchers. */
const val MAX_OFFERED = 4

/** An app's pinned shortcut ids once [id] is pinned too: Android sets them all at once, and drops any left out. */
fun pinnedIds(
    pinned: List<String>,
    id: String,
) = (pinned + id).distinct()

/**
 * The private space (Android 15 on), a profile Android shows only to the default home app holding
 * ACCESS_HIDDEN_PROFILES. [locked] while Android keeps it shut: then none of its apps are listed, so nothing finds
 * them, search included, as Android asks of launchers. [hidden] while the user chose to hide it as it locks, here or
 * in Android's private space settings (16 on): the drawer then shows no tab for it, and only typing "private" offers
 * to unlock it.
 */
data class PrivateSpace(
    /** As in [AndroidApp.profile]. */
    val profile: Long,
    val locked: Boolean,
    val hidden: Boolean,
)

/**
 * The phone's launchable apps, loaded once per process and kept current through
 * LauncherApps callbacks, so the drawer never waits on PackageManager.
 * Labels arrive first; icons fill in behind them. Apps in a work profile are listed beside the
 * owner's with a briefcase on their icons, apps in a clone profile with the clone mark stock
 * launchers use, and apps in the private space with a lock, only while it's unlocked.
 */
class AndroidAppCatalog(
    private val context: Context,
) {
    val apps = mutableStateListOf<AndroidApp>()

    /** Shortcuts apps put on the home screen, by name; Android shows them only to the default home app. */
    val shortcuts = mutableStateListOf<AndroidShortcut>()

    /** By [AndroidApp.key] and [AndroidShortcut.key]. */
    val icons = mutableStateMapOf<String, ImageBitmap>()

    /** Alpha-only silhouettes of the same icons, for the monochrome icon style. */
    val masks = mutableStateMapOf<String, ImageBitmap>()

    /** Names by [AndroidApp.key] and [AndroidShortcut.key], in the phone's language now; home's tiles go by these. */
    val labels by derivedStateOf { apps.associate { it.key to it.label } + shortcuts.associate { it.key to it.label } }

    private val byKey by derivedStateOf { apps.associateBy { it.key } }

    /**
     * The app a home tile pinned as [component] in [profile] opens now. Apps that let you pick another icon (Duolingo,
     * X) do it by switching to another launcher activity, and an update may rename theirs, so a tile whose activity is
     * gone goes to its app's current one. Null while neither is listed.
     */
    fun pinned(
        component: ComponentName,
        profile: Long?,
    ): AndroidApp? =
        byKey[androidAppKey(component, profile)]
            ?: apps.firstOrNull { it.component.packageName == component.packageName && it.profile == profile }

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

    /** The private space, while there's one and Android lets this launcher see it (the default home app, Android 15 on); else null. */
    var privateSpace by mutableStateOf<PrivateSpace?>(null)
        private set

    /** Whether [profile] (as in [AndroidApp.profile]) is the private space's, locked or not. */
    fun isPrivate(profile: Long?) = profile != null && profile == privateSpace?.profile

    /** Whether [user] is the private space. */
    fun isPrivate(user: UserHandle) = user != Process.myUserHandle() && isPrivate(userManager.getSerialNumberForUser(user))

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    /** The launcher's own choice to hide the private space while it's locked, beside Android's. */
    private val privatePrefs = context.getSharedPreferences("private_space", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val iconDispatcher = Dispatchers.IO.limitedParallelism(4)
    private var loading: Job? = null

    /** Whether the last load could list [shortcuts]: only while this is the default home app. Long-press menus offer app shortcuts only then too. */
    var listsShortcuts by mutableStateOf(false)
        private set

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

                // Apps change their other shortcuts (a chat app's recent chats) often; only pinned ones are listed.
                override fun onShortcutsChanged(
                    packageName: String,
                    shortcuts: List<ShortcutInfo>,
                    user: UserHandle,
                ) {
                    if (shortcuts.any { it.isPinned } || this@AndroidAppCatalog.shortcuts.any { it.ref.packageName == packageName && it.user == user }) reloadShortcuts(packageName, user)
                }

                // Android 16 on: hiding the private space while it's locked was turned on or off in its settings.
                override fun onUserConfigChanged(info: LauncherUserInfo) = reload()
            },
        )
        // A work profile or the private space added, removed, paused or locked, resumed or unlocked, or the phone's
        // language changed (apps' names with it); the package callback doesn't tell.
        val changed =
            IntentFilter().apply {
                addAction(Intent.ACTION_LOCALE_CHANGED)
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
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, changed, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, changed)
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
        val user = userOf(profile)
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
        if (resume(user)) return
        // Uninstalled or disabled since the list was loaded.
        runCatching {
            if (user == Process.myUserHandle()) {
                // As Launcher3 does for its own profile; only an intent can ask for no open-an-app animation.
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            } else {
                launcherApps.startMainActivity(component, user, null, null)
            }
        }.onFailure { Log.w(TAG, "cannot open $component in $user", it) }
    }

    /** Opens [shortcut] through the app that made it; in a paused work profile, as [open] does for apps. */
    fun open(shortcut: AndroidShortcut) = open(shortcut.ref, shortcut.user)

    /** Opens a pinned shortcut; [profile] as in [AndroidShortcut.profile]. */
    fun open(
        ref: ShortcutRef,
        profile: Long?,
    ) {
        val user = userOf(profile)
        if (user == null) {
            Log.w(TAG, "cannot open shortcut $ref: its profile $profile is gone")
            return
        }
        open(ref, user)
    }

    private fun open(
        ref: ShortcutRef,
        user: UserHandle,
    ) {
        if (resume(user)) return
        // Removed or turned off by its app since the list was loaded, or this isn't the default home app anymore.
        runCatching { launcherApps.startShortcut(ref.packageName, ref.id, null, null, user) }
            .onFailure { Log.w(TAG, "cannot open shortcut $ref in $user", it) }
    }

    /** The profile [profile] is the serial number of, as in [AndroidApp.profile]; null once it's gone. */
    private fun userOf(profile: Long?): UserHandle? = if (profile == null) Process.myUserHandle() else userManager.getUserForSerialNumber(profile)

    /**
     * True if [user] is a paused work profile or the locked private space: asks to turn work apps back on or to unlock it
     * instead (the system may ask for the PIN first).
     */
    private fun resume(user: UserHandle): Boolean {
        if (!userManager.isQuietModeEnabled(user)) return false
        runCatching { userManager.requestQuietModeEnabled(false, user) }.onFailure { Log.w(TAG, "cannot resume profile $user", it) }
        return true
    }

    /**
     * Pins the shortcut an app asked to put on the home screen, once the user agreed, and returns it; null when it
     * can't be pinned anymore (the request was answered already, or this isn't the default home app now).
     */
    fun accept(request: LauncherApps.PinItemRequest): AndroidShortcut? {
        val info = request.shortcutInfo ?: return null
        // Home shows whatever's on it while the private space is locked: its apps' shortcuts stay off it.
        if (isPrivate(info.userHandle)) return null
        // Throws if it was accepted already.
        val pinned = runCatching { request.isValid && request.accept() }.onFailure { Log.w(TAG, "cannot pin ${info.id} of ${info.`package`}", it) }.getOrDefault(false)
        if (!pinned) return null
        reload()
        val user = info.userHandle
        val profile = serialOf(user)
        return AndroidShortcut(shortcutLabel(info), ShortcutRef(info.`package`, info.id), user, profile, cloned(profile), isPrivate(profile))
    }

    /**
     * The shortcuts [app] offers in its long-press menu, with their icons; none while this isn't the default home app,
     * or [app]'s profile is paused or locked. Blocks: call off the main thread.
     */
    fun offered(app: AndroidApp): List<OfferedShortcut> {
        if (!listsShortcuts || paused(app.profile)) return emptyList()
        val query =
            ShortcutQuery()
                .setPackage(app.component.packageName)
                .setActivity(app.component)
                .setQueryFlags(ShortcutQuery.FLAG_MATCH_MANIFEST or ShortcutQuery.FLAG_MATCH_DYNAMIC)
        val infos =
            runCatching { launcherApps.getShortcuts(query, app.user) }
                .onFailure { Log.w(TAG, "cannot list the shortcuts of ${app.component}", it) }
                .getOrNull()
                .orEmpty()
                .filter { it.isEnabled }
        val density = context.resources.displayMetrics.densityDpi
        return offeredOrder(infos, { it.isDynamic }, { it.rank }).map { info ->
            val icon =
                runCatching { launcherApps.getShortcutIconDrawable(info, density)?.let { render(it, markOf(app.profile)) } }
                    .onFailure { Log.w(TAG, "cannot load the icon of ${info.id}", it) }
                    .getOrNull()
            OfferedShortcut(shortcutLabel(info), ShortcutRef(info.`package`, info.id), app.user, icon?.first, icon?.second)
        }
    }

    /** Opens a shortcut from an app's long-press menu, as [open] does a pinned one. */
    fun open(shortcut: OfferedShortcut) = open(shortcut.ref, shortcut.user)

    /**
     * Pins a shortcut from an app's long-press menu, so it's listed and opens like one the app asked to pin, then
     * hands it to [onPinned]; not if it can't be pinned (this isn't the default home app anymore), nor from the
     * private space, whose apps stay off home.
     */
    fun pin(
        shortcut: OfferedShortcut,
        onPinned: (AndroidShortcut) -> Unit,
    ) {
        if (isPrivate(shortcut.user)) return
        scope.launch {
            val pinned =
                withContext(Dispatchers.IO) {
                    runCatching {
                        // Pinning sets all of an app's shortcuts at once: keep the ones pinned already.
                        val query = ShortcutQuery().setPackage(shortcut.ref.packageName).setQueryFlags(ShortcutQuery.FLAG_MATCH_PINNED)
                        val ids = launcherApps.getShortcuts(query, shortcut.user).orEmpty().map { it.id }
                        launcherApps.pinShortcuts(shortcut.ref.packageName, pinnedIds(ids, shortcut.ref.id), shortcut.user)
                    }.onFailure { Log.w(TAG, "cannot pin shortcut ${shortcut.ref}", it) }
                }
            if (pinned.isFailure) return@launch
            // The shortcut callback lists it in the drawer and draws its icon.
            val profile = serialOf(shortcut.user)
            onPinned(AndroidShortcut(shortcut.label, shortcut.ref, shortcut.user, profile, cloned(profile), isPrivate(profile)))
        }
    }

    /** Unpins [shortcut]: it leaves the drawer, and is gone unless its app keeps it for itself. */
    fun remove(shortcut: AndroidShortcut) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    // Pinning sets all of an app's shortcuts at once: keep its others, also turned-off ones the drawer leaves out.
                    val query = ShortcutQuery().setPackage(shortcut.ref.packageName).setQueryFlags(ShortcutQuery.FLAG_MATCH_PINNED)
                    val others = launcherApps.getShortcuts(query, shortcut.user).orEmpty().map { it.id }.filter { it != shortcut.ref.id }
                    launcherApps.pinShortcuts(shortcut.ref.packageName, others, shortcut.user)
                }.onFailure { Log.w(TAG, "cannot remove shortcut ${shortcut.ref}", it) }
            }
            reload()
        }
    }

    /** The icon of a shortcut an app asks to pin, as the drawer will show it; it isn't listed before it's pinned. Blocks: call off the main thread. */
    fun iconOf(info: ShortcutInfo): ImageBitmap? =
        runCatching {
            val profile = serialOf(info.userHandle)
            launcherApps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)?.let { render(it, markOf(profile)).first }
        }.onFailure { Log.w(TAG, "cannot load the icon of ${info.id}", it) }
            .getOrNull()

    /** Lists shortcuts once this became the default home app, and drops them once it isn't anymore; checked as home shows. */
    fun checkShortcutAccess() {
        scope.launch {
            if (withContext(Dispatchers.IO) { canListShortcuts() } != listsShortcuts) reload()
        }
    }

    private fun canListShortcuts() = runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)

    /** As in [AndroidApp.profile]. */
    private fun serialOf(user: UserHandle): Long? = if (user == Process.myUserHandle()) null else userManager.getSerialNumberForUser(user)

    /**
     * Pauses the work profiles or turns them back on, like stock launchers' work apps switch.
     * The system only lets the default launcher, while it's in front; turning them on may ask for the work PIN.
     */
    fun pauseWork(pause: Boolean) {
        for (profile in workProfiles) {
            val user = userManager.getUserForSerialNumber(profile) ?: continue
            runCatching { userManager.requestQuietModeEnabled(pause, user) }
                .onFailure { Log.w(TAG, "cannot ${if (pause) "pause" else "resume"} profile $profile", it) }
        }
    }

    /**
     * Locks the private space or unlocks it, as stock launchers' lock button does; unlocking asks for its PIN or
     * fingerprint. The system only lets the default launcher. Locking stops its apps, and they leave the list.
     */
    fun lockPrivate(lock: Boolean) {
        val user = privateSpace?.let { userManager.getUserForSerialNumber(it.profile) } ?: return
        runCatching { userManager.requestQuietModeEnabled(lock, user) }
            .onFailure { Log.w(TAG, "cannot ${if (lock) "lock" else "unlock"} the private space", it) }
    }

    /** Hides the private space while it's locked, or shows it then too; Android's own choice (16 on) hides it as well. */
    fun hidePrivate(hide: Boolean) {
        privatePrefs.edit().putBoolean(HIDE_PRIVATE, hide).apply()
        reload()
    }

    /** Whether Android's private space settings can be opened from here: from Android 16 on. */
    val privateSettings get() = Build.VERSION.SDK_INT >= 36

    /** Android's private space settings: its lock, hiding it while locked, deleting it. */
    fun openPrivateSettings() {
        if (Build.VERSION.SDK_INT < 36 || privateSpace == null) return
        runCatching { launcherApps.privateSpaceSettingsIntent?.let(::start) }
            .onFailure { Log.w(TAG, "cannot open the private space settings", it) }
    }

    /** The phone's app store, installing into the private space, as its install button on stock launchers does; while it's unlocked. */
    fun installPrivate() {
        val space = privateSpace?.takeIf { !it.locked } ?: return
        if (Build.VERSION.SDK_INT < 35) return
        val user = userManager.getUserForSerialNumber(space.profile) ?: return
        runCatching { launcherApps.getAppMarketActivityIntent(null, user)?.let(::start) }
            .onFailure { Log.w(TAG, "cannot open the app store in the private space", it) }
    }

    /** Starts what the system handed over to open, from home in front, as Launcher3 does. */
    private fun start(sender: IntentSender) {
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle() else null
        context.startIntentSender(sender, null, Intent.FLAG_ACTIVITY_NEW_TASK, Intent.FLAG_ACTIVITY_NEW_TASK, 0, options)
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
                var space: PrivateSpace? = null
                var canList = false
                // With what to draw each icon from; null for those of a paused profile, kept as they were.
                var made = emptyList<Pair<ShortcutInfo?, AndroidShortcut>>()
                val listed = shortcuts.toList()
                val infos =
                    withContext(Dispatchers.IO) {
                        val me = Process.myUserHandle()
                        // Ours first; the system lists the profiles it lets a launcher see, ours included.
                        val profiles = listOf(me) + runCatching { launcherApps.profiles }.getOrDefault(emptyList()).filter { it != me }
                        canList = canListShortcuts()
                        profiles
                            .flatMap { user ->
                                val profile = if (user == me) null else userManager.getSerialNumberForUser(user)
                                val type = if (profile == null) null else userType(user)
                                val clone = type == UserManager.USER_TYPE_PROFILE_CLONE
                                val secret = type == UserManager.USER_TYPE_PROFILE_PRIVATE
                                val quiet = profile != null && userManager.isQuietModeEnabled(user)
                                if (profile != null && secret) {
                                    space = PrivateSpace(profile, quiet, hiddenWhileLocked(user))
                                    // Locked: none of its apps or shortcuts are listed, so neither the drawer nor search nor widgets find them.
                                    if (quiet) return@flatMap emptyList()
                                }
                                if (profile != null && quiet) paused += profile
                                if (profile != null && clone) clones += profile
                                if (profile != null && !clone && !secret) works += profile
                                // A paused profile's shortcuts can't be listed; they stay, dimmed like its apps, and opening one resumes it.
                                if (canList) made += if (profile != null && profile in paused) listed.filter { it.profile == profile }.map { null to it } else pinnedShortcuts(user, profile, clone, secret)
                                runCatching { launcherApps.getActivityList(null, user) }
                                    .onFailure { Log.w(TAG, "cannot list apps in $user", it) }
                                    .getOrDefault(emptyList())
                                    .filter { it.componentName.packageName != context.packageName }
                                    .map { it to AndroidApp(it.label.toString(), it.componentName, user, profile, it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0, clone = clone, privateSpace = secret) }
                            }
                            // The same app in two profiles: the owner's one first.
                            .sortedWith(compareBy({ it.second.label.lowercase() }, { it.second.profile != null }))
                    }
                listsShortcuts = canList
                pausedProfiles = paused
                cloneProfiles = clones
                workProfiles = works
                privateSpace = space
                val list = infos.map { it.second }
                if (list != apps.toList()) {
                    apps.clear()
                    apps += list
                }
                made = made.sortedWith(compareBy(shortcutOrder) { it.second })
                val pinned = made.map { it.second }
                if (pinned != shortcuts.toList()) {
                    shortcuts.clear()
                    shortcuts += pinned
                }
                val present = (list.map { it.key } + pinned.map { it.key }).toHashSet()
                icons.keys.retainAll(present)
                masks.keys.retainAll(present)
                for ((info, app) in infos) {
                    if (app.key !in icons) loadIcon(app.key, markOf(app.profile)) { info.getIcon(0) }
                }
                // Apps change a shortcut's icon without an update, so these are drawn again each time; there are only a few.
                for ((info, shortcut) in made) {
                    if (info != null) loadIcon(shortcut.key, markOf(shortcut.profile)) { launcherApps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi) }
                }
            }
    }

    /**
     * Lists [packageName]'s pinned shortcuts in [user]'s profile again, and draws only their icons: apps such as chat apps
     * change their shortcuts often, and a full [reload] would list every app in every profile each time.
     */
    private fun reloadShortcuts(
        packageName: String,
        user: UserHandle,
    ) {
        // A full load under way may have listed them before the change: start it over instead.
        if (loading?.isActive == true || !listsShortcuts) return reload()
        scope.launch {
            val profile = serialOf(user)
            // The private space's shortcuts aren't listed while it's locked; one it changes then waits for it to unlock.
            if (privateSpace?.let { it.profile == profile && it.locked } == true) return@launch
            val made = withContext(Dispatchers.IO) { pinnedShortcuts(user, profile, cloned(profile), isPrivate(profile), packageName) }
            // One started meanwhile lists these too, and its list is the newer.
            if (loading?.isActive == true) return@launch
            val others = shortcuts.filterNot { it.ref.packageName == packageName && it.user == user }
            val pinned = (others + made.map { it.second }).sortedWith(shortcutOrder)
            if (pinned != shortcuts.toList()) {
                shortcuts.clear()
                shortcuts += pinned
            }
            val present = pinned.mapTo(HashSet()) { it.key }
            val gone = icons.keys.filterTo(HashSet()) { it.startsWith("$packageName/#") && it !in present }
            icons.keys.removeAll(gone)
            masks.keys.removeAll(gone)
            for ((info, shortcut) in made) loadIcon(shortcut.key, markOf(shortcut.profile)) { launcherApps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi) }
        }
    }

    /** The shortcuts pinned here in [user]'s profile, or only [packageName]'s; ones their app turned off (a chat that's gone) can't be opened, so they're left out. */
    private fun pinnedShortcuts(
        user: UserHandle,
        profile: Long?,
        clone: Boolean,
        secret: Boolean,
        packageName: String? = null,
    ): List<Pair<ShortcutInfo, AndroidShortcut>> =
        runCatching { launcherApps.getShortcuts(ShortcutQuery().setPackage(packageName).setQueryFlags(ShortcutQuery.FLAG_MATCH_PINNED), user) }
            .onFailure { Log.w(TAG, "cannot list shortcuts in $user", it) }
            .getOrNull()
            .orEmpty()
            .filter { it.isEnabled }
            .map { it to AndroidShortcut(shortcutLabel(it), ShortcutRef(it.`package`, it.id), user, profile, clone, secret) }

    /** Draws [key]'s icon off the main thread; both styles up front so switching is instant, and masks are 1 byte per pixel. */
    private fun CoroutineScope.loadIcon(
        key: String,
        mark: Path?,
        drawable: () -> Drawable?,
    ) = launch {
        val rendered = withContext(iconDispatcher) { runCatching { drawable()?.let { render(it, mark) } }.getOrNull() }
        if (rendered != null) {
            icons[key] = rendered.first
            masks[key] = rendered.second
        }
    }

    /** [d] at the drawer's size and its silhouette, marked with [mark] as [addBadge] does when it's from another profile ([markOf]). */
    private fun render(
        d: Drawable,
        mark: Path?,
    ): Pair<ImageBitmap, ImageBitmap> {
        val bmp = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, iconPx, iconPx)
        d.draw(Canvas(bmp))
        var mask = iconMask(d, iconPx)
        if (mark != null) mask = addBadge(bmp, mask, mark)
        return bmp.asImageBitmap() to mask.asImageBitmap()
    }

    /** What marks an icon from [profile] (as in [AndroidApp.profile]): a lock in the private space, the clone mark in a clone, else a briefcase; nothing in ours. */
    private fun markOf(profile: Long?): Path? =
        when {
            profile == null -> null
            isPrivate(profile) -> lock
            cloned(profile) -> cloneMark
            else -> briefcase
        }

    /**
     * [user]'s kind, as UserManager.USER_TYPE_PROFILE_CLONE or USER_TYPE_PROFILE_PRIVATE. Only Android 15 on tells a
     * launcher; before, every profile beside ours counts as work (and the private space doesn't exist).
     */
    private fun userType(user: UserHandle): String? =
        if (Build.VERSION.SDK_INT >= 35) runCatching { launcherApps.getLauncherUserInfo(user)?.userType }.getOrNull() else null

    /**
     * Whether the private space hides while it's locked: by the launcher's own choice, or Android's, which its
     * settings offer from Android 16 on, and which other apps can only read from there.
     */
    private fun hiddenWhileLocked(user: UserHandle): Boolean =
        privatePrefs.getBoolean(HIDE_PRIVATE, false) ||
            Build.VERSION.SDK_INT >= 36 &&
            runCatching { launcherApps.getLauncherUserInfo(user)?.userConfig?.getBoolean(LauncherUserInfo.PRIVATE_SPACE_ENTRYPOINT_HIDDEN) }.getOrNull() == true

    /**
     * Marks an icon from another profile with [mark] (a briefcase, the clone mark or a lock) in its corner, and its
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

        private const val HIDE_PRIVATE = "hide_while_locked"

        /** As apps are listed: by name, the owner's before another profile's. */
        private val shortcutOrder = compareBy<AndroidShortcut>({ it.label.lowercase() }, { it.profile != null })

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

        /** Material's "lock" glyph, 24 units square, for the private space as stock launchers mark it. */
        private val lock by lazy {
            PathParser()
                .parsePathString(
                    "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10" +
                        "c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6" +
                        "c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z",
                ).toPath()
                .asAndroidPath()
        }

        private const val BADGE_BACKGROUND = 0xFF3C4043.toInt()
    }
}
