package dev.omalauncher.widgets.render

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.Icons
import dev.omalauncher.ui.Palette
import dev.omalauncher.widgets.lang.Action
import dev.omalauncher.widgets.lang.Env
import dev.omalauncher.widgets.runtime.WidgetInstance
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How far, in dp, a list is pulled down past its top before letting go runs onPull. */
private const val PULL_DP = 72f

/** The fetches onPull set off show as started within this long, in ms, or there were none. */
private const val START_MS = 400L

/** The spinner turns at least this long, in ms, so a quick refresh still shows it happened, and at most [MAX_MS]. */
private const val MIN_MS = 600L
private const val MAX_MS = 30_000L

/**
 * onPull on a Column or Grid that scrolls (owl 17): pulled down past its top, the list's overscroll draws the launcher's
 * refresh icon down under the finger, and let go far enough it runs [action] and spins, at rest a little below the top,
 * while the sources it set off fetch. Goes before the node's scrolling in its modifiers, so it gets what the list
 * can't take; inner first, so a panel's own pull past the top never sees it.
 */
@Composable
internal fun pullToRefresh(
    action: Action,
    env: Env,
    inst: WidgetInstance,
): Modifier {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val threshold = PULL_DP * density
    val run by rememberUpdatedState { action.run(env, emptyList()) }
    val painter = rememberVectorPainter(Icons.Refresh)
    val state = remember { PullState() }
    val connection =
        remember(threshold, inst) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Pulled down and pushed back up: the spinner goes back before the list scrolls.
                    if (source != NestedScrollSource.UserInput || available.y >= 0f || state.distance <= 0f || state.refreshing) return Offset.Zero
                    state.settling?.cancel()
                    val before = state.distance
                    state.distance = (before + available.y).coerceAtLeast(0f)
                    return Offset(0f, state.distance - before)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // What the list couldn't scroll down at its top: the pull, at half the finger's speed, as Android's.
                    // Only with a finger down: a mouse wheel is user input too, but no fling follows it to let go.
                    if (source != NestedScrollSource.UserInput || available.y <= 0f || state.refreshing || !state.pressed) return Offset.Zero
                    // Caught again while it eased back up.
                    state.settling?.cancel()
                    state.distance = (state.distance + available.y / 2).coerceAtMost(threshold * 1.5f)
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (state.distance <= 0f || state.refreshing) return Velocity.Zero
                    if (state.distance < threshold) {
                        state.settling = scope.launch { state.settle(0f) }
                        return available
                    }
                    state.refreshing = true
                    val started = SystemClock.uptimeMillis()
                    run()
                    scope.launch {
                        val spinning =
                            launch {
                                state.spin.snapTo(0f)
                                state.spin.animateTo(360f, infiniteRepeatable(tween(900, easing = LinearEasing)))
                            }
                        try {
                            state.settle(threshold * 0.75f)
                            // Sources refresh() woke, or a URL the action changed; none at all, it only spins briefly.
                            if (withTimeoutOrNull(START_MS) { snapshotFlow { inst.fetching() }.first { it } } != null) {
                                withTimeoutOrNull(MAX_MS) { snapshotFlow { inst.fetching() }.first { !it } }
                            }
                            delay(MIN_MS - (SystemClock.uptimeMillis() - started))
                            state.settle(0f)
                        } finally {
                            // Nothing that suspends: this also runs when the node goes, cancelled.
                            state.refreshing = false
                            spinning.cancel()
                        }
                    }
                    return available
                }
            }
        }
    // Whether a finger is down on the list, seen before the list itself sees it, consuming nothing.
    val touches =
        Modifier.pointerInput(state) {
            awaitPointerEventScope {
                while (true) state.pressed = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
            }
        }
    // A layer each side of the spinner: drawing it again each frame of a pull or a spin re-records only it, not the list
    // under it or the screen around it.
    return touches.nestedScroll(connection).graphicsLayer().drawWithContent {
        drawContent()
        val d = state.distance
        if (d <= 0f) return@drawWithContent
        val disc = 36.dp.toPx()
        val icon = 20.dp.toPx()
        val progress = (d / threshold).coerceAtMost(1f)
        // Spinning once let go; before, it turns with the pull, three quarters of a turn by the point where it'll run.
        val angle = if (state.refreshing) state.spin.value else progress * 270f
        val ready = state.refreshing || d >= threshold
        clipRect {
            translate(size.width / 2 - disc / 2, d - disc) {
                drawCircle(Palette.surface, disc / 2, Offset(disc / 2, disc / 2), alpha = progress)
                rotate(angle, Offset(disc / 2, disc / 2)) {
                    translate((disc - icon) / 2, (disc - icon) / 2) {
                        with(painter) { draw(Size(icon, icon), progress, ColorFilter.tint(if (ready) Palette.accent else Palette.muted)) }
                    }
                }
            }
        }
    }.graphicsLayer()
}

private class PullState {
    /** How far down the spinner is, in px: the pull while the finger's down, then where it eases to. */
    var distance by mutableFloatStateOf(0f)
    var refreshing by mutableStateOf(false)

    /** The spinner's turn while refreshing, in degrees; read while drawing, so it only redraws. */
    val spin = Animatable(0f)

    /** Easing back up after a pull too short to run; a new pull stops it, so the two don't fight. */
    var settling: Job? = null

    /** A finger (or a pressed button) is down on the list; a pull only follows one. Not state: nothing draws from it. */
    var pressed = false

    suspend fun settle(to: Float) = animate(distance, to) { v, _ -> distance = v }
}
