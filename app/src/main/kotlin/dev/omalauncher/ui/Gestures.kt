package dev.omalauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.omalauncher.Runtime

/** What a gesture on home does. */
enum class GestureAction(
    /** Saved in preferences: never rename. */
    val id: String,
    val label: String,
) {
    Drawer("drawer", "app drawer"),
    Notifications("notifications", "notifications"),
    None("none", "nothing"),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** What swiping up on home can do, the default first. */
private val SwipeUpChoices = listOf(GestureAction.Drawer, GestureAction.None)

/** What swiping down on home can do, the default first. */
private val SwipeDownChoices = listOf(GestureAction.Notifications, GestureAction.None)

/** The actions picked for home's gestures. */
class Gestures(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private var up by mutableStateOf(saved(SWIPE_UP, SwipeUpChoices))
    private var down by mutableStateOf(saved(SWIPE_DOWN, SwipeDownChoices))

    /** The app drawer by default, like stock launchers. Saved as it's set. */
    var swipeUp: GestureAction
        get() = up
        set(action) {
            up = action
            prefs.edit().putString(SWIPE_UP, action.id).apply()
        }

    /** Android's notification shade by default, like stock launchers. Saved as it's set. */
    var swipeDown: GestureAction
        get() = down
        set(action) {
            down = action
            prefs.edit().putString(SWIPE_DOWN, action.id).apply()
        }

    /** The action saved for [key] if the gesture offers it, else its default. */
    private fun saved(
        key: String,
        choices: List<GestureAction>,
    ) = GestureAction.of(prefs.getString(key, null))?.takeIf { it in choices } ?: choices.first()

    private companion object {
        const val SWIPE_UP = "gesture_swipe_up"
        const val SWIPE_DOWN = "gesture_swipe_down"
    }
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

/** What a swipe on home moves. */
internal enum class SwipeTarget { Drawer, Shade, None }

/**
 * What a swipe on home moves, from how far the drawer is open and the swipe's first step ([dy] > 0 is down). With
 * the drawer showing, the swipe drags it whatever the settings, so it can still be pulled closed.
 */
internal fun swipeTarget(
    drawer: Float,
    dy: Float,
    up: GestureAction,
    down: GestureAction,
) = when {
    drawer > 0f -> SwipeTarget.Drawer
    dy > 0f && down == GestureAction.Notifications -> SwipeTarget.Shade
    // With nothing set for swiping down, the drawer still follows a swipe that starts down and turns up.
    up == GestureAction.Drawer -> SwipeTarget.Drawer
    else -> SwipeTarget.None
}

/**
 * Swiping on home (the pages, the recent apps strip, the bar) does what's picked in gesture settings: up opens the
 * drawer under the finger, down pulls down Android's notification shade.
 */
@Composable
internal fun Modifier.homeSwipe(
    runtime: Runtime,
    state: LauncherState,
    enabled: Boolean = true,
): Modifier {
    val context = LocalContext.current
    val gestures = runtime.gestures
    val drawerShown by remember(state) { derivedStateOf { state.drawer.value > 0f } }
    // Whether the drawer shows is only read when it matters: read here, it would recompose all of home (its Box is
    // inline) each time the drawer opens or closes.
    val drags = gestures.swipeUp == GestureAction.Drawer || gestures.swipeDown == GestureAction.Notifications || drawerShown
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    // Picked by the swipe's first step, so one that starts down and turns up doesn't open the drawer under the shade.
    var target by remember { mutableStateOf<SwipeTarget?>(null) }
    // One drag handler, only turned on or off: a new one mid-swipe (as the drawer starts to show) would drop the drag.
    return draggable(
        orientation = Orientation.Vertical,
        enabled = enabled && drags,
        state =
            rememberDraggableState { dy ->
                if (target == null && dy != 0f) {
                    target = swipeTarget(state.drawer.value, dy, gestures.swipeUp, gestures.swipeDown)
                    if (target == SwipeTarget.Shade) expandNotifications(context)
                }
                if (target == SwipeTarget.Drawer) state.dragBy(-dy / screenHeight)
            },
        onDragStarted = { target = null },
        onDragStopped = { velocity -> if (target == SwipeTarget.Drawer) state.settle(velocity) },
    )
}

/**
 * Pulls down Android's notification shade. There's no public call for it: launchers that aren't part of the system,
 * like Lawnchair, call StatusBarManager's hidden expandNotificationsPanel, which needs EXPAND_STATUS_BAR.
 */
@SuppressLint("WrongConstant")
private fun expandNotifications(context: Context) {
    runCatching {
        Class
            .forName("android.app.StatusBarManager")
            .getMethod("expandNotificationsPanel")
            .invoke(context.getSystemService("statusbar"))
    }.onFailure { Log.w(TAG, "cannot pull down the notification shade", it) }
}

private const val TAG = "gestures"

/** Gesture settings: what swiping up and down on home do, each picked from a dropdown. */
@Composable
fun GesturesScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val gestures = runtime.gestures
    OverlayPanel("gestures", onDismiss, contentWidth = ReadingWidth) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GestureRow("swipe up", gestures.swipeUp, SwipeUpChoices) { gestures.swipeUp = it }
            GestureRow("swipe down", gestures.swipeDown, SwipeDownChoices) { gestures.swipeDown = it }
        }
    }
}

/** A gesture and the action it runs; tapping the action lists the gesture's [choices] under it. */
@Composable
private fun GestureRow(
    label: String,
    action: GestureAction,
    choices: List<GestureAction>,
    onPick: (GestureAction) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LauncherStyle.shape(12.dp))
            .background(Palette.surface)
            .clickable { open = !open }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(action.label, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
                Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 4.dp))
            }
            if (open) {
                Popup(
                    popupPositionProvider = BelowAnchorEnd,
                    onDismissRequest = { open = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Column(
                        Modifier
                            .width(IntrinsicSize.Max)
                            .widthIn(min = 180.dp)
                            .frame(2.dp, Palette.accent, Palette.background)
                            .padding(4.dp),
                    ) {
                        for (option in choices) {
                            val current = option == action
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onPick(option)
                                        open = false
                                    }.padding(horizontal = 10.dp, vertical = 10.dp),
                            ) {
                                BasicText(if (current) "● " else "  ", style = Mono.copy(color = Palette.accent))
                                BasicText(option.label, style = Mono.copy(color = if (current) Palette.accent else Palette.foreground))
                            }
                        }
                    }
                }
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
