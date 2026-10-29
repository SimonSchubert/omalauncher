package dev.omalauncher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
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
import dev.omalauncher.widgets.HomeStore
import dev.omalauncher.widgets.checkSecretsCommand
import dev.omalauncher.widgets.editWidgetCommand
import dev.omalauncher.widgets.publishWidgetCommand
import dev.omalauncher.widgets.reviewSharedWidgetCommand
import dev.omalauncher.widgets.render.Backdrop
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.omalauncher.R
import dev.omalauncher.widgets.render.LocalBackdrop
import dev.omalauncher.widgets.render.LocalHomeCovered
import dev.omalauncher.widgets.render.LocalInputFocus
import dev.omalauncher.widgets.render.LocalMenuOpened

@Composable
fun LauncherRoot(
    runtime: Runtime,
    state: LauncherState,
) {
    state.bindScope()
    val ready = runtime.launcherReady
    // With our live wallpaper active the window is transparent and Android draws the wallpaper behind it. Home draws its
    // own over the whole window, so the color under it would only be drawn over.
    val showOwnBackground = !ready || !runtime.themes.liveWallpaperActive && runtime.themes.wallpaper == null
    Box(
        Modifier
            .fillMaxSize()
            .then(if (showOwnBackground) Modifier.background(Palette.background) else Modifier)
            .keysRoot(),
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
    BackHandler {
        when {
            state.shared != null -> state.shared = null
            state.sharing != null -> state.sharing = null
            runtime.widgets.openScreen != null -> runtime.widgets.openScreen = null
            state.pulled != null -> state.closePull()
            else -> state.close()
        }
    }

    if (!runtime.themes.liveWallpaperActive) {
        runtime.themes.wallpaper?.let { bmp ->
            // Read while drawing, and in its own layer, so swiping pages only redraws this.
            Canvas(Modifier.fillMaxSize().graphicsLayer()) {
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
    // A face runs under them, so their bands make way for it.
    if (!state.faceShowing) SystemBarFrames()
    val covered = remember(state) { { state.homeCovered || runtime.widgets.openScreen != null } }
    // What frosted nodes blur: the background drawn above, or by our live wallpaper, slid as far as the pages.
    val backdrop = remember(runtime, state) { Backdrop({ runtime.themes.background }, { state.wallpaperOffset }) }
    CompositionLocalProvider(LocalHomeCovered provides covered, LocalBackdrop provides backdrop) {
        // The whole window: a face fills it, and tiles keep clear of the system bars and the bar themselves.
        HomeScreen(runtime, state, Modifier.fillMaxSize())
    }
    HomeMenu(runtime, state)

    // Only whether it shows is read here; the animation itself runs in the layer, without recomposing.
    val panelShown by remember(state) { derivedStateOf { state.drawer.value > 0.01f } }
    // Closing it forgets the search, like stock drawers: the next swipe up shows every app, not the old results.
    LaunchedEffect(panelShown) { if (!panelShown) state.query = "" }

    fun launch(hit: AppHit) {
        state.query = ""
        state.closeNow()
        hit.open(runtime)
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
                        is AppHit.Owl -> runtime.widgets.shortcuts.addOwl(hit.spec, state.currentPage)
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
                edit = { spec ->
                    state.closeNow()
                    state.withAgent(runtime, "Edit ${spec.name}") { editWidgetCommand(it, spec) }
                },
                // The same screen a widget is shared from: a link or QR code, or the store.
                share = { spec ->
                    state.closeNow()
                    state.sharing = spec
                },
                // Its file goes to .removed/, as a removed widget's does.
                removeOwl = { spec -> runtime.widgets.removeWidget(spec) },
            )
        }

    LaunchedEffect(handlers) { runtime.widgets.appHandlers = handlers }

    // The drawer is a panel: the one picked for swiping up, drawer.owl, or the stock one, whichever runs first.
    val drawer = runtime.widgets.drawerPanel()
    // Panels live while they show, so their sources keep up; a panel's close() slides it away.
    val shownPanels by remember(state, drawer) {
        derivedStateOf { setOfNotNull(drawer?.takeIf { state.drawer.value > 0f }, state.pulled?.takeIf { state.pull.value > 0f }) }
    }
    LaunchedEffect(shownPanels) { runtime.widgets.shownPanels = shownPanels }
    LaunchedEffect(state) {
        runtime.widgets.panelCloses.collect { name ->
            if (name == state.pulled) state.closePull() else if (name == runtime.widgets.drawerPanel()) state.close()
        }
    }

    if (panelShown) {
        // As much as the drawer's own opacity says.
        val opacity = drawer?.let { runtime.widgets.panelInstance(it)?.second?.panelOpacity() } ?: 0.97f
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = state.drawer.value.coerceAtMost(1f) }
                .background(Palette.background.copy(alpha = opacity))
                // A tap on it closes the drawer; keys move between the drawer's apps, not to it.
                .focusProperties { canFocus = false }
                .clickable { state.close() }
                .drawerSwipe(state),
        )
    }
    // The bar and the panel above it; the keyboard lifts both. Nothing here takes touches while the panel is closed,
    // except the bar. A phone's landscape folds them into a rail beside the grid, and unfolds them over it, centered.
    val side = homeSide()
    if (side != null && !panelShown) {
        if (!runtime.terminals.typing) {
            SideRail(runtime, state, handlers, side, Modifier.fillMaxSize().graphicsLayer { translationX = state.barShift() * size.width }.safePadding())
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
                val slide =
                    Modifier.graphicsLayer {
                        val progress = state.drawer.value
                        alpha = progress.coerceAtMost(1f)
                        translationY = (1f - progress) * size.height * 0.6f
                    }
                // Long-pressing an app opens its menu: the keyboard goes then, once, and stays down.
                val release = remember(state) { { state.promptRequest = PromptRequest.Release } }
                if (panelShown && drawer != null) {
                    // An arrow with nothing focused, as after a tap in it, starts in the drawer.
                    CompositionLocalProvider(LocalMenuOpened provides release) { UpPanel(runtime, state, drawer, slide.holdsKeys(enters = false, traps = false)) }
                }
            }
            // Hidden while a tile types: it would sit on the keyboard, over the tile. They slide with the tile pages,
            // out past a face; swiping up there opens the drawer, which brings them back.
            if (!runtime.terminals.typing || runtime.terminals.keyboardLeaving) {
                Column(Modifier.graphicsLayer { if (!panelShown) translationX = state.barShift() * size.width }) {
                    // Home only: the open app list has everything.
                    // Swiping up from either does what it does on home, as from the dock on stock launchers.
                    // Keys go between it and home's tiles both ways, which Compose's search doesn't find (see PromptBar).
                    if (LauncherStyle.recentApps && !panelShown) {
                        RecentStrip(runtime, handlers, Modifier.homeSwipe(runtime, state).focusRequester(state.stripEntry).focusGroup(), home = state.homeEntry)
                    }
                    // Insets itself by the grid gap, so it lines up with the tiles and the gap still takes taps.
                    PromptBar(runtime, state, modifier = Modifier.homeSwipe(runtime, state))
                }
            }
        }
    }

    // Over home and the bar, as it comes down from the top.
    PulledPanel(runtime, state)
    // Full-screen panels draw over the bar.
    ThemesOverlay(runtime, state)
    FileErrors(listOf(StyleStore.FILE to runtime.style.errors, HomeStore.FILE to runtime.widgets.home.errors) + runtime.gestureProblems())
}

/**
 * What style.toml or layout.toml got wrong, at the top over everything, like Hyprland's bar for config errors:
 * errors.txt is out of sight without a terminal. A tap hides the problems shown; new ones show again.
 */
@Composable
private fun FileErrors(files: List<Pair<String, List<String>>>) {
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    val shown = files.map { (name, errors) -> name to errors.filterNot { "$name: $it" in hidden } }.filter { it.second.isNotEmpty() }
    if (shown.isEmpty()) return
    Box(Modifier.fillMaxSize().safePadding().padding(PanelGap), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = ReadingWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PanelGap)) {
            for ((name, errors) in shown) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .frame(2.dp, Palette.red, Palette.background)
                        .clickable { hidden = hidden + errors.map { "$name: $it" } }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    BasicText(pluralStringResource(R.plurals.home_file_problems, errors.size, name, errors.size), style = Mono.copy(color = Palette.red))
                    for (line in errors.take(MAX_ERRORS)) BasicText(line, style = Mono.copy(fontSize = TextSize.Small))
                    val more = errors.size - MAX_ERRORS
                    BasicText(
                        if (more > 0) pluralStringResource(R.plurals.home_file_problems_more, more, more) else stringResource(R.string.home_file_problems_hide),
                        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                    )
                }
            }
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
            onReview = { path -> state.withAgent(runtime, "Review widget") { reviewSharedWidgetCommand(it, path) } },
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
                        state.withAgent(runtime, "Publish ${spec.name}") { publishWidgetCommand(it, spec) }
                    }
                },
            // Closes the screen: what the agent changes would be shared from a fresh one.
            onCheck = {
                state.sharing = null
                state.withAgent(runtime, "Check ${spec.name}") { checkSecretsCommand(it, spec) }
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
