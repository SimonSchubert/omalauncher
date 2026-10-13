package dev.omalauncher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.omalauncher.Runtime
import dev.omalauncher.theme.Style
import dev.omalauncher.theme.StyleStore
import dev.omalauncher.widgets.checkSecretsCommand
import dev.omalauncher.widgets.publishWidgetCommand
import dev.omalauncher.widgets.reviewSharedWidgetCommand
import dev.omalauncher.widgets.render.LocalHomeCovered
import dev.omalauncher.widgets.render.LocalInputFocus

@Composable
fun LauncherRoot(
    runtime: Runtime,
    state: LauncherState,
) {
    state.bindScope()
    val ready = runtime.launcherReady
    // With our live wallpaper active the window is transparent and Android draws the wallpaper behind it.
    val showOwnBackground = !ready || !runtime.themes.liveWallpaperActive
    Box(
        Modifier
            .fillMaxSize()
            .then(if (showOwnBackground) Modifier.background(Palette.background) else Modifier),
    ) {
        if (ready) DesktopScreen(runtime, state) else SetupScreen(runtime)
    }
}

@Composable
private fun DesktopScreen(
    runtime: Runtime,
    state: LauncherState,
) {
    val context = LocalContext.current
    // Target SDK 37 blocks LAN until ACCESS_LOCAL_NETWORK is granted (Linux + local http widgets).
    val askLocalNet = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (needsLocalNetworkPermission(
                sdkInt = Build.VERSION.SDK_INT,
                granted = context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED,
            )
        ) {
            askLocalNet.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
    BackHandler {
        when {
            state.shared != null -> state.shared = null
            state.sharing != null -> state.sharing = null
            runtime.widgets.openScreen != null -> runtime.widgets.openScreen = null
            else -> state.close()
        }
    }

    if (!runtime.themes.liveWallpaperActive) {
        runtime.themes.wallpaper?.let { bmp ->
            // Read while drawing, so swiping pages only redraws this.
            Canvas(Modifier.fillMaxSize()) {
                val src = wallpaperCrop(bmp.width, bmp.height, size.width.toInt(), size.height.toInt(), state.wallpaperOffset)
                drawImage(
                    bmp,
                    srcOffset = IntOffset(src.left, src.top),
                    srcSize = IntSize(src.width(), src.height()),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
    SystemBarFrames()
    val covered = remember(state) { { state.homeCovered || runtime.widgets.openScreen != null } }
    CompositionLocalProvider(LocalHomeCovered provides covered) {
        Column(Modifier.fillMaxSize().safePadding()) {
            HomeScreen(runtime, state, Modifier.weight(1f))
        }
    }
    HomeMenu(runtime, state)

    // Only whether it shows is read here; the animation itself runs in the layer, without recomposing.
    val panelShown by remember(state) { derivedStateOf { state.drawer.value > 0.01f } }
    // Closing it forgets the search, like stock drawers: the next swipe up shows every app, not the old results.
    LaunchedEffect(panelShown) { if (!panelShown) state.query = "" }
    val search by remember(runtime, state) { derivedStateOf { searchApps(runtime, state.query) } }
    val highlighted = search.highlighted(state.picked)

    fun launch(hit: AppHit) {
        runtime.usage.record(hit.key)
        state.query = ""
        state.closeNow()
        when (hit) {
            is AppHit.Linux -> runtime.launch(hit.app)
            is AppHit.Android -> runtime.androidApps.open(hit.app)
            is AppHit.Shortcut -> runtime.androidApps.open(hit.shortcut)
        }
    }

    val handlers =
        remember(runtime, state, context) {
            AppHandlers(
                launch = ::launch,
                // Pins to the page that's showing; the panel closes so the new tile is visible.
                pin = { hit ->
                    when (hit) {
                        is AppHit.Linux -> runtime.widgets.shortcuts.addLinux(hit.app, state.currentPage)
                        is AppHit.Android -> runtime.widgets.shortcuts.addAndroid(hit.app, state.currentPage)
                        is AppHit.Shortcut -> runtime.widgets.shortcuts.addAndroidShortcut(hit.shortcut, state.currentPage)
                    }
                    state.close()
                },
                info = { app ->
                    state.closeNow()
                    runtime.androidApps.openInfo(app)
                },
                uninstall = { app -> runtime.androidApps.uninstall(app) },
                // Off home too: its tile would only be a name that opens nothing.
                remove = { shortcut ->
                    runtime.androidApps.remove(shortcut)
                    runtime.widgets.shortcuts.removeAndroidShortcut(shortcut)
                },
                // Opening one leaves the drawer at once, as opening an app does; pinning one closes it to show the new tile, as pin does.
                shortcuts = { app -> shortcutMenu(runtime, app, page = { state.currentPage }, onOpen = state::closeNow, onPinned = state::close) },
            )
        }

    if (panelShown) {
        // Near-opaque like stock drawers: home widgets bleeding through made icons hard to read.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = state.drawer.value.coerceAtMost(1f) }
                .background(Palette.background.copy(alpha = 0.97f))
                .clickable { state.close() }
                .drawerSwipe(state)
                .swipeTabs(runtime, state),
        )
    }
    // The bar and the panel above it; the keyboard lifts both. Nothing here takes touches while the panel is closed,
    // except the bar. A phone's landscape folds them into a rail beside the grid, and unfolds them over it, centered.
    val side = homeSide()
    if (side != null && !panelShown) {
        if (runtime.terminals.active == null) {
            SideRail(runtime, state, handlers, side, Modifier.fillMaxSize().safePadding())
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .safePadding()
                .imePadding()
                .then(if (side != null) Modifier.wrapContentWidth().widthIn(max = ReadingWidth) else Modifier),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (panelShown) {
                    AppPanel(
                        runtime = runtime,
                        state = state,
                        search = search,
                        highlighted = highlighted,
                        handlers = handlers,
                        onAsk = { state.promptRequest = PromptRequest.Send },
                        onSettings = { state.show() },
                        modifier =
                            Modifier.graphicsLayer {
                                val progress = state.drawer.value
                                alpha = progress.coerceAtMost(1f)
                                translationY = (1f - progress) * size.height * 0.6f
                            },
                    )
                }
            }
            // Hidden while a terminal tile types: it would sit on the keyboard, over the tile.
            if (runtime.terminals.active == null || runtime.terminals.keyboardLeaving) {
                // Home only: the open app list has everything.
                // Swiping up from either does what it does on home, as from the dock on stock launchers.
                if (LauncherStyle.recentApps && !panelShown) RecentStrip(runtime, handlers, Modifier.homeSwipe(runtime, state))
                // Insets itself by the grid gap, so it lines up with the tiles and the gap still takes taps.
                PromptBar(runtime, state, search = search, highlighted = highlighted, onLaunch = handlers.launch, modifier = Modifier.homeSwipe(runtime, state))
            }
        }
    }

    // Full-screen panels draw over the bar.
    ThemesOverlay(runtime, state)
    StyleErrors(runtime.style)
}

/**
 * What style.toml got wrong, at the top over everything, like Hyprland's bar for config errors: errors.txt is out
 * of sight without a terminal. A tap hides the problems shown; new ones show again.
 */
@Composable
private fun StyleErrors(style: StyleStore) {
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    val shown = style.errors.filterNot { it in hidden }
    if (shown.isEmpty()) return
    Box(Modifier.fillMaxSize().safePadding().padding(PanelGap), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = ReadingWidth)
                .fillMaxWidth()
                .frame(2.dp, Palette.red, Palette.background)
                .clickable { hidden = hidden + shown }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            BasicText("style.toml: ${if (shown.size == 1) "1 problem" else "${shown.size} problems"}", style = Mono.copy(color = Palette.red))
            for (line in shown.take(MAX_ERRORS)) BasicText(line, style = Mono.copy(fontSize = TextSize.Small))
            val more = if (shown.size > MAX_ERRORS) "${shown.size - MAX_ERRORS} more in errors.txt · " else ""
            BasicText("${more}tap to hide", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
    }
}

private const val MAX_ERRORS = 3

@Composable
private fun ThemesOverlay(
    runtime: Runtime,
    state: LauncherState,
) {
    // A widget's screen first: what it opens (themes, agents) draws on top of it.
    runtime.widgets.screenInstance()?.let { (spec, inst) ->
        CompositionLocalProvider(LocalInputFocus provides true) {
            WidgetScreenPanel(spec, inst, onDismiss = { runtime.widgets.openScreen = null })
        }
    }
    state.shared?.let { link ->
        SharedWidgetScreen(
            runtime.widgets,
            link,
            state.currentPage,
            // Stays open underneath, to add it once the agent has had its say.
            onReview = { path ->
                val agent = runtime.agents.default
                if (agent == null) state.show(SettingsPage.Agents) else runtime.terminal(reviewSharedWidgetCommand(agent.bin, path), "Review widget", agent)
            },
            onDismiss = { state.shared = null },
        )
    }
    state.sharing?.let { spec ->
        ShareWidgetScreen(
            spec,
            // Only your own: a widget from the store or from someone else isn't yours to submit.
            onPublish =
                if (!runtime.widgets.isOwnWidget(spec)) {
                    null
                } else {
                    {
                        state.sharing = null
                        // The default agent opens a pull request (or an issue) on the store repo.
                        val agent = runtime.agents.default
                        if (agent == null) state.show(SettingsPage.Agents) else runtime.terminal(publishWidgetCommand(agent.bin, spec), "Publish ${spec.name}", agent)
                    }
                },
            // Closes the screen: what the agent changes would be shared from a fresh one.
            onCheck = {
                state.sharing = null
                val agent = runtime.agents.default
                if (agent == null) state.show(SettingsPage.Agents) else runtime.terminal(checkSecretsCommand(agent.bin, spec), "Check ${spec.name}", agent)
            },
            onDismiss = { state.sharing = null },
        )
    }
}

/** Clear of the system bars and of the camera cutout, which landscape puts at a side. */
@Composable
private fun Modifier.safePadding() = windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))

/**
 * Bands behind Android's status bar and navigation bar, so its clock, notification icons and gesture handle sit on
 * the same glass as home's tiles rather than on the wallpaper. Each fills its bar out to the screen's edges, with the
 * tiles' border only along the side facing them, a gap away. Under the drawer and full-screen panels, which cover the
 * bars themselves.
 */
@Composable
private fun SystemBarFrames() {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val status = WindowInsets.statusBars
    val nav = WindowInsets.navigationBars
    with(density) {
        val top = status.getTop(density).toDp()
        val bottom = nav.getBottom(density).toDp()
        // Three-button navigation stands at a side in a phone's landscape.
        val left = nav.getLeft(density, direction).toDp()
        val right = nav.getRight(density, direction).toDp()
        val status = Style.STATUS_BAR
        val navigation = Style.NAVIGATION_BAR
        Box(Modifier.fillMaxSize()) {
            // Thinner than that leaves no room for a band: a gesture bar hidden in the system's settings, say.
            fun Dp.band() = takeIf { it >= 8.dp }
            val across = Modifier.fillMaxWidth()
            top.band()?.let { Band(across.align(Alignment.TopCenter).height(it), status) { w -> Rect(0f, size.height - w, size.width, size.height) } }
            bottom.band()?.let { Band(across.align(Alignment.BottomCenter).height(it), navigation) { w -> Rect(0f, 0f, size.width, w) } }
            // Between the top and bottom ones, a gap from each. Left and right of the screen, whichever way text runs.
            val between = Modifier.fillMaxSize().padding(top = top + EdgeGap, bottom = bottom + EdgeGap)
            left.band()?.let { Band(between.wrapContentWidth(AbsoluteAlignment.Left).width(it), navigation) { w -> Rect(size.width - w, 0f, size.width, size.height) } }
            right.band()?.let { Band(between.wrapContentWidth(AbsoluteAlignment.Right).width(it), navigation) { w -> Rect(0f, 0f, w, size.height) } }
        }
    }
}

/**
 * [bar]'s band, the tiles' glass unless style.toml says otherwise, and its border only where [line]
 * says: the side facing the tiles. Nothing when the bar isn't framed.
 */
@Composable
private fun Band(
    modifier: Modifier,
    bar: Style.Bar,
    line: DrawScope.(width: Float) -> Rect,
) {
    val fill = LauncherStyle.barFill(bar) ?: return
    val border = LauncherStyle.barLine(bar)
    val width = LauncherStyle.barLineWidth(bar)
    Box(
        modifier.background(fill).drawBehind {
            if (border == null || width == 0.dp) return@drawBehind
            val r = line(width.toPx())
            drawRect(border, r.topLeft, r.size)
        },
    )
}
