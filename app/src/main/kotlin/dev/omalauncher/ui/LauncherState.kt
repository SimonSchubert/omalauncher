package dev.omalauncher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers

/** App panel position (above the bar): 0 = closed, 1 = open. Follows the finger while dragging. */
class LauncherState {
    val drawer = Animatable(0f)
    var styleOpen by mutableStateOf(false)

    /** The community themes, from the home menu's theme strip or over the style screen. */
    var themeBrowserOpen by mutableStateOf(false)
    var agentsOpen by mutableStateOf(false)
    var storeOpen by mutableStateOf(false)
    var settingsOpen by mutableStateOf(false)
    var packagesOpen by mutableStateOf(false)
    var licensesOpen by mutableStateOf(false)

    /** The Linux sheet: install, progress, remove. Opened by anything that needs Linux. */
    var linuxOpen by mutableStateOf(false)

    /** The widget whose share panel is open: a QR code and link, or the store. */
    var sharing by mutableStateOf<dev.omalauncher.widgets.WidgetSpec?>(null)

    /** A widget link that was opened (scanned from a QR code), waiting for the user to read it and add it. */
    var shared by mutableStateOf<String?>(null)

    /** The menu from long-pressing empty home space. */
    var homeMenuOpen by mutableStateOf(false)

    /** The open drawer or a full-screen panel hides the home screen; animated widgets stop ticking under it. */
    val homeCovered: Boolean
        get() = drawer.value > 0.99f || settingsOpen || styleOpen || themeBrowserOpen || agentsOpen || storeOpen || packagesOpen || linuxOpen || licensesOpen || shared != null || sharing != null

    /** Closes every overlay screen (settings and what it opens). */
    fun closeScreens() {
        styleOpen = false
        themeBrowserOpen = false
        agentsOpen = false
        storeOpen = false
        packagesOpen = false
        linuxOpen = false
        licensesOpen = false
        settingsOpen = false
        homeMenuOpen = false
        shared = null
        sharing = null
    }

    /** Key of the home tile in edit mode (handles and action bar shown), if any. */
    var editing by mutableStateOf<String?>(null)

    /** How far the home pages are scrolled, 0 (first) to 1 (last); the background slides along. */
    var wallpaperOffset by mutableFloatStateOf(0.5f)

    /**
     * The bar's height in px with its gaps above and below, as last laid out over home (0 until then). A large
     * system font makes it taller than [PromptBarHeight]; home ends its grid above what it really takes.
     */
    var barHeight by mutableIntStateOf(0)

    /** Home page currently showing (1-based); the drawer pins apps there. */
    var currentPage by mutableStateOf(1)

    /** The drawer's tab; it stays picked the next time the drawer opens. */
    var tab by mutableStateOf(DrawerTab.Personal)

    private var typed by mutableStateOf("")

    /** What's in the bar: an app to find, or a prompt for the agent. */
    var query: String
        get() = typed
        set(value) {
            if (value != typed) picked = null
            typed = value
        }

    /** The result chosen with Tab or the arrow keys (-1 = ask the agent); null follows the ranking. Reset by typing. */
    var picked by mutableStateOf<Int?>(null)

    /** Asks the prompt bar to start typing (Super+A) or to send (the ask row); consumed by the bar. */
    var promptRequest by mutableStateOf<PromptRequest?>(null)

    /** Home page to scroll to (e.g. from Super+number); consumed by the pager. */
    var requestedPage by mutableStateOf<Int?>(null)

    /** A composition scope: animations need its frame clock. Null until the UI is composed. */
    private var composeScope: CoroutineScope? = null
    private val scope get() = composeScope ?: CoroutineScope(Dispatchers.Main)

    @Composable
    fun bindScope() {
        val s = rememberCoroutineScope()
        SideEffect { composeScope = s }
    }

    /** Animates when composed; before that (e.g. Home pressed during startup) just jumps. */
    private fun animate(
        target: Float,
        stiffness: Float,
    ) = composeScope?.launch { drawer.animateTo(target, spring(stiffness = stiffness)) }
        ?: scope.launch { drawer.snapTo(target) }

    fun open() = animate(1f, Spring.StiffnessMediumLow)

    fun close() = animate(0f, Spring.StiffnessMedium)

    /**
     * Closes without animating. Used when leaving the launcher: animations pause
     * while it's in the background and could leave an invisible drawer catching touches.
     */
    fun closeNow() = scope.launch { drawer.snapTo(0f) }

    fun toggle() = if (drawer.targetValue > 0.5f) close() else open()

    /** Direction of the most recent drag step; a fallback when no fling velocity is reported. */
    private var lastDragUp = false

    fun dragBy(fraction: Float) = scope.launch {
        if (fraction != 0f) lastDragUp = fraction > 0
        drawer.snapTo((drawer.value + fraction).coerceIn(0f, 1f)) }

    /** Settles after a drag; [velocity] is in px/s, negative = upwards. */
    fun settle(velocity: Float) {
        when {
            velocity < -800f -> open()
            velocity > 800f -> close()
            // Compose sometimes reports 0 velocity for the first gesture after resuming.
            velocity == 0f && drawer.value > 0.05f -> if (lastDragUp) open() else close()
            drawer.value > 0.2f -> open()
            else -> close()
        }
    }
}
