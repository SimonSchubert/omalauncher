package dev.omalauncher.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

/** The actions picked for home's gestures: swipe up opens the drawer, swipe down the shade. */
class Gestures(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private var up by mutableStateOf(GestureAction.of(prefs.getString(SWIPE_UP, null)) ?: GestureAction.Drawer)
    private var down by mutableStateOf(GestureAction.of(prefs.getString(SWIPE_DOWN, null)) ?: GestureAction.Notifications)

    /** The app drawer by default, like stock launchers. Saved as it's set. */
    var swipeUp: GestureAction
        get() = up
        set(action) {
            up = action
            prefs.edit().putString(SWIPE_UP, action.id).apply()
        }

    /** The notification shade by default, like stock launchers. Saved as it's set. */
    var swipeDown: GestureAction
        get() = down
        set(action) {
            down = action
            prefs.edit().putString(SWIPE_DOWN, action.id).apply()
        }

    private companion object {
        const val SWIPE_UP = "gesture_swipe_up"
        const val SWIPE_DOWN = "gesture_swipe_down"
    }
}

/**
 * Dragging up opens the drawer under the finger and dragging down closes it, as on stock launchers: the panel
 * follows the drag and a fling or how far it got decides where it settles. Scrollables inside (a widget's
 * list, the recent apps strip) take their own direction first.
 *
 * When the drawer starts closed, a swipe in either direction can run [onSwipeUp]/[onSwipeDown] instead of
 * settling the drawer: home uses swipe-down to expand the notification shade. Closing an open drawer
 * never triggers them.
 */
@Composable
internal fun Modifier.drawerSwipe(
    state: LauncherState,
    enabled: Boolean = true,
    onSwipeUp: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null,
): Modifier {
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var startValue by remember(state) { mutableStateOf(0f) }
    var downPx by remember(state) { mutableStateOf(0f) }
    var upPx by remember(state) { mutableStateOf(0f) }
    return draggable(
        orientation = Orientation.Vertical,
        enabled = enabled,
        onDragStarted = {
            startValue = state.drawer.value
            downPx = 0f
            upPx = 0f
        },
        state = rememberDraggableState { dy ->
            if (dy > 0f) downPx += dy else upPx -= dy
            state.dragBy(-dy / screenHeight)
        },
        onDragStopped = { velocity ->
            val startedClosed = startValue < 0.05f
            val stillClosed = state.drawer.value < 0.15f
            val swipedDown = velocity > SWIPE_FLING || downPx > screenHeight * SWIPE_FRACTION
            val swipedUp = velocity < -SWIPE_FLING || upPx > screenHeight * SWIPE_FRACTION
            if (startedClosed && stillClosed && swipedDown && onSwipeDown != null) {
                state.close()
                onSwipeDown()
            } else if (startedClosed && swipedUp && onSwipeUp != null) {
                state.close()
                onSwipeUp()
            } else {
                state.settle(velocity)
            }
        },
    )
}

/** Fling fast enough to count as a swipe, in px/s. Matches the drawer's open/close fling. */
private const val SWIPE_FLING = 800f

/** Drag far enough to count as a swipe even without a fling: 8% of screen height. */
private const val SWIPE_FRACTION = 0.08f

/**
 * Swiping up on home (the pages, the recent apps strip, the bar) does what's picked in gesture settings, and
 * swiping down expands the shade. With the drawer open, the swipe drags it whatever the setting, so it can
 * still be pulled closed.
 */
@Composable
internal fun Modifier.homeSwipe(
    runtime: Runtime,
    state: LauncherState,
    enabled: Boolean = true,
): Modifier {
    val context = androidx.compose.ui.platform.LocalContext.current
    val drawerShown by remember(state) { derivedStateOf { state.drawer.value > 0f } }
    // Whether the drawer shows is only read when it matters: read here, it would recompose all of home (its Box is
    // inline) each time the drawer opens or closes.
    val swipeUpAction = runtime.gestures.swipeUp
    val swipeDownAction = runtime.gestures.swipeDown
    val wantsUp = swipeUpAction != GestureAction.None
    val wantsDown = swipeDownAction != GestureAction.None
    // One drag handler, only turned on or off: a new one mid-swipe (as the drawer starts to show) would drop the drag.
    return drawerSwipe(
        state,
        enabled && (wantsUp || wantsDown || drawerShown),
        onSwipeUp =
            when (swipeUpAction) {
                GestureAction.Notifications -> ({ expandNotificationsPanel(context) })
                // Drawer is the drag itself (settle opens it); no override needed.
                else -> null
            },
        onSwipeDown =
            if (!wantsDown) {
                null
            } else {
                {
                    when (swipeDownAction) {
                        GestureAction.Notifications -> expandNotificationsPanel(context)
                        GestureAction.Drawer -> state.open()
                        GestureAction.None -> {}
                    }
                }
            },
    )
}

/** Gesture settings: what swiping up/down on home does, picked from dropdowns. */
@Composable
fun GesturesScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val gestures = runtime.gestures
    OverlayPanel("gestures", onDismiss, contentWidth = ReadingWidth) {
        GestureRow("swipe up", gestures.swipeUp) { gestures.swipeUp = it }
        Spacer(Modifier.height(8.dp))
        GestureRow("swipe down", gestures.swipeDown) { gestures.swipeDown = it }
    }
}

/** A gesture and the action it runs; tapping the action lists the others under it. */
@Composable
private fun GestureRow(
    label: String,
    action: GestureAction,
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
                        for (option in GestureAction.entries) {
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
