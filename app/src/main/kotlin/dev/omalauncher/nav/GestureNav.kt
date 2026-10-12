package dev.omalauncher.nav

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.omalauncher.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/** Where the launcher's gestures are: back from either side, or One UI's three zones along the bottom. */
enum class GestureLayout(
    /** Saved in preferences: never rename. */
    val id: String,
    val label: String,
) {
    SidesAndBottom("sides", "sides & bottom"),
    BottomZones("bottom", "bottom only"),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: SidesAndBottom
    }
}

/** How wide the strip along each side that starts a back swipe is. */
enum class BackSensitivity(
    /** Saved in preferences: never rename. */
    val id: String,
    val label: String,
    val widthDp: Int,
) {
    Low("low", "low", 12),
    Medium("medium", "medium", 18),
    High("high", "high", 26),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Medium
    }
}

data class NavSettings(
    /** The launcher's own gestures, drawn by [NavigationService], instead of only the phone's navigation bar. */
    val gestures: Boolean = false,
    val layout: GestureLayout = GestureLayout.SidesAndBottom,
    val sensitivity: BackSensitivity = BackSensitivity.Medium,
    /** Linux windows get the screen's sides for themselves (edge drags, scrollbars); back is then the bottom's only. */
    val linuxKeepsSides: Boolean = false,
    /** Swiping up lifts the app into a card under the finger, drawn from a screenshot. */
    val animation: Boolean = true,
    /** Swipe up and hold opens the launcher's recents ([RecentsView]) rather than the phone's. */
    val ownRecents: Boolean = true,
)

/**
 * Gesture navigation for phones that keep it from third-party launchers (Xiaomi, Poco): back, home and
 * recents from swipes at the screen's edges, performed by [NavigationService]. Settings are in
 * settings › navigation; the service follows them while it runs.
 */
class GestureNav(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences("navigation", Context.MODE_PRIVATE)

    private val _settings =
        MutableStateFlow(
            NavSettings(
                gestures = prefs.getBoolean(GESTURES, false),
                layout = GestureLayout.of(prefs.getString(LAYOUT, null)),
                sensitivity = BackSensitivity.of(prefs.getString(SENSITIVITY, null)),
                linuxKeepsSides = prefs.getBoolean(LINUX_SIDES, false),
                animation = prefs.getBoolean(ANIMATION, true),
                ownRecents = prefs.getBoolean(OWN_RECENTS, true),
            ),
        )
    val settings: StateFlow<NavSettings> = _settings

    /** Whether Android has [NavigationService] bound: it's turned on in the accessibility settings. */
    val running = MutableStateFlow(false)

    private val _sideHolds = MutableStateFlow<Set<Any>>(emptySet())

    /** Screens in front that need the screen's sides (see [HoldScreenSides]); the side strips step aside meanwhile. */
    val sideHolds: StateFlow<Set<Any>> = _sideHolds

    /** The home screen is in front: a swipe up there has no app to lift into a card. Set by MainActivity. */
    @Volatile
    var homeInFront = false

    /** The home screen's app drawer, while home is in front: a swipe up from the bottom edge there opens it. Set by MainActivity. */
    var homeDrawer: HomeDrawer? = null

    /** The launcher's recents: apps as they came to the front, with screenshots. */
    val recents = RecentApps()

    /** One of the launcher's own tasks came to the front (a Linux window, Files): recents lists it like an app. */
    fun ownTaskResumed(
        taskId: Int,
        label: String,
    ) = recents.toFront(recents.find(RecentKey.Task(taskId)) ?: RecentEntry(RecentKey.Task(taskId), label, null))

    /** A Linux window's title or icon changed. */
    fun ownTaskDescribed(
        taskId: Int,
        label: String,
        icon: android.graphics.Bitmap?,
    ) {
        val entry = recents.find(RecentKey.Task(taskId)) ?: return
        entry.label = label
        entry.icon = icon
    }

    fun ownTaskGone(taskId: Int) = recents.remove(RecentKey.Task(taskId))

    fun hold(owner: Any) = _sideHolds.update { it + owner }

    fun release(owner: Any) = _sideHolds.update { it - owner }

    fun update(change: (NavSettings) -> NavSettings) {
        val s = _settings.updateAndGet(change)
        prefs
            .edit()
            .putBoolean(GESTURES, s.gestures)
            .putString(LAYOUT, s.layout.id)
            .putString(SENSITIVITY, s.sensitivity.id)
            .putBoolean(LINUX_SIDES, s.linuxKeepsSides)
            .putBoolean(ANIMATION, s.animation)
            .putBoolean(OWN_RECENTS, s.ownRecents)
            .apply()
        if (s.gestures && running.value) PhoneNavBar.hideButtons(context) else PhoneNavBar.showButtons(context)
    }

    /** Opens Android's accessibility settings, where [NavigationService] is turned on. */
    fun openAccessibilitySettings() = start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /**
     * Opens Android's own page for the navigation mode. On Xiaomi's HyperOS this is the stock Android page,
     * which hides the buttons without putting the launcher back to Xiaomi's; its own settings page does that.
     */
    fun openSystemNavigationSettings() {
        val candidates =
            listOf(
                Intent("com.android.settings.NAVIGATION_MODE_SETTINGS").setPackage("com.android.settings"),
                Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$NavigationModeSettingsActivity")),
                Intent(Settings.ACTION_SETTINGS),
            )
        candidates.firstOrNull(::start)
    }

    private fun start(intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    private companion object {
        const val GESTURES = "gestures"
        const val LAYOUT = "layout"
        const val SENSITIVITY = "sensitivity"
        const val LINUX_SIDES = "linux_sides"
        const val ANIMATION = "animation"
        const val OWN_RECENTS = "own_recents"
    }
}

/** Home's app drawer, moved by a swipe up from the bottom edge as by a drag on the home screen itself. */
interface HomeDrawer {
    /** No panel (settings, a widget's screen) is over home: a swipe up is for the drawer, not to close it. */
    val available: Boolean

    /** Moves it by [fraction] of the screen's height, upwards positive. */
    fun drag(fraction: Float)

    /** Let go at [velocity] px/s, negative upwards: it opens or closes. */
    fun settle(velocity: Float)
}

/** The phone's own navigation, from Android's settings. */
enum class SystemNavigation(
    val label: String,
) {
    ThreeButtons("3 buttons"),
    TwoButtons("2 buttons"),
    Gestures("gestures"),
    ;

    companion object {
        /**
         * The mode the user can actually navigate with. On HyperOS, Xiaomi's switch ([XIAOMI_GESTURES]) is what
         * turns its gestures on; [MODE_KEY] can still say gestures, which hides the buttons and leaves no
         * navigation at all.
         */
        fun read(context: Context): SystemNavigation? = reading(context).effective

        /** Both values, so the settings screen can say when Android's page and Xiaomi's switch disagree. */
        fun reading(context: Context): NavReading {
            val xiaomi =
                runCatching { Settings.Global.getString(context.contentResolver, XIAOMI_GESTURES)?.toIntOrNull() }.getOrNull()
            val mode = runCatching { Settings.Secure.getInt(context.contentResolver, MODE_KEY) }.getOrNull()
            return NavReading(reported = mode?.let { entries.getOrNull(it) }, effective = effectiveMode(xiaomi, mode))
        }

        const val MODE_KEY = "navigation_mode"

        /** HyperOS's "full screen gestures" switch. 1 hides the buttons, 0 brings them back. Absent on other phones. */
        const val XIAOMI_GESTURES = "force_fsg_nav_bar"
    }
}

/** What Android reports, and what actually navigates. */
data class NavReading(
    val reported: SystemNavigation?,
    val effective: SystemNavigation?,
) {
    /** Android's page says gestures while Xiaomi's switch says buttons: the bar is hidden and swipes do nothing. */
    val split: Boolean get() = reported == SystemNavigation.Gestures && effective != SystemNavigation.Gestures
}

/**
 * HyperOS only treats its own switch as gestures. [navigationMode] saying gestures while that switch is off
 * is the stuck state: no buttons, and no gestures either. Elsewhere the switch is absent and Android's mode stands.
 */
internal fun effectiveMode(
    xiaomiGestures: Int?,
    navigationMode: Int?,
): SystemNavigation? {
    val reported = navigationMode?.let { SystemNavigation.entries.getOrNull(it) }
    if (xiaomiGestures == 0 && (reported == null || reported == SystemNavigation.Gestures)) return SystemNavigation.ThreeButtons
    if (xiaomiGestures == 1) return SystemNavigation.Gestures
    return reported
}

val Context.gestureNav get() = (applicationContext as App).gestureNav

/**
 * Keeps the launcher's back swipe off the screen's sides while [active] and the screen is in front, so a
 * drag that starts at an edge (resizing a tile) stays the screen's.
 */
@Composable
fun HoldScreenSides(active: Boolean) {
    val nav = LocalContext.current.gestureNav
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(active, lifecycle) {
        val owner = Any()
        val observer =
            LifecycleEventObserver { _, _ ->
                if (active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) nav.hold(owner) else nav.release(owner)
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            nav.release(owner)
        }
    }
}
