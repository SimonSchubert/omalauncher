package dev.omalauncher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import android.app.WallpaperManager
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import dev.omalauncher.widgets.FACE_PAGE
import dev.omalauncher.widgets.isFacePage
import dev.omalauncher.widgets.lang.OwlKind
import dev.omalauncher.widgets.Folder
import dev.omalauncher.widgets.HomeItem
import dev.omalauncher.widgets.Shortcut
import dev.omalauncher.widgets.WidgetItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemBars
import dev.omalauncher.theme.Style
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import dev.omalauncher.widgets.render.LocalHomeTiles
import dev.omalauncher.widgets.editWidgetCommand
import dev.omalauncher.widgets.fixDependenciesCommand
import dev.omalauncher.widgets.newOwlCommand

/** Home pages of JSON widgets on a snap grid, bordered and gapped like Omarchy windows. */
@Composable
fun HomeScreen(
    runtime: Runtime,
    state: LauncherState,
    modifier: Modifier = Modifier,
) {
    val engine = runtime.widgets
    val linuxState by runtime.bootstrap.state.collectAsState()
    val arrange = remember { Arrange() }
    // One empty page always waits after the last one, to drop tiles on or pin apps to. Pages only exist
    // while they hold something, so it becomes a real page once used and a new empty one follows.
    // Left of page 1 the same, for faces: like a stock launcher's feed, but the user's own. An empty one waits left of
    // the leftmost, page 0 at first, then -1, -2, ... as they fill.
    // Unfolded, tile pages show in pairs and a face across both halves: the pager counts slots (see slotsOf).
    val per = if (showsTwoPages(windowSize())) 2 else 1
    val pageNumbers =
        engine.pages
            .let { listOf(minOf(it.first(), FACE_PAGE + 1) - 1) + it }
            .let { if (engine.itemsOn(it.last()).isEmpty()) it else it + (it.last() + 1) }
    // Only faces on screen, or the empty one waiting left of them: they get it whole, the bar's room too (see
    // LauncherState.faceShowing), and the status bar's while all of them say statusBar: false.
    fun faceOn(page: Int?) = page?.let { engine.layout.owner(engine.itemsOn(it)) as? WidgetItem }?.takeIf { it.isFace }
    fun full(page: Int?) = page != null && (isFacePage(page) || faceOn(page) != null)
    val slots = slotsOf(pageNumbers, per, ::full)
    fun slotOf(page: Int) = slots.indexOfFirst { page in it }
    // Home opens on page 1, not left of it; on the first tile page, if a layout from before faces has none numbered 1.
    val pager = rememberPagerState(initialPage = slotOf(pageNumbers.firstOrNull { it >= 1 } ?: 1).coerceAtLeast(0)) { slots.size }
    // A tile dropped on another page: show that page once the page list has caught up, by the list as it is then. The
    // list the request was made with could be one from before (a store add spilling onto page 2 showed a face). A swipe
    // meanwhile wins, and the request goes with it rather than firing later.
    var showPage by remember { mutableStateOf<Int?>(null) }
    val currentSlots by rememberUpdatedState(slots)
    LaunchedEffect(showPage) {
        val page = showPage ?: return@LaunchedEffect
        try {
            withTimeoutOrNull(2_000) { snapshotFlow { currentSlots.indexOfFirst { page in it } }.first { it >= 0 } }?.let { pager.scrollToPage(it) }
        } finally {
            // Not when another page was asked for meanwhile: that one runs this again.
            if (showPage == page) showPage = null
        }
    }
    var menuFor by remember { mutableStateOf<HomeItem?>(null) }

    val terminals = runtime.terminals
    BackHandler(enabled = terminals.typing) { terminals.deactivate() }
    BackHandler(enabled = state.homeMenuOpen) { state.homeMenuOpen = false }
    BackHandler(enabled = state.editing != null && !state.homeMenuOpen) { state.editing = null }
    BackHandler(enabled = arrange.folder != null && !state.homeMenuOpen) { arrange.folder = null }
    LaunchedEffect(engine) {
        engine.launcherAction = { action ->
            when (action) {
                // "themes" from before the screen was style; widgets still send it.
                "style", "themes" -> state.show(SettingsPage.Style)
                "agents" -> state.show(SettingsPage.Agents)
                "store" -> state.show(SettingsPage.Store)
                "linux" -> state.show(SettingsPage.Linux)
                "drawer" -> state.open()
                "settings" -> state.show()
            }
        }
    }
    // Folding or unfolding keeps the page that was showing: unfolded, the pair it's in; folded, the left one of the pair.
    var shownPer by remember { mutableIntStateOf(per) }
    LaunchedEffect(per) {
        if (per == shownPer) return@LaunchedEffect
        val i = slotOf(engine.activePage)
        shownPer = per
        if (i >= 0) pager.scrollToPage(i)
    }
    // The drawer pins apps to whichever page is showing (the left one of a pair); from a face, wherever it is, to page 1.
    // Pages appearing before the one showing don't move home off it, unless home was just sent to another page. At
    // start, pinned apps load before widgets, so page 1 may join after the faces and later pages: until widgets have
    // loaded, home keeps to page 1 rather than whatever stands at its index.
    val listed = remember { arrayOf(slots) }
    LaunchedEffect(pager.currentPage, slots, engine.loaded) {
        if (!engine.loaded) return@LaunchedEffect
        if (slots != listed[0]) {
            listed[0] = slots
            val i = slotOf(engine.activePage)
            if (showPage == null && i >= 0 && i != pager.currentPage) {
                pager.scrollToPage(i)
                return@LaunchedEffect
            }
        }
        engine.activePage = slots.getOrNull(pager.currentPage)?.first() ?: 1
        state.currentPage = engine.activePage.takeIf { it >= 1 && !full(it) } ?: 1
    }
    val shown = slots.getOrElse(pager.currentPage) { listOf(1) }
    // A face showing has the keyboard, for one of its fields or terminals: it isn't lifted, it makes room itself.
    val faceTyping = shown.mapNotNull(::faceOn).any { f -> engine.instance(f.spec)?.let { it.owns(runtime.terminals.active) || runtime.terminals.field?.widget === it } == true }
    val faceShowing = shown.all(::full)
    val statusBarAway = shown.all { faceOn(it)?.let { f -> engine.instance(f.spec) }?.showsStatusBar() == false }
    // Which pager slots are full screen, for the bar to slide with the others.
    val fullSlots = slots.map { it.all(::full) }
    SideEffect {
        state.faceShowing = faceShowing
        state.statusBarAway = statusBarAway
        state.barShift = { barShift(fullSlots, pager.currentPage + pager.currentPageOffsetFraction) }
    }
    // The background slides with the pages, like stock launchers. Our live wallpaper gets it through Android.
    val view = LocalView.current
    DisposableEffect(engine, view) {
        engine.view = view
        onDispose { if (engine.view === view) engine.view = null }
    }
    LaunchedEffect(pager, view) {
        val wallpapers = WallpaperManager.getInstance(view.context)
        snapshotFlow {
            val last = pager.pageCount - 1
            val offset = if (last <= 0) 0.5f else ((pager.currentPage + pager.currentPageOffsetFraction) / last).coerceIn(0f, 1f)
            // Also resent when our live wallpaper is picked, which starts out centered.
            offset to runtime.themes.liveWallpaperActive
        }.collect { (offset, live) ->
            state.wallpaperOffset = offset
            if (live) {
                view.windowToken?.let {
                    wallpapers.setWallpaperOffsetSteps(if (pager.pageCount > 1) 1f / (pager.pageCount - 1) else 1f, 1f)
                    wallpapers.setWallpaperOffsets(it, offset, 0.5f)
                }
            }
        }
    }
    // A terminal tile keeps the keyboard only while its page shows.
    LaunchedEffect(pager.currentPage) { terminals.deactivate() }
    // Its shell exited, or the app lost focus: the soft keyboard goes too.
    val typing = terminals.active != null
    LaunchedEffect(typing) { if (!typing) terminals.keyboard?.hide() }
    LaunchedEffect(state.requestedPage) {
        state.requestedPage?.let { slotOf(it).takeIf { i -> i >= 0 }?.let { i -> pager.animateScrollToPage(i) } }
        state.requestedPage = null
    }

    fun openMenu(item: HomeItem?) {
        menuFor = item
        state.homeMenuOpen = true
    }

    fun remove(item: HomeItem) {
        state.editing = null
        when (item) {
            is WidgetItem -> engine.removeWidget(item.spec)
            is Shortcut, is Folder -> engine.shortcuts.remove(item.key)
        }
        engine.layout.remove(item.key)
    }

    /** Opens the default agent on the widget's file; with no agent installed, the agents screen instead. */
    fun edit(item: WidgetItem) {
        state.editing = null
        state.withAgent(runtime, "Edit ${item.spec.name}") { editWidgetCommand(it, item.spec) }
    }

    // Overlays below are siblings of the pager inside this Box, so they never change the page's size.
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    // Where the keyboard ends up, so a terminal tile resizes once rather than with every frame of its animation.
    @OptIn(ExperimentalLayoutApi::class)
    val imeTarget = WindowInsets.imeAnimationTarget
    val statusBar = WindowInsets.statusBars.union(WindowInsets.displayCutout)
    // Back with the soft keyboard up only reaches the keyboard app, which closes itself. Closed like that
    // (not by the tile's ⌨ button), the tile lets go too, so the bar and recent apps come back. Only once it's gone:
    // hiding it again mid-way, while a back swipe still drives its animation, left home taking no touches.
    // A widget's text field the same: its keyboard closed without it losing focus.
    LaunchedEffect(terminals) {
        snapshotFlow { ime.getBottom(density) > 0 }.collect { shown ->
            if (!shown && (terminals.keyboard?.wanted == true || terminals.field != null)) terminals.deactivate()
        }
    }
    // The bar and recent apps don't wait for that: back as the keyboard starts closing, like the pages.
    LaunchedEffect(terminals) {
        snapshotFlow { imeTarget.getBottom(density) == 0 && (terminals.keyboard?.wanted == true || terminals.field != null) }
            .collect { terminals.keyboardLeaving = it }
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val extraKeys = terminals.active != null && extraKeysWanted()
    val side = homeSide()
    val turned = homeTurned()
    val sideRoom = sideWidth()
    val barRoom = with(density) { state.barHeight.takeIf { it > 0 }?.toDp() ?: (PromptBarHeight + HomeGap + EdgeGap) }
    LaunchedEffect(extraKeys) { if (!extraKeys) terminals.extraKeys = null }
    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInWindow() }
            // Sees every press first, without taking it: one outside the active terminal tile ends its turn.
            .pointerInput(terminals) {
                awaitEachGesture { terminals.pressAt(origin + awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position) }
            },
    ) {
        // Owns the soft keyboard for terminal tiles.
        AndroidView(
            factory = { KeyboardSink(it, runtime.keyboard).also { sink -> terminals.keyboard = sink } },
            onRelease = { if (terminals.keyboard === it) terminals.keyboard = null },
            modifier = Modifier.size(1.dp),
        )
    // A widget's text field on a tile lifts it like a terminal does.
    CompositionLocalProvider(LocalHomeTiles provides terminals) {
    Column(
        Modifier
            .fillMaxSize()
            // Swiping up opens the drawer, like stock launchers, or what gesture settings say; not while editing, in a
            // folder or typing in a tile. A long-press drag takes its touches first, and the pager only wants sideways ones.
            .homeSwipe(runtime, state, enabled = !terminals.typing && state.editing == null && arrange.folder == null)
            // Typing in a tile (its terminal or a widget's text field): lift the pages so the tile stays above the keyboard,
            // but not under the status bar; a tile too tall for both gives up the rest at its bottom.
            .graphicsLayer {
                val keys = if (extraKeys) terminals.extraKeysHeight.takeIf { it > 0f } ?: ExtraKeysHeight.toPx() else 0f
                translationY =
                    -terminals.liftFor(
                        imeHeight = ime.getBottom(density),
                        imeTarget = imeTarget.getBottom(density),
                        windowHeight = view.height,
                        margin = 8.dp.toPx() + keys,
                        top = statusBar.getTop(density) + EdgeGap.toPx(),
                        minHeight = 64.dp.toPx(),
                    )
            }
            // Nothing below where the typing tile ends, above the extra keys, so the gaps around them show the wallpaper, and
            // nothing lifted under the status bar. A face typing is the whole screen: it isn't lifted and keeps drawing
            // under the status bar and down to the keyboard.
            .drawWithContent {
                val keyboard = ime.getBottom(density)
                if (!terminals.typing || keyboard == 0 || faceTyping) {
                    drawContent()
                } else {
                    val keys = if (extraKeys) terminals.extraKeysHeight.takeIf { it > 0f } ?: ExtraKeysHeight.toPx() else 0f
                    val lift = terminals.lift - origin.y
                    clipRect(
                        top = statusBar.getTop(density) + lift,
                        bottom = view.height - keyboard - keys - 8.dp.toPx() + lift,
                    ) { this@drawWithContent.drawContent() }
                }
            }
    ) {
        // The pages fill the window, so a face can; tiles keep clear of the system bars (as if shown, so they
        // don't move when a face hides the status bar; with style hiding it they reach the top, as always) and of the
        // bar, which DesktopScreen draws over the pages (so the keyboard lifts it without shrinking the grid). Below the
        // grid the strip, a gap, the bar and the gap to the edge: the bar as measured, since a large font makes it
        // taller. In a phone's landscape they stand beside it instead, on the side portrait's bottom turned to.
        @OptIn(ExperimentalLayoutApi::class)
        val bars = (if (LauncherStyle.style[Style.STATUS_BAR_SHOWN]) WindowInsets.systemBarsIgnoringVisibility else WindowInsets.systemBars).union(WindowInsets.displayCutout)
        val direction = LocalLayoutDirection.current
        val keepClear =
            with(density) {
                GridPad(
                    left = bars.getLeft(density, direction) + if (side == HomeSide.Left) sideRoom.roundToPx() else 0,
                    top = bars.getTop(density),
                    right = bars.getRight(density, direction) + if (side == HomeSide.Right) sideRoom.roundToPx() else 0,
                    bottom = bars.getBottom(density) + if (side != null) 0 else barRoom.roundToPx(),
                )
            }
        HomeGrid(
            pager = pager,
            keepClear = keepClear,
            slots = slots,
            full = ::full,
            itemsOn = engine::itemsOn,
            layout = engine.layout,
            arrange = arrange,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            selected = state.editing,
            onSelect = { state.editing = it },
            actions = { item ->
                // A pinned Android app offers its shortcuts above the bar, as its long-press menu in the drawer does.
                val app = (item as? Shortcut)?.component?.let { runtime.androidApps.pinned(it, item.profile) }
                val (menu, offered) =
                    offeredShortcuts(item.key) {
                        app?.let { shortcutMenu(runtime, it, page = { item.page }, onOpen = { arrange.folder = null }) }
                    }
                // Nothing until they're loaded, so the bar doesn't jump down as they arrive.
                if (offered != null) Column {
                    if (menu != null && offered.isNotEmpty()) {
                        MenuItems(emptyList(), { state.editing = null }, minWidth = 160.dp, itemPadding = 12.dp, fontSize = TextSize.Item) {
                            ShortcutRows(offered, menu, { state.editing = null }, TextSize.Item, rule = false)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    EditBar {
                        if (item is WidgetItem) EditAction("edit") { edit(item) }
                        // As a QR code or link, or (your own) to the store. An agent's tile is a terminal, not a widget to pass on.
                        if (item is WidgetItem && !engine.isAgentWidget(item.spec)) {
                            EditAction("share") {
                                state.editing = null
                                state.sharing = item.spec
                            }
                        }
                        EditAction("remove", Palette.red) { remove(item) }
                    }
                }
            },
            render = { item, tile, highlighted ->
                when (item) {
                    is WidgetItem -> {
                        // Whose terminal has the keyboard: the tile gives up what the keyboard covers once the lift is spent.
                        val instance = engine.instance(item.spec)
                        val typing = instance != null && (instance.owns(terminals.active) || terminals.field?.widget === instance)
                        WidgetTile(
                            spec = item.spec,
                            instance = engine.instance(item.spec),
                            // As shown: on its side in landscape. A face is the whole grid.
                            cells = (if (item.isFace) engine.layout.grid else engine.layout.cell(item.key)?.let { it.w to it.h })?.let { (w, h) -> if (turned) h to w else w to h },
                            // Accent like Hyprland's active border: only while one of its terminals has the keyboard. A face
                            // has none, being the whole screen; its screenInset grows by what the keyboard covers instead.
                            focused = highlighted || (typing && !item.isFace),
                            keyboard = if (typing && item.isFace) terminals.cut else 0f,
                            modifier =
                                if (typing && item.isFace) {
                                    tile.onGloballyPositioned { terminals.tile = it.boundsInWindow().translate(0f, terminals.lift) }
                                } else if (typing) {
                                    tile
                                        .onGloballyPositioned { terminals.tile = it.boundsInWindow().translate(0f, terminals.lift) }
                                        .padding(bottom = with(density) { terminals.cut.toDp() })
                                } else {
                                    tile
                                },
                            onTap = { engine.tap(item.spec) },
                            needs = engine.missing[item.spec.file.name].orEmpty(),
                            install = engine.installs[item.spec.file.name],
                            onInstall = { engine.installDependencies(item.spec) },
                            waitsForLinux = if (engine.waitsForLinux(item.spec)) linuxState else null,
                            onInstallLinux = { state.show(SettingsPage.Linux) },
                            onFix = { log -> state.withAgent(runtime, "Fix ${item.spec.name}") { fixDependenciesCommand(it, item.spec, log) } },
                        )
                    }
                    is Shortcut ->
                        ShortcutTile(
                            shortcut = item,
                            label = runtime.shortcutLabel(item),
                            icon = runtime.shortcutIcon(item, LauncherStyle.monochromeIcons),
                            focused = highlighted,
                            modifier = tile,
                            onTap = {
                                // Opening an app from a folder closes the folder, like Android.
                                arrange.folder = null
                                runtime.open(item)
                            },
                            paused = runtime.androidApps.paused(item.profile),
                            clone = runtime.androidApps.cloned(item.profile),
                        )
                    is Folder ->
                        FolderTile(
                            folder = item,
                            icons = item.apps.take(4).map { runtime.shortcutIcon(it, LauncherStyle.monochromeIcons) },
                            labels = item.apps.take(4).map { runtime.shortcutLabel(it) },
                            focused = highlighted,
                            modifier = tile,
                            onTap = { arrange.folder = item.key },
                        )
                }
            },
            // The recent apps strip's room: kept while it's empty, so tiles don't reflow when it fills, and still
            // home's to swipe and long-press there until it does.
            room = if (LauncherStyle.recentApps && side == null) RecentStripHeight else 0.dp,
            turned = turned,
            side = side,
            per = per,
            onLongPressEmpty = { openMenu(null) },
            feed = { page ->
                FaceInvite(runtime.agents.default?.name, onBrowse = { state.show(SettingsPage.FaceStore) }) {
                    if (linuxState == LinuxState.Installed) {
                        state.withAgent(runtime, "New face") { newOwlCommand(it, OwlKind.Face, page) }
                    } else {
                        state.show(SettingsPage.Linux)
                    }
                }
            },
            onNoRoom = { item -> showPage = engine.moveToFreePage(item) },
            onMove = { item, page ->
                engine.moveToPage(item, page)
                showPage = page
            },
            onGroup = { app, target -> engine.shortcuts.group(app, target) },
            onReorder = { app, before -> engine.shortcuts.moveBefore(app.key, before) },
            onRenameFolder = { folder, name -> engine.shortcuts.rename(folder.key, name) },
        )
    }
    }

    if (extraKeys) {
        ExtraKeys(
            runtime,
            Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .onGloballyPositioned {
                    terminals.extraKeys = it.boundsInWindow()
                    terminals.extraKeysHeight = it.size.height.toFloat()
                },
        )
    }

    }
}


/**
 * The page left of the first, while it's empty: where a stock launcher has its news feed, an invitation to have
 * [agent] build a face of the user's own for it.
 */
@Composable
private fun FaceInvite(
    agent: String?,
    onBrowse: () -> Unit,
    onCreate: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .frame(1.dp, Palette.border, Palette.background.copy(alpha = 0.85f))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText("a face of your own", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
            // The theme's own text color: muted is too faint on some themes' card.
            BasicText(
                "This page is yours to fill with a face: a feed, a dashboard, a start page, a game. Say what you want and " +
                    "${agent ?: "an agent"} builds it here, full screen.",
                style = Mono,
            )
            // Pulled left by the buttons' own padding, so the first bracket lines up with the text.
            Column(Modifier.offset(x = (-8).dp)) {
                BracketAction("create with ${agent?.lowercase() ?: "an agent"}", Palette.accent, onCreate)
                BracketAction("browse store", Palette.accent, onBrowse)
            }
        }
    }
}

/**
 * The pager's slots, what each of its positions shows left to right: a page each with [per] 1. Unfolded ([per] 2), tile
 * pages in pairs, so every tile keeps its folded size, and a [full] page (a face's, or the empty one waiting for one)
 * alone across both halves, as a face is the whole screen. A tile page with a full one after it stands alone, its other
 * half empty; the last one gets the number after it as an empty partner, to drop tiles on.
 */
internal fun slotsOf(
    pages: List<Int>,
    per: Int,
    full: (Int) -> Boolean,
): List<List<Int>> {
    if (per == 1) return pages.map { listOf(it) }
    val slots = ArrayList<List<Int>>()
    var i = 0
    while (i < pages.size) {
        val page = pages[i]
        val next = pages.getOrNull(i + 1)
        slots +=
            when {
                full(page) -> listOf(page)
                next == null -> listOf(page, page + 1)
                full(next) -> listOf(page)
                else -> listOf(page, next).also { i++ }
            }
        i++
    }
    return slots
}

/**
 * Where the strip and the bar stand across at pager [position] (slots, fractional while swiping), in screen widths: 0
 * over a tile page; past a [full] slot (a face's, or the empty one waiting left of page 1) they slide out with the tile page beside it,
 * as its tiles do, and they're a whole width away between two full ones.
 */
internal fun barShift(
    full: List<Boolean>,
    position: Float,
): Float {
    val left = kotlin.math.floor(position).toInt()
    val f = position - left
    val leftFull = full.getOrElse(left) { false }
    val rightFull = full.getOrElse(left + 1) { leftFull }
    return when {
        !leftFull && !rightFull -> 0f
        // Going right from a tile page: the bar leaves to the left with it.
        !leftFull -> -f
        // Coming from a face on the left: the bar comes in from the right with the tile page.
        !rightFull -> 1f - f
        else -> 1f
    }
}
