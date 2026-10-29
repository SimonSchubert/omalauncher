package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import dev.omalauncher.widgets.WidgetEngine
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.omalauncher.R
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.widgets.WidgetSpec
import dev.omalauncher.widgets.lang.text
import dev.omalauncher.widgets.render.WidgetContent
import dev.omalauncher.widgets.render.WidgetScreen
import androidx.compose.foundation.layout.fillMaxWidth
import dev.omalauncher.widgets.runtime.WidgetInstance

@Composable
internal fun WidgetTile(
    spec: WidgetSpec,
    instance: WidgetInstance?,
    cells: Pair<Int, Int>?,
    focused: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
    needs: List<String>,
    install: WidgetEngine.Install?,
    onInstall: () -> Unit,
    onFix: (String) -> Unit,
    /** Where Linux stands, if the widget needs it and it isn't running: a placeholder keeps its cells. */
    waitsForLinux: LinuxState? = null,
    onInstallLinux: () -> Unit = {},
    /** How much of a face the soft keyboard covers while one of its fields has it, in pixels: its `screenInset` then. */
    keyboard: Float = 0f,
) {
    // A face draws straight on the wallpaper, without a tile's frame; selected, it still shows the focus border.
    val bare = spec.isFace && waitsForLinux == null && instance != null && needs.isEmpty()
    Box(
        modifier
            .then(
                when {
                    !bare -> Modifier.tile(focused)
                    focused -> Modifier.frame(LauncherStyle.focusWidth, LauncherStyle.focusBorder)
                    else -> Modifier
                },
            ).pointerInput(spec) { detectTapGestures(onTap = { onTap() }) },
    ) {
        if (waitsForLinux != null) {
            // While an installed Linux starts up the tile stays empty; it only offers an install when there's none.
            if (waitsForLinux != LinuxState.Checking && waitsForLinux != LinuxState.Installed) {
                LinuxPlaceholder(spec.name, installing = waitsForLinux is LinuxState.Installing, onInstallLinux)
            }
        } else if (instance != null && needs.isNotEmpty()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(spec.name, style = Mono.copy(color = Palette.muted))
                BasicText(stringResource(R.string.home_widget_needs, needs.joinToString(", ")), style = Mono)
                when (install) {
                    WidgetEngine.Install.Running -> BasicText(stringResource(R.string.home_installing), style = Mono.copy(color = Palette.accent))
                    is WidgetEngine.Install.Failed -> {
                        BasicText(install.log.lines().lastOrNull { it.isNotBlank() }.orEmpty(), style = Mono.copy(color = Palette.red, fontSize = TextSize.Small), maxLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TileButton(stringResource(R.string.home_retry), Palette.accent, onInstall)
                            TileButton(stringResource(R.string.home_fix_with_agent), Palette.muted) { onFix(install.log) }
                        }
                    }
                    null -> TileButton(stringResource(R.string.home_install), Palette.accent, onInstall)
                }
            }
        } else if (instance == null) {
            Column(Modifier.padding(12.dp)) {
                BasicText(spec.file.name, style = Mono.copy(color = Palette.red))
                BasicText(spec.error.orEmpty(), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        } else if (bare) {
            // The whole screen: under the status bar and the navigation bar. The nodes keep clear of the camera cutout, of
            // the status bar while it stays (statusBar true) and of a navigation bar at the side, in landscape; the one along
            // the bottom is the face's to mind, through screenInset, as an edge app's is, and so is the keyboard while one of
            // its fields has it. With `edge` the top and the sides are the face's to mind too, through screenTop, screenLeft
            // and screenRight.
            val top = if (instance.showsStatusBar()) WindowInsets.statusBars.union(WindowInsets.displayCutout) else WindowInsets.displayCutout
            val covered = top.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal).union(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
            WidgetContent(instance, Modifier.fillMaxSize(), cells, if (instance.program.edge) WindowInsets(0, 0, 0, 0) else covered)
            ScreenInset(instance, face = true, keyboard = keyboard)
            if (instance.program.edge) EdgeFaceInsets(instance, covered)
        } else {
            WidgetContent(instance, Modifier.fillMaxSize(), cells)
        }
    }
}

/** A widget that runs Linux commands, before Linux is installed: its name and an offer to install. */
@Composable
private fun LinuxPlaceholder(
    name: String,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(name, style = Mono.copy(color = Palette.muted), maxLines = 1)
        Spacer(Modifier.weight(1f))
        BasicText(stringResource(R.string.home_widget_needs_linux), style = Mono.copy(color = Palette.accent))
        BasicText(
            stringResource(R.string.home_widget_needs_linux_text),
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TileButton(stringResource(if (installing) R.string.home_installing else R.string.home_install), Palette.accent, onInstall)
    }
}

@Composable
private fun TileButton(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    BasicText(
        label,
        Modifier
            .frame(1.dp, color, radius = 8.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = Mono.copy(color = color),
    )
}

/**
 * An owl app's window: its title, with a back chevron while its `back` holds, over its view. `title: ""` leaves the
 * title row out, for an app that draws its own; Back still goes up a level while `back` holds.
 */
@Composable
internal fun OwlAppWindow(
    spec: WidgetSpec,
    instance: WidgetInstance,
) {
    val title = instance.program.screenTitle?.let { instance.eval(it, instance.root, "title").text() } ?: spec.name
    val up: (() -> Unit)? = if (instance.canGoUp()) { { instance.goUp() } } else null
    // Full screen like any Android app: no window border or side gap, and no ✕ (Back leaves the app and
    // Recents closes it; close() still does). The view runs to the screen's edges.
    // With `edge` it runs on under the navigation bar too: only the keyboard pushes its bottom up.
    // With `statusBar: false` it runs up to the top edge, around a camera cutout, which `screenTop` measures.
    val statusBar = instance.showsStatusBar()
    var sides = WindowInsetsSides.Horizontal
    if (!instance.program.edge) sides += WindowInsetsSides.Bottom
    if (statusBar) sides += WindowInsetsSides.Top
    val insets = WindowInsets.safeDrawing.only(sides).union(WindowInsets.ime)
    Column(Modifier.fillMaxSize().background(Palette.background).windowInsetsPadding(insets)) {
        if (title.isNotEmpty()) Box(Modifier.padding(horizontal = PanelPadding, vertical = 8.dp)) { BackTitle(title, up) }
        WidgetScreen(instance, Modifier.weight(1f).fillMaxWidth())
    }
    if (instance.program.edge) ScreenInset(instance)
    ScreenTop(instance, statusBar)
}

/**
 * True while a widget is drawn off screen for its preview picture. That drawing shares the live instance, and has no
 * status or navigation bar, so it leaves the insets the home screen measured alone.
 */
internal val LocalOffscreen = staticCompositionLocalOf { false }

/**
 * Tells an edge face what [covered] takes of the screen, the status bar, a camera cutout and a navigation bar at the side:
 * its top as `screenTop`, and its sides, in landscape, as `screenLeft` and `screenRight`.
 */
@Composable
private fun EdgeFaceInsets(
    instance: WidgetInstance,
    covered: WindowInsets,
) {
    if (LocalOffscreen.current) return
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val top = with(density) { covered.getTop(density).toDp().value.toDouble() }
    val left = with(density) { covered.getLeft(density, direction).toDp().value.toDouble() }
    val right = with(density) { covered.getRight(density, direction).toDp().value.toDouble() }
    SideEffect {
        instance.setScreenTop(top)
        instance.setScreenSides(left, right)
    }
}

/** Tells an app's `screenTop` how much of its top a camera cutout covers: none while the status bar shows, which the view starts below. */
@Composable
private fun ScreenTop(
    instance: WidgetInstance,
    statusBar: Boolean,
) {
    if (LocalOffscreen.current) return
    val density = LocalDensity.current
    val covered = if (statusBar) 0 else WindowInsets.displayCutout.getTop(density)
    val dp = with(density) { covered.toDp().value.toDouble() }
    SideEffect { instance.setScreenTop(dp) }
}

/**
 * Tells an `edge` app's or a [face]'s `screenInset` how much of its bottom the navigation bar covers; for an app none while
 * the keyboard is up, and for a face the [keyboard] (in pixels) while one of its fields has it. A face goes straight back
 * to the navigation bar as the keyboard starts closing, rather than to none until it has.
 */
@Composable
private fun ScreenInset(
    instance: WidgetInstance,
    face: Boolean = false,
    keyboard: Float = 0f,
) {
    if (LocalOffscreen.current) return
    val density = LocalDensity.current
    val covered =
        if (face) {
            maxOf(WindowInsets.navigationBars.getBottom(density).toFloat(), keyboard)
        } else {
            (WindowInsets.safeDrawing.getBottom(density) - WindowInsets.ime.getBottom(density)).coerceAtLeast(0).toFloat()
        }
    val dp = with(density) { covered.toDp().value.toDouble() }
    SideEffect { instance.setScreenInset(dp) }
}

/** A widget's screen { }, full screen over home in an Omarchy window titled by its `title` or the widget's name. */
@Composable
internal fun WidgetScreenPanel(
    spec: WidgetSpec,
    instance: WidgetInstance,
    onDismiss: () -> Unit,
) {
    val title = instance.program.screenTitle?.let { instance.eval(it, instance.root, "title").text() } ?: spec.name
    OverlayPanel(title, onDismiss) {
        WidgetScreen(instance, Modifier.weight(1f).fillMaxWidth())
    }
}
