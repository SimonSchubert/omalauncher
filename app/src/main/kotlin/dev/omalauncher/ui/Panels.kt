package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import dev.omalauncher.Runtime
import dev.omalauncher.widgets.render.LocalInputFocus
import dev.omalauncher.widgets.render.WidgetScreen

/*
 * Panels (`panel "…" { }` files) are views a swipe on home opens, picked in gesture settings. The launcher keeps
 * what's around them: the backdrop, moving with the finger, closing by a pull or Back. The app drawer is one.
 */

/**
 * The drawer: the panel swiping up opens (see WidgetEngine.drawerPanel), moving with the finger over the backdrop, with
 * the prompt bar under it, whose text is its `query`. Pulling down past the top of its list closes it, as the drawer, and so
 * does a tap on its empty space.
 */
@Composable
internal fun UpPanel(
    runtime: Runtime,
    state: LauncherState,
    name: String,
    /** Closed but kept composed (see Launcher): reopening it then doesn't compose and measure its list again. */
    hidden: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val inst = runtime.widgets.panelInstance(name)?.second ?: return
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(inst, hidden) {
        snapshotFlow { hidden() }.collect { closed ->
            when {
                // Each time it opens. Cheap when nothing changed, as for the drawer: Linux apps installed since show up in android.drawer.
                !closed -> runtime.refreshApps()
                // What had the keys in it lets go as it closes: kept, it would hold them out of sight.
                focused -> focusManager.clearFocus(force = true)
            }
        }
    }
    // The panel reads the bar's text itself. Set before it composes, below, so its first frame has it too.
    val bar = remember(state) { { state.query } }
    if (inst.query !== bar) inst.query = bar
    // Opened from the top later, the same file sees "".
    DisposableEffect(inst, bar) { onDispose { if (inst.query === bar) inst.query = { "" } } }
    val pull = rememberDrawerPull(state)
    CompositionLocalProvider(LocalInputFocus provides true) {
        WidgetScreen(
            inst,
            modifier
                .fillMaxSize()
                .onFocusChanged { focused = it.hasFocus }
                .nestedScroll(pull)
                // A tap nothing in the panel takes closes it, as one on the backdrop beside the drawer's list does.
                .pointerInput(state) { detectTapGestures { state.close() } },
            hidden,
        )
    }
}

/**
 * The panel swiping down opens: it comes down from the top under the finger and goes back up the same way, as the
 * drawer comes up. Fully down it's opaque, the whole screen; pushing up past the end of its list closes it.
 */
@Composable
internal fun PulledPanel(
    runtime: Runtime,
    state: LauncherState,
) {
    val name = state.pulled ?: return
    val inst =
        runtime.widgets.panelInstance(name)?.second ?: run {
            // Removed or broken while down: it goes.
            LaunchedEffect(name) { state.closePull() }
            return
        }
    CompositionLocalProvider(LocalInputFocus provides true) {
        // As the drawer moves, mirrored: the backdrop fades in where it is and the content comes down with the finger.
        // Both read the position while drawing, so the drag only redraws layers.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = state.pull.value
                    // Both fade as they draw, as the drawer's do (see Launcher): no full-screen buffer each frame.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .background(Palette.background.copy(alpha = inst.panelOpacity()))
                .pullSwipe(state)
                .holdsKeys()
                .safeDrawingPadding()
                .graphicsLayer {
                    // Faded in as it comes, as the drawer's content: half down, it doesn't draw over home's tiles at full strength.
                    alpha = state.pull.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    translationY = -(1f - state.pull.value) * size.height * 0.6f
                },
        ) {
            WidgetScreen(inst, Modifier.fillMaxSize())
        }
    }
}

/** A panel fully open, without home behind it, for owl-check's picture of it. */
@Composable
internal fun PanelPreview(inst: dev.omalauncher.widgets.runtime.WidgetInstance) {
    Box(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding()) {
        WidgetScreen(inst, Modifier.fillMaxSize())
    }
}
