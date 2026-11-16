package dev.omalauncher.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.omalauncher.storage.LauncherData
import dev.omalauncher.LockAdmin
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.theme.Style
import dev.omalauncher.theme.StyleStore
import dev.omalauncher.widgets.WidgetEngine
import dev.omalauncher.widgets.WidgetSpec
import dev.omalauncher.widgets.editWidgetCommand
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import dev.omalauncher.ui.BelowAnchorEnd
import dev.omalauncher.ui.ChoicePopup
import dev.omalauncher.ui.Glyph
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.OverlayPanel
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.ReadingWidth
import dev.omalauncher.ui.SectionLabel
import dev.omalauncher.ui.Symbol
import dev.omalauncher.ui.TextSize
import dev.omalauncher.ui.card
import kotlin.math.abs

/**
 * What the gestures on home do, kept in style.toml so agents can set them too: the file name of a panel
 * (`panel "…" { }`; swiping up opens drawer.owl, the app drawer, by default), "lock" to lock the screen,
 * "menu" for home's menu (themes, and new widgets, apps and panels with the agent), or "none".
 */
class Gestures(
    /** The app's, for home's error banner and settings, which say what a gesture does. */
    internal val context: Context,
    private val style: StyleStore,
) {
    /** drawer.owl by default: the app drawer, like stock launchers, written in owl. Saved as it's set. */
    var swipeUp: String
        get() = LauncherStyle.style[Style.SWIPE_UP]
        set(choice) = style.update { it.with(Style.SWIPE_UP, choice) }

    /** Nothing by default. Saved as it's set. */
    var swipeDown: String
        get() = LauncherStyle.style[Style.SWIPE_DOWN]
        set(choice) = style.update { it.with(Style.SWIPE_DOWN, choice) }

    /** Locking the screen by default, so the phone locks with a double tap as on stock launchers. Saved as it's set. */
    var doubleTap: String
        get() = LauncherStyle.style[Style.DOUBLE_TAP]
        set(choice) = style.update { it.with(Style.DOUBLE_TAP, choice) }

    /** Home's menu by default, where themes and agentic creation are. Saved as it's set. */
    var hold: String
        get() = LauncherStyle.style[Style.HOLD]
        set(choice) = style.update { it.with(Style.HOLD, choice) }

    /** Nothing by default. Saved as it's set. */
    var twoFingerUp: String
        get() = LauncherStyle.style[Style.TWO_FINGER_UP]
        set(choice) = style.update { it.with(Style.TWO_FINGER_UP, choice) }

    /** Nothing by default. Saved as it's set. */
    var twoFingerDown: String
        get() = LauncherStyle.style[Style.TWO_FINGER_DOWN]
        set(choice) = style.update { it.with(Style.TWO_FINGER_DOWN, choice) }

    /** What [gesture] is set to. */
    internal fun choice(gesture: HomeGesture): String =
        when (gesture) {
            HomeGesture.SwipeUp -> swipeUp
            HomeGesture.SwipeDown -> swipeDown
            HomeGesture.DoubleTap -> doubleTap
            HomeGesture.Hold -> hold
            HomeGesture.TwoFingerUp -> twoFingerUp
            HomeGesture.TwoFingerDown -> twoFingerDown
        }

    /** Sets [gesture] to [choice], saved as it's set. */
    internal fun pick(
        gesture: HomeGesture,
        choice: String,
    ) {
        when (gesture) {
            HomeGesture.SwipeUp -> swipeUp = choice
            HomeGesture.SwipeDown -> swipeDown = choice
            HomeGesture.DoubleTap -> doubleTap = choice
            HomeGesture.Hold -> hold = choice
            HomeGesture.TwoFingerUp -> twoFingerUp = choice
            HomeGesture.TwoFingerDown -> twoFingerDown = choice
        }
    }

    /** The panels gestures picked, which home keeps running so one is ready when its gesture starts. */
    val pickedPanels: List<String>
        get() = HomeGesture.entries.map(::choice).filter { !it.isGestureAction }

    init {
        // Launchers before style.toml kept swipe up in preferences; moved over once, and left there for a downgrade.
        val prefs = LauncherData.prefs(context)
        if (!prefs.getBoolean(MOVED, false)) {
            if (prefs.getString(OLD_SWIPE_UP, null) == NONE) swipeUp = NONE
            prefs.edit().putBoolean(MOVED, true).apply()
        }
    }

    companion object {
        const val NONE = "none"

        /** Lock the screen, with the phone's device admin Android asks the launcher to be once. */
        const val LOCK = "lock"

        /** Home's menu: themes, and new widgets, apps and panels with the agent. */
        const val MENU = "menu"
        private const val OLD_SWIPE_UP = "gesture_swipe_up"
        private const val MOVED = "gestures_in_style"
    }
}

/**
 * A gesture on home, in the order gesture settings lists them. [where] says where it applies: the whole home
 * screen, or the empty space between tiles.
 */
internal enum class HomeGesture(
    @StringRes val title: Int,
    @StringRes val where: Int,
) {
    SwipeUp(R.string.gestures_swipe_up, R.string.gestures_on_home),
    SwipeDown(R.string.gestures_swipe_down, R.string.gestures_on_home),
    DoubleTap(R.string.gestures_double_tap, R.string.gestures_on_empty),
    Hold(R.string.gestures_hold, R.string.gestures_on_empty),
    TwoFingerUp(R.string.gestures_two_finger_up, R.string.gestures_on_home),
    TwoFingerDown(R.string.gestures_two_finger_down, R.string.gestures_on_home),
}

/** Whether [this] is one of the actions every gesture has, rather than a panel's file name. */
internal val String.isGestureAction: Boolean get() = this == Gestures.NONE || this == Gestures.LOCK || this == Gestures.MENU

/** The panel [choice] names, if it's one that runs; null for nothing, an action that isn't a panel, and a panel that's gone or broken. */
internal fun Runtime.panelOf(choice: String) = choice.takeIf { !it.isGestureAction }?.takeIf { widgets.panelInstance(it) != null }

/**
 * What [choice] is called in settings: nothing, an action, or the panel's name (its file's, while it has none).
 */
internal fun Runtime.gestureLabel(choice: String) =
    when (choice) {
        Gestures.NONE -> gestures.context.getString(R.string.gestures_nothing)
        Gestures.LOCK -> gestures.context.getString(R.string.gestures_lock)
        Gestures.MENU -> gestures.context.getString(R.string.gestures_menu)
        else -> widgets.panels().firstOrNull { it.file.name == choice }?.name ?: choice.removeSuffix(".owl")
    }

/**
 * Picked panels that don't compile, drawer.owl and keybar.owl, for home's error banner: without a tile, nothing else would say so,
 * and the drawer quietly shows another until they're fixed.
 */
internal fun Runtime.gestureProblems(): List<Pair<String, List<String>>> {
    val context = gestures.context
    val drawer =
        widgets.drawerPanel()?.let { if (it == WidgetEngine.STOCK_DRAWER) context.getString(R.string.gestures_stock_drawer) else gestureLabel(it) }
            ?: context.getString(R.string.gestures_nothing)
    val drawerShows = context.getString(R.string.gestures_drawer_shows, drawer)
    val nothing = context.getString(R.string.gestures_opens_nothing)
    // What each gesture opens meanwhile, keyed by its picked panel: the drawer falls back, the others open nothing.
    val meanwhile =
        linkedMapOf(
            WidgetEngine.DRAWER to drawerShows,
            gestures.swipeUp to drawerShows,
            gestures.swipeDown to context.getString(R.string.gestures_swipe_down_nothing),
            gestures.doubleTap to nothing,
            gestures.hold to nothing,
            gestures.twoFingerUp to nothing,
            gestures.twoFingerDown to nothing,
        )
    val panels =
        meanwhile.filterKeys { !it.isGestureAction }.mapNotNull { (choice, what) ->
            val spec = widgets.panels().firstOrNull { it.file.name == choice && it.error != null } ?: return@mapNotNull null
            spec.file.name to listOf(context.getString(R.string.gestures_until_fixed, what, spec.error!!.lineSequence().first()))
        }
    // keybar.owl too: the stock keys stand in for it as quietly.
    val keys = widgets.specs.firstOrNull { it.file.name == WidgetEngine.KEYBAR && it.error != null }?.let { it.file.name to listOf(context.getString(R.string.gestures_keybar_until_fixed, it.error!!.lineSequence().first())) }
    return panels + listOfNotNull(keys)
}

/**
 * Dragging up opens the drawer under the finger and dragging down closes it, as on stock launchers: the panel
 * follows the drag and a fling or how far it got decides where it settles. Scrollables inside (a widget's
 * list, the recent apps strip) take their own direction first.
 */
@Composable
internal fun Modifier.drawerSwipe(
    state: LauncherState,
    enabled: Boolean = true,
): Modifier {
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    return draggable(
        orientation = Orientation.Vertical,
        enabled = enabled,
        state = rememberDraggableState { dy -> state.dragBy(-dy / screenHeight) },
        onDragStopped = { velocity -> state.settle(velocity) },
    )
}

/** What a drag on home turned out to be, decided by where the drawer is and which way it first moves. */
private enum class HomeDrag { Undecided, Drawer, Pull, Action, Ignored }

/** The drag under way: not state, nothing draws from it. */
private class DragTrack {
    var kind = HomeDrag.Undecided

    /** Two fingers are down: the drawer stays still, because a two-finger swipe is a gesture of its own. */
    var multi = false

    /** How far the finger came, to tell a swipe meant for an action from a touch that only moved a little. */
    var travel = 0f

    /** What an [HomeDrag.Action] runs: the action of the gesture the drag started with. */
    var choice: String? = null

    fun reset() {
        kind = HomeDrag.Undecided
        travel = 0f
        choice = null
    }
}

/** How far a swipe for an action, which shows nothing under the finger, must go before it counts: a tenth of the screen. */
private const val ACTION_SWIPE = 0.1f

/** A fling for an action, in px/s, like the drawer's own. */
private const val ACTION_FLING = 800f

/**
 * Swiping on home (the pages, the recent apps strip, the bar) does what's picked in gesture settings: up for the
 * drawer or a panel in its place, down for a panel from the top, or an action such as locking the screen. With the
 * drawer open, the swipe drags it whatever the setting, so it can still be pulled closed. Swiping with two fingers
 * is its own gesture, and keeps the drawer still under it.
 */
@Composable
internal fun Modifier.homeSwipe(
    runtime: Runtime,
    state: LauncherState,
    enabled: Boolean = true,
    /** Runs an action a gesture is set to (lock, home's menu), when a swipe turns out to be one. */
    onAction: (String) -> Unit = {},
): Modifier {
    val gestures = runtime.gestures
    val drawerShown by remember(state) { derivedStateOf { state.drawer.value > 0f } }
    // Whether the drawer shows is only read when it matters: read here, it would recompose all of home (its Box is
    // inline) each time the drawer opens or closes.
    val drags = gestures.swipeUp != Gestures.NONE || gestures.swipeDown != Gestures.NONE || drawerShown
    val twoFingers = gestures.twoFingerUp != Gestures.NONE || gestures.twoFingerDown != Gestures.NONE
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val swipe = with(LocalDensity.current) { 56.dp.toPx() }
    val drag = remember { DragTrack() }
    val latestAction by rememberUpdatedState(onAction)
    // One drag handler, only turned on or off: a new one mid-swipe (as the drawer starts to show) would drop the drag.
    return Modifier
        // Sees a second finger before the drag below (it reads events first, and takes none): a two-finger swipe is
        // its own gesture, so the drawer doesn't follow it and the swipe runs what gesture settings picked.
        .pointerInput(runtime, state, enabled, twoFingers) {
            if (!enabled || !twoFingers) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var fingers = false
                var start = Offset.Zero
                var end = Offset.Zero
                try {
                    while (true) {
                        val pressed = awaitPointerEvent(PointerEventPass.Initial).changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val middle = pressed.fold(Offset.Zero) { sum, c -> sum + c.position } / pressed.size.toFloat()
                        if (!fingers && pressed.size > 1) {
                            fingers = true
                            drag.multi = true
                            start = middle
                        }
                        if (fingers) end = middle
                    }
                } finally {
                    drag.multi = false
                    val dy = end.y - start.y
                    val dx = end.x - start.x
                    // A decisive swipe, mostly straight up or down, and only over home itself.
                    if (fingers && abs(dy) > swipe && abs(dy) > abs(dx) && state.drawer.value == 0f && state.pulled == null && !state.homeMenuOpen) {
                        val choice = if (dy < 0f) gestures.twoFingerUp else gestures.twoFingerDown
                        if (choice != Gestures.NONE) latestAction(choice)
                    }
                }
            }
        }
        .draggable(
            orientation = Orientation.Vertical,
            enabled = enabled && (drags || twoFingers),
            state =
                rememberDraggableState { dy ->
                    // Two fingers: the two-finger gesture's, and the drawer stays where it is.
                    if (drag.multi) return@rememberDraggableState
                    drag.travel += dy
                    if (drag.kind == HomeDrag.Undecided && dy != 0f) {
                        drag.kind =
                            when {
                                state.drawer.value > 0f -> HomeDrag.Drawer
                                dy < 0f && gestures.swipeUp != Gestures.NONE ->
                                    if (gestures.swipeUp.isGestureAction) {
                                        drag.choice = gestures.swipeUp
                                        HomeDrag.Action
                                    } else {
                                        HomeDrag.Drawer
                                    }
                                // Picked as the drag starts: it comes down from out of sight above with the finger.
                                dy > 0f ->
                                    when {
                                        gestures.swipeDown.isGestureAction -> {
                                            drag.choice = gestures.swipeDown
                                            HomeDrag.Action
                                        }
                                        else -> runtime.panelOf(gestures.swipeDown)?.let { name -> state.pulled = name; HomeDrag.Pull } ?: HomeDrag.Ignored
                                    }
                                else -> HomeDrag.Ignored
                            }
                    }
                    when (drag.kind) {
                        HomeDrag.Drawer -> state.dragBy(-dy / screenHeight)
                        HomeDrag.Pull -> state.pullBy(dy / screenHeight)
                        else -> {}
                    }
                },
            onDragStarted = {
                drag.reset()
                state.startPull()
            },
            onDragStopped = { velocity ->
                when (drag.kind) {
                    HomeDrag.Drawer -> state.settle(velocity)
                    HomeDrag.Pull -> state.settlePull(velocity)
                    // It showed nothing under the finger: it counts once it went far enough or was a fling.
                    HomeDrag.Action -> if (abs(drag.travel) > screenHeight * ACTION_SWIPE || abs(velocity) > ACTION_FLING) drag.choice?.let(latestAction)
                    else -> {}
                }
                drag.reset()
            },
        )
}

/**
 * Dragging the open swipe-down panel moves it under the finger; it settles open or closed like the drawer. A list in
 * it scrolls first: pushing up past its end moves the panel on, as pulling down past the drawer's top closes it.
 */
@Composable
internal fun Modifier.pullSwipe(state: LauncherState): Modifier {
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val pushToClose =
        remember(state, screenHeight) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Partly pushed up and dragged back down: it comes back before the list scrolls.
                    if (source != NestedScrollSource.UserInput || available.y <= 0f || state.pull.value >= 1f) return Offset.Zero
                    state.pullBy(available.y / screenHeight)
                    return Offset(0f, available.y)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
                    if (state.pull.value >= 1f) state.startPull()
                    state.pullBy(available.y / screenHeight)
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (state.pull.value >= 1f) return Velocity.Zero
                    state.settlePull(available.y)
                    return available
                }
            }
        }
    return nestedScroll(pushToClose).draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { dy -> state.pullBy(dy / screenHeight) },
        onDragStarted = { state.startPull() },
        onDragStopped = { velocity -> state.settlePull(velocity) },
    )
}

/**
 * Gesture settings: what each gesture on home does, picked from a dropdown of actions (nothing, locking the screen,
 * home's menu) and panels, owl files to edit with the agent. One row per gesture, scrolling as the list grows.
 */
@Composable
fun GesturesScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
    /** Opens the agent on a panel, as settings › pages does on a face. */
    onAgent: (title: String, command: (bin: String) -> String) -> Unit,
) {
    val gestures = runtime.gestures
    val context = LocalContext.current
    // Android's device admin screen comes back to this one: read again on the way in, so the lock's line goes once allowed.
    var allowed by remember { mutableStateOf(LockAdmin.active(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = LockAdmin.active(context)
        onPauseOrDispose {}
    }
    // A panel that doesn't compile is still listed, marked, so a pick that stopped working shows why.
    val panels = runtime.widgets.panels().map { it.file.name to if (it.program == null) stringResource(R.string.gestures_has_errors, it.name) else it.name }
    val actions =
        listOf(
            Gestures.NONE to stringResource(R.string.gestures_nothing),
            Gestures.LOCK to stringResource(R.string.gestures_lock),
            Gestures.MENU to stringResource(R.string.gestures_menu),
        )
    val choices = actions + panels
    OverlayPanel(stringResource(R.string.gestures_title), onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            for (gesture in HomeGesture.entries) {
                val choice = gestures.choice(gesture)
                GestureRow(gesture, choices, panels.isNotEmpty(), choice) { gestures.pick(gesture, it) }
                when {
                    // The lock needs Android's say: a line to ask for it, until it's given.
                    choice == Gestures.LOCK && !allowed -> AllowLock { LockAdmin.allow(context) }
                    else -> EditPanel(runtime, choice, onAgent)
                }
                Spacer(Modifier.height(8.dp))
            }
            BasicText(
                stringResource(R.string.gestures_hint),
                Modifier.padding(top = 4.dp, start = 14.dp, end = 14.dp, bottom = 8.dp),
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
        }
    }
}

/** Under a lock gesture the phone hasn't allowed yet: [allow] opens Android's screen to make the launcher its device admin. */
@Composable
private fun AllowLock(allow: () -> Unit) {
    Row(
        Modifier
            .padding(start = 6.dp, top = 2.dp)
            .fillMaxWidth()
            .clickable(onClick = allow)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("[ ${stringResource(R.string.gestures_allow)} ]", Modifier.padding(end = 8.dp), style = Mono.copy(color = Palette.accent))
        BasicText(stringResource(R.string.gestures_lock_needs_admin), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
    }
}

/** Under a gesture opening a panel: [edit] starts the agent on its file, as one edits a face or a widget. */
@Composable
private fun EditPanel(
    runtime: Runtime,
    choice: String,
    onAgent: (title: String, command: (bin: String) -> String) -> Unit,
) {
    EditFile(runtime.widgets.panels().firstOrNull { it.file.name == choice } ?: return, onAgent)
}

/**
 * [edit] starts the agent on [spec]'s file, as one edits a face or a widget. The whole line takes it, file name too: the
 * keys' arrows then stop on it between the gestures, where a short [ edit ] at its start lost to the full row past it.
 */
@Composable
private fun EditFile(
    spec: WidgetSpec,
    onAgent: (title: String, command: (bin: String) -> String) -> Unit,
) {
    Row(
        Modifier
            .padding(start = 6.dp, top = 2.dp)
            .fillMaxWidth()
            .clickable { onAgent("Edit ${spec.name}") { editWidgetCommand(it, spec) } }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("[ ${stringResource(R.string.gestures_edit)} ]", Modifier.padding(end = 8.dp), style = Mono.copy(color = Palette.accent))
        BasicText(spec.file.name, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
    }
}

/**
 * A gesture and what it does, with where it applies under its name; tapping it lists the actions and the panels
 * under them. [choices] are (saved value, label).
 */
@Composable
private fun GestureRow(
    gesture: HomeGesture,
    choices: List<Pair<String, String>>,
    hasPanels: Boolean,
    current: String,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // A file name not among the panels (removed since): shown as it's saved, so the user sees it and picks another.
    val picked = choices.firstOrNull { it.first == current } ?: (current to stringResource(R.string.gestures_missing, current))
    Row(
        Modifier.card(onClick = { open = !open }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(stringResource(gesture.title), style = Mono.copy(fontSize = TextSize.Item))
            BasicText(stringResource(gesture.where), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(picked.second, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
                Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 4.dp))
            }
            if (open) {
                ChoicePopup(
                    choices,
                    picked,
                    BelowAnchorEnd,
                    onDismiss = { open = false },
                    onPick = { onPick(it.first) },
                    label = { it.second },
                    // The actions come first; under them, a heading says what follows.
                    footer = { if (hasPanels) SectionLabel(stringResource(R.string.gestures_panels)) },
                )
            }
        }
    }
}
