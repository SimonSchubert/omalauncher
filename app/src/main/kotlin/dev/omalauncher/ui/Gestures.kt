package dev.omalauncher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.PopupPositionProvider
import dev.omalauncher.LauncherData
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.theme.Style
import dev.omalauncher.theme.StyleStore
import dev.omalauncher.widgets.WidgetEngine
import dev.omalauncher.widgets.WidgetSpec
import dev.omalauncher.widgets.editWidgetCommand
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * What swiping up and swiping down on home open, kept in style.toml so agents can set them too: the file name of a
 * panel (`panel "…" { }`; swiping up opens drawer.owl, the app drawer, by default), or "none".
 */
class Gestures(
    /** The app's, for home's error banner and settings, which say what a gesture opens. */
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
        private const val OLD_SWIPE_UP = "gesture_swipe_up"
        private const val MOVED = "gestures_in_style"
    }
}

/** The panel [choice] names, if it's one that runs; null for nothing, and a panel that's gone or broken. */
internal fun Runtime.panelOf(choice: String) = choice.takeIf { it != Gestures.NONE }?.takeIf { widgets.panelInstance(it) != null }

/** What [choice] is called in settings: nothing, or the panel's name (its file's, while it has none). */
internal fun Runtime.gestureLabel(choice: String) =
    if (choice == Gestures.NONE) gestures.context.getString(R.string.gestures_nothing) else widgets.panels().firstOrNull { it.file.name == choice }?.name ?: choice.removeSuffix(".owl")

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
    val meanwhile =
        linkedMapOf(gestures.swipeUp to drawerShows, WidgetEngine.DRAWER to drawerShows) +
            (gestures.swipeDown to context.getString(R.string.gestures_swipe_down_nothing))
    val panels =
        meanwhile.mapNotNull { (choice, what) ->
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
private enum class HomeDrag { Undecided, Drawer, Pull, Ignored }

/** The drag under way: not state, nothing draws from it. */
private class DragTrack {
    var kind = HomeDrag.Undecided
}

/**
 * Swiping on home (the pages, the recent apps strip, the bar) does what's picked in gesture settings: up for the
 * drawer or a panel in its place, down for a panel from the top. With the drawer open, the swipe drags it whatever
 * the setting, so it can still be pulled closed.
 */
@Composable
internal fun Modifier.homeSwipe(
    runtime: Runtime,
    state: LauncherState,
    enabled: Boolean = true,
): Modifier {
    val gestures = runtime.gestures
    val drawerShown by remember(state) { derivedStateOf { state.drawer.value > 0f } }
    // Whether the drawer shows is only read when it matters: read here, it would recompose all of home (its Box is
    // inline) each time the drawer opens or closes.
    val drags = gestures.swipeUp != Gestures.NONE || gestures.swipeDown != Gestures.NONE || drawerShown
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val drag = remember { DragTrack() }
    // One drag handler, only turned on or off: a new one mid-swipe (as the drawer starts to show) would drop the drag.
    return draggable(
        orientation = Orientation.Vertical,
        enabled = enabled && drags,
        state =
            rememberDraggableState { dy ->
                if (drag.kind == HomeDrag.Undecided && dy != 0f) {
                    drag.kind =
                        when {
                            state.drawer.value > 0f -> HomeDrag.Drawer
                            dy < 0f && gestures.swipeUp != Gestures.NONE -> HomeDrag.Drawer
                            // Picked as the drag starts: it comes down from out of sight above with the finger.
                            dy > 0f -> runtime.panelOf(gestures.swipeDown)?.let { name -> state.pulled = name; HomeDrag.Pull } ?: HomeDrag.Ignored
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
            drag.kind = HomeDrag.Undecided
            state.startPull()
        },
        onDragStopped = { velocity ->
            when (drag.kind) {
                HomeDrag.Drawer -> state.settle(velocity)
                HomeDrag.Pull -> state.settlePull(velocity)
                else -> {}
            }
            drag.kind = HomeDrag.Undecided
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
 * Gesture settings: what swiping up and swiping down on home open, each picked from a dropdown of choices and panels,
 * owl files to edit with the agent.
 */
@Composable
fun GesturesScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
    /** Opens the agent on a panel, as settings › pages does on a face. */
    onAgent: (title: String, command: (bin: String) -> String) -> Unit,
) {
    val gestures = runtime.gestures
    // A panel that doesn't compile is still listed, marked, so a pick that stopped working shows why.
    val panels = runtime.widgets.panels().map { it.file.name to if (it.program == null) stringResource(R.string.gestures_has_errors, it.name) else it.name }
    val nothing = Gestures.NONE to stringResource(R.string.gestures_nothing)
    OverlayPanel(stringResource(R.string.gestures_title), onDismiss, contentWidth = ReadingWidth) {
        GestureRow(stringResource(R.string.gestures_swipe_up), panels + listOf(nothing), gestures.swipeUp) { gestures.swipeUp = it }
        EditPanel(runtime, gestures.swipeUp, onAgent)
        Spacer(Modifier.height(8.dp))
        GestureRow(stringResource(R.string.gestures_swipe_down), listOf(nothing) + panels, gestures.swipeDown) { gestures.swipeDown = it }
        EditPanel(runtime, gestures.swipeDown, onAgent)
        BasicText(
            stringResource(R.string.gestures_hint),
            Modifier.padding(top = 12.dp, start = 14.dp, end = 14.dp),
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
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

/** A gesture and what it opens; tapping that lists the others under it. [choices] are (saved value, label). */
@Composable
private fun GestureRow(
    label: String,
    choices: List<Pair<String, String>>,
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
        BasicText(label, Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(picked.second, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
                Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 4.dp))
            }
            if (open) {
                ChoicePopup(choices, picked, BelowAnchorEnd, onDismiss = { open = false }, onPick = { onPick(it.first) }, label = { it.second })
            }
        }
    }
}

/** Just under the anchor, lined up with its end, and kept on screen. */
private object BelowAnchorEnd : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ) = IntOffset(
        (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        (anchorBounds.bottom + 8).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
}
