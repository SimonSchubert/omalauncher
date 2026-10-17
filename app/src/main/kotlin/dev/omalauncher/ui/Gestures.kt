package dev.omalauncher.ui

import android.content.Context
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import dev.omalauncher.LauncherData
import dev.omalauncher.Runtime

/** What a gesture on home does. */
enum class GestureAction(
    /** Saved in preferences: never rename. */
    val id: String,
    val label: String,
) {
    Drawer("drawer", "app drawer"),
    Screens("screens", "arrange screens"),
    None("none", "nothing"),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** The actions picked for home's gestures: swipe up and pinch. */
class Gestures(
    context: Context,
) {
    private val prefs = LauncherData.prefs(context)

    private var up by mutableStateOf(GestureAction.of(prefs.getString(SWIPE_UP, null)) ?: GestureAction.Drawer)
    private var pinchAction by mutableStateOf(GestureAction.of(prefs.getString(PINCH, null)) ?: GestureAction.Screens)

    /** The app drawer by default, like stock launchers. Saved as it's set. */
    var swipeUp: GestureAction
        get() = up
        set(action) {
            up = action
            prefs.edit().putString(SWIPE_UP, action.id).apply()
        }

    /** What pinching on home does. Default: arrange screens. */
    var pinch: GestureAction
        get() = pinchAction
        set(action) {
            pinchAction = action
            prefs.edit().putString(PINCH, action.id).apply()
        }

    private companion object {
        const val SWIPE_UP = "gesture_swipe_up"
        const val PINCH = "gesture_pinch"
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

/**
 * Swiping up on home (the pages, the recent apps strip, the bar) does what's picked in gesture settings. With
 * the drawer open, the swipe drags it whatever the setting, so it can still be pulled closed.
 */
@Composable
internal fun Modifier.homeSwipe(
    runtime: Runtime,
    state: LauncherState,
    enabled: Boolean = true,
): Modifier {
    val drawerShown by remember(state) { derivedStateOf { state.drawer.value > 0f } }
    // Whether the drawer shows is only read when it matters: read here, it would recompose all of home (its Box is
    // inline) each time the drawer opens or closes.
    val drags =
        when (runtime.gestures.swipeUp) {
            GestureAction.Drawer -> true
            // Not offered for swiping up (see GesturesScreen), but a pick from elsewhere mustn't break the swipe.
            GestureAction.None, GestureAction.Screens -> drawerShown
        }
    // One drag handler, only turned on or off: a new one mid-swipe (as the drawer starts to show) would drop the drag.
    return drawerSwipe(state, enabled && drags)
}

/**
 * Pinching with two fingers on home triggers the configured pinch action (arranging screens by default).
 */
@Composable
internal fun Modifier.homePinch(
    enabled: Boolean = true,
    onPinch: () -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val minPinchDelta = with(LocalDensity.current) { 55.dp.toPx() }

    return pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            var initialDistance = -1f
            var triggered = false

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val active = event.changes.filter { it.pressed }
                if (active.size < 2) {
                    if (active.isEmpty()) break
                    initialDistance = -1f
                } else if (active.size >= 2) {
                    val p1 = active[0].position
                    val p2 = active[1].position
                    val dist = (p1 - p2).getDistance()

                    if (initialDistance < 0f) {
                        initialDistance = dist
                    } else if (!triggered) {
                        val delta = initialDistance - dist
                        // Detect pinch in: fingers moved towards each other by min delta and distance shrank by >= 20%
                        if (delta > minPinchDelta && dist / initialDistance < 0.82f) {
                            triggered = true
                            event.changes.forEach { it.consume() }
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPinch()
                            break
                        }
                    }
                }
            }
        }
    }
}

/** Gesture settings: what swiping up and pinching on home do, picked from dropdowns. */
@Composable
fun GesturesScreen(
    runtime: dev.omalauncher.Runtime,
    onDismiss: () -> Unit,
) {
    val gestures = runtime.gestures
    OverlayPanel("gestures", onDismiss, contentWidth = ReadingWidth) {
        GestureRow("swipe up", gestures.swipeUp, listOf(GestureAction.Drawer, GestureAction.None)) { gestures.swipeUp = it }
        Box(Modifier.padding(top = 12.dp)) {
            GestureRow("pinch", gestures.pinch, listOf(GestureAction.Screens, GestureAction.Drawer, GestureAction.None)) { gestures.pinch = it }
        }
    }
}

/** A gesture and the action it runs; tapping the action lists the others under it. */
@Composable
private fun GestureRow(
    label: String,
    action: GestureAction,
    /** What this gesture can be set to. */
    options: List<GestureAction>,
    onPick: (GestureAction) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.card(onClick = { open = !open }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(action.label, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
                Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 4.dp))
            }
            if (open) {
                ChoicePopup(options, action, BelowAnchorEnd, onDismiss = { open = false }, onPick = onPick, label = { it.label })
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
