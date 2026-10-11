package dev.omalauncher.ui

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState

/** Where landscape home keeps the recent apps strip and the bar: the side portrait's bottom turned to. */
enum class HomeSide { Left, Right }

/** Whether home turns portrait's grid on its side: landscape, unless the window shows two pages. */
@Composable
internal fun homeTurned(): Boolean {
    val window = LocalWindowInfo.current.containerSize
    return window.width > window.height && !showsTwoPages(window)
}

/**
 * The side for the strip and the bar where home turns its grid on its side; null in portrait. Only a phone's
 * landscape is too short for them below the grid: a tablet keeps them there, where a rail would stack its
 * prompt one letter at a time down a long edge.
 */
@Composable
internal fun homeSide(): HomeSide? {
    val view = LocalView.current
    // Turning straight from one landscape to the other changes no size, so nothing would recompose: ask the display.
    var rotation by remember(view) { mutableIntStateOf(view.display?.rotation ?: Surface.ROTATION_0) }
    DisposableEffect(view) {
        val displays = view.context.getSystemService(DisplayManager::class.java)
        val listener =
            object : DisplayManager.DisplayListener {
                override fun onDisplayChanged(displayId: Int) {
                    view.display?.let { rotation = it.rotation }
                }

                override fun onDisplayAdded(displayId: Int) {}

                override fun onDisplayRemoved(displayId: Int) {}
            }
        displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displays.unregisterDisplayListener(listener) }
    }
    val window = LocalWindowInfo.current.containerSize
    val tablet = with(LocalDensity.current) { minOf(window.width, window.height).toDp() } >= 600.dp
    return when {
        !homeTurned() || tablet -> null
        rotation == Surface.ROTATION_270 -> HomeSide.Left
        else -> HomeSide.Right
    }
}

/**
 * Whether home shows two pages side by side: when each half of the window is phone-shaped, as on a
 * foldable's inner screen in either orientation. Phones, tablets and phone landscape show one.
 */
internal fun showsTwoPages(window: IntSize) = window.width / 2f / window.height in 0.4f..0.65f

/**
 * Room the side takes beside the grid: the strip, the rail and the gaps after them (the grid's own gap comes before).
 * Added up in whole pixels, each part rounded as [SideRail] lays it out, so the grid ends exactly where the strip starts.
 */
@Composable
internal fun sideWidth(): Dp =
    with(LocalDensity.current) {
        val gap = HomeGap.roundToPx()
        ((if (LauncherStyle.recentApps) RecentStripHeight.roundToPx() + gap else 0) + PromptBarHeight.roundToPx() + gap).toDp()
    }

/**
 * Landscape home's side: portrait's bottom turned with the grid. The recent apps strip stands
 * beside the grid with its icons upright, and the bar folds into a rail at the edge; tapping it
 * unfolds the bar and the app panel.
 */
@Composable
internal fun SideRail(
    runtime: Runtime,
    state: LauncherState,
    handlers: AppHandlers,
    side: HomeSide,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxHeight().padding(vertical = 6.dp),
        horizontalArrangement = if (side == HomeSide.Left) Arrangement.Start else Arrangement.End,
    ) {
        // Outward from the grid, like portrait from the grid down: the strip, then the bar.
        val strip = @Composable { if (LauncherStyle.recentApps) RecentStrip(runtime, handlers, vertical = true) }
        if (side == HomeSide.Left) {
            Spacer(Modifier.width(6.dp))
            PromptRail(runtime, state)
            Spacer(Modifier.width(6.dp))
            strip()
        } else {
            strip()
            Spacer(Modifier.width(6.dp))
            PromptRail(runtime, state)
            Spacer(Modifier.width(6.dp))
        }
    }
}

/**
 * The bar folded up: its prompt stacked down the rail one upright letter at a time, the cursor
 * waiting under it, and the mic below. A tap unfolds the bar; the mic unfolds it dictating.
 */
@Composable
private fun PromptRail(
    runtime: Runtime,
    state: LauncherState,
) {
    val linuxState by runtime.bootstrap.state.collectAsState()
    val agent = runtime.agents.default
    // The longest that fits the rail: a letter takes a whole line.
    val hints = listOfNotNull(agent?.takeIf { linuxState == LinuxState.Installed }?.let { "open an app or ask ${it.bin}" }, "open an app", "apps")
    val style = Mono.copy(fontSize = TextSize.Item, color = Palette.muted)
    Column(
        Modifier
            .width(PromptBarHeight)
            .fillMaxHeight()
            .tile()
            .clickable { state.open() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.TopCenter) {
            val line = with(LocalDensity.current) { style.fontSize.toDp() } * 1.3f
            // Less the prompt sign and the cursor.
            val room = (maxHeight / line).toInt() - 2
            val hint = hints.firstOrNull { it.length <= room }.orEmpty()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(line), contentAlignment = Alignment.Center) {
                    BasicText("❯", Modifier.rotate(90f), style = style.copy(color = Palette.accent))
                }
                for (c in hint) {
                    // A space is half a line: enough to part the words.
                    Box(Modifier.height(if (c == ' ') line / 2 else line), contentAlignment = Alignment.Center) {
                        if (c != ' ') BasicText(c.toString(), style = style)
                    }
                }
                Box(Modifier.padding(top = 2.dp).size(10.dp, line * 0.8f).background(Palette.accent))
            }
        }
        Box(
            Modifier
                .size(PromptBarHeight)
                .clickable {
                    state.promptRequest = PromptRequest.Listen
                    state.open()
                },
            contentAlignment = Alignment.Center,
        ) { MicIcon(Palette.foreground) }
    }
}
