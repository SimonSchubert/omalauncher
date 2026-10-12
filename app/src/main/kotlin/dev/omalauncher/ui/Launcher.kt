package dev.omalauncher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import dev.omalauncher.Runtime
import dev.omalauncher.widgets.checkSecretsCommand
import dev.omalauncher.widgets.publishWidgetCommand
import dev.omalauncher.widgets.reviewSharedWidgetCommand
import dev.omalauncher.widgets.render.LocalHomeCovered

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
    // Something that needs Linux was asked for (a terminal, Super+Enter): offer to install it.
    LaunchedEffect(runtime.linuxWanted) {
        if (runtime.linuxWanted) {
            runtime.linuxWanted = false
            state.close()
            state.linuxOpen = true
        }
    }
    BackHandler {
        when {
            // Screens opened from settings return to it; settings itself is last.
            state.shared != null -> state.shared = null
            state.sharing != null -> state.sharing = null
            state.linuxOpen -> state.linuxOpen = false
            state.licensesOpen -> state.licensesOpen = false
            state.agentsOpen -> state.agentsOpen = false
            state.storeOpen -> state.storeOpen = false
            state.themeBrowserOpen -> state.themeBrowserOpen = false
            state.styleOpen -> state.styleOpen = false
            state.packagesOpen -> state.packagesOpen = false
            state.settingsOpen -> state.settingsOpen = false
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
    val covered = remember(state) { { state.homeCovered || runtime.widgets.openScreen != null } }
    CompositionLocalProvider(LocalHomeCovered provides covered) {
        Column(Modifier.fillMaxSize().safePadding()) {
            HomeScreen(runtime, state, Modifier.weight(1f))
        }
    }
    HomeMenu(runtime, state)

    // Only whether it shows is read here; the animation itself runs in the layer, without recomposing.
    val panelShown by remember(state) { derivedStateOf { state.drawer.value > 0.01f } }
    val search by remember(runtime, state) { derivedStateOf { searchApps(runtime, state.query) } }
    val highlighted = search.highlighted(state.picked)

    fun launch(hit: AppHit) {
        // The private space's apps leave no trace in the phone's own profile: not in recents, not in the ranking.
        if (!(hit is AppHit.Android && hit.app.private)) runtime.usage.record(hit.key)
        state.query = ""
        state.closeNow()
        when (hit) {
            is AppHit.Linux -> runtime.launch(hit.app)
            is AppHit.Android -> runtime.androidApps.open(hit.app)
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
                    }
                    state.close()
                },
                info = { app ->
                    state.closeNow()
                    runtime.androidApps.openInfo(app)
                },
                uninstall = { app -> runtime.androidApps.uninstall(app) },
            )
        }

    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    if (panelShown) {
        // Near-opaque like stock drawers: home widgets bleeding through made icons hard to read.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = state.drawer.value.coerceAtMost(1f) }
                .background(Palette.background.copy(alpha = 0.97f))
                .clickable { state.close() }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { dy -> state.dragBy(-dy / screenHeight) },
                    onDragStopped = { velocity -> state.settle(velocity) },
                ).swipeTabs(runtime, state),
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
                        onSettings = {
                            state.closeNow()
                            state.settingsOpen = true
                        },
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
                if (LauncherStyle.recentApps && !panelShown) RecentStrip(runtime, handlers)
                // Insets itself by the grid gap, so it lines up with the tiles and the gap still takes taps.
                PromptBar(runtime, state, search = search, highlighted = highlighted, onLaunch = handlers.launch)
            }
        }
    }

    // Full-screen panels draw over the bar.
    ThemesOverlay(runtime, state)
}

@Composable
private fun ThemesOverlay(
    runtime: Runtime,
    state: LauncherState,
) {
    // A widget's screen first: what it opens (themes, agents) draws on top of it.
    runtime.widgets.screenInstance()?.let { (spec, inst) -> WidgetScreenPanel(spec, inst, onDismiss = { runtime.widgets.openScreen = null }) }
    // Settings first so the screens it opens draw on top of it.
    if (state.settingsOpen) {
        SettingsScreen(
            runtime,
            onDismiss = { state.settingsOpen = false },
            onStyle = { state.styleOpen = true },
            onAgents = { state.agentsOpen = true },
            onPackages = { state.packagesOpen = true },
            onStore = { state.storeOpen = true },
            onLinux = { state.linuxOpen = true },
            onLicenses = { state.licensesOpen = true },
        )
    }
    if (state.packagesOpen) PackagesScreen(runtime, onDismiss = { state.packagesOpen = false })
    if (state.styleOpen) StyleScreen(runtime, onDismiss = { state.styleOpen = false }, onBrowse = { state.themeBrowserOpen = true })
    if (state.themeBrowserOpen) ThemeCatalogScreen(runtime.themes, onDismiss = { state.themeBrowserOpen = false })
    if (state.storeOpen) StoreScreen(runtime.widgets, runtime.agents.installed, state.currentPage, runtime.themes.picked, onDismiss = { state.storeOpen = false })
    if (state.licensesOpen) LicensesScreen(onDismiss = { state.licensesOpen = false })
    if (state.linuxOpen) LinuxScreen(runtime, onDismiss = { state.linuxOpen = false })
    state.shared?.let { link ->
        SharedWidgetScreen(
            runtime.widgets,
            link,
            state.currentPage,
            // Stays open underneath, to add it once the agent has had its say.
            onReview = { path ->
                val agent = runtime.agents.default
                if (agent == null) state.agentsOpen = true else runtime.terminal(reviewSharedWidgetCommand(agent.bin, path), "Review widget", agent)
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
                        if (agent == null) state.agentsOpen = true else runtime.terminal(publishWidgetCommand(agent.bin, spec), "Publish ${spec.name}", agent)
                    }
                },
            // Closes the screen: what the agent changes would be shared from a fresh one.
            onCheck = {
                state.sharing = null
                val agent = runtime.agents.default
                if (agent == null) state.agentsOpen = true else runtime.terminal(checkSecretsCommand(agent.bin, spec), "Check ${spec.name}", agent)
            },
            onDismiss = { state.sharing = null },
        )
    }
    if (state.agentsOpen) {
        AgentsScreen(
            runtime,
            onDismiss = { state.agentsOpen = false },
            onOpen = { agent ->
                state.agentsOpen = false
                runtime.terminal(agent.bin, agent.name, agent)
            },
        )
    }
}

/** Clear of the system bars and of the camera cutout, which landscape puts at a side. */
@Composable
private fun Modifier.safePadding() = windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
