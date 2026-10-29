package dev.omalauncher.ui

import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState

/** Where landscape home keeps the recent apps strip and the bar: the side portrait's bottom turned to. */
enum class HomeSide { Left, Right }

/**
 * The window's size, turned to match the configuration's orientation. Before Android 14, Compose hears of a
 * configuration change through the application, before the activity's own bounds follow: coming back from an
 * app that forced landscape, it measured the window as still landscape and kept that until the next change.
 */
@Composable
internal fun windowSize(): IntSize {
    val size = LocalWindowInfo.current.containerSize
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    return if (portrait == size.width > size.height) IntSize(size.height, size.width) else size
}

/** Whether home turns portrait's grid on its side: landscape, unless the window shows two pages. */
@Composable
internal fun homeTurned(): Boolean {
    val window = windowSize()
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
    val window = windowSize()
    val tablet = with(LocalDensity.current) { minOf(window.width, window.height).toDp() } >= 600.dp
    return when {
        !homeTurned() || tablet -> null
        rotation == Surface.ROTATION_270 -> HomeSide.Left
        else -> HomeSide.Right
    }
}

/** Keys' way from the bottom toward the grid to [home]: Up, or across from the [side] the bar and the strip stand on. */
internal fun FocusProperties.toHome(
    side: HomeSide?,
    home: FocusRequester,
) {
    when (side) {
        null -> up = home
        HomeSide.Left -> right = home
        HomeSide.Right -> left = home
    }
}

/**
 * Whether home shows two pages side by side: when each half of the window is phone-shaped and wide
 * enough to be worth a page of its own, as on a foldable's inner screen in either orientation. Phones,
 * tablets, phone landscape, and a square screen too narrow to split usefully show one; so does
 * [LauncherStyle.forceSinglePage]. A screen's shape alone can't tell a real foldable from a phone
 * that's merely square, which [TWO_PAGES_MIN_WIDTH] rules out by width.
 */
@Composable
internal fun showsTwoPages(window: IntSize): Boolean {
    if (LauncherStyle.forceSinglePage) return false
    if (window.width / 2f / window.height !in 0.4f..0.65f) return false
    return with(LocalDensity.current) { (window.width / 2).toDp() } >= TWO_PAGES_MIN_WIDTH
}

/** Narrower than this, a "page" isn't worth having its own: smaller than the shortest common phone width. */
private val TWO_PAGES_MIN_WIDTH = 320.dp

/**
 * Room the side takes beside the grid: the strip and the gap between tiles after it, then the rail and the edge's gap
 * (the grid's own gap comes before).
 * Added up in whole pixels, each part rounded as [SideRail] lays it out, so the grid ends exactly where the strip starts.
 */
@Composable
internal fun sideWidth(): Dp =
    with(LocalDensity.current) {
        val gap = HomeGap.roundToPx()
        ((if (LauncherStyle.recentApps) RecentStripHeight.roundToPx() + gap else 0) + PromptBarHeight.roundToPx() + EdgeGap.roundToPx()).toDp()
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
        modifier.fillMaxHeight().padding(vertical = EdgeGap),
        horizontalArrangement = if (side == HomeSide.Left) Arrangement.Start else Arrangement.End,
    ) {
        // Outward from the grid, like portrait from the grid down: the strip, then the bar. Keys come out of the grid
        // to the first of them there is.
        val alone = !(LauncherStyle.recentApps && recentApps(runtime, 1).isNotEmpty())
        val strip = @Composable {
            if (LauncherStyle.recentApps) {
                RecentStrip(
                    runtime,
                    handlers,
                    if (alone) Modifier else Modifier.focusRequester(state.stripEntry).focusGroup(),
                    vertical = true,
                    home = state.homeEntry,
                    side = side,
                )
            }
        }
        if (side == HomeSide.Left) {
            Spacer(Modifier.width(EdgeGap))
            PromptRail(runtime, state, side, alone)
            Spacer(Modifier.width(HomeGap))
            strip()
        } else {
            strip()
            Spacer(Modifier.width(HomeGap))
            PromptRail(runtime, state, side, alone)
            Spacer(Modifier.width(EdgeGap))
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
    side: HomeSide,
    /** With no recent apps beside it, keys come to it out of the grid and go back across. */
    alone: Boolean,
) {
    val linuxState by runtime.bootstrap.state.collectAsState()
    val agent = runtime.agents.default
    // The longest that fits the rail: a letter takes a whole line.
    val hints =
        listOfNotNull(
            agent?.takeIf { linuxState == LinuxState.Installed }?.let { stringResource(R.string.home_prompt_hint_agent, it.bin) },
            stringResource(R.string.home_prompt_hint),
            stringResource(R.string.home_prompt_hint_short),
        )
    val style = Mono.copy(fontSize = TextSize.Item, color = Palette.muted)
    Column(
        Modifier
            .then(if (alone) Modifier.focusRequester(state.stripEntry) else Modifier)
            .width(PromptBarHeight)
            .fillMaxHeight()
            .tile()
            // With no strip between, keys go across to the grid, as portrait's bar goes up to it.
            .focusProperties { if (alone) toHome(side, state.homeEntry) }
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
