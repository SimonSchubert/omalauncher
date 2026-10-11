package dev.omalauncher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.layout.width
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.ColorFilter
import dev.omalauncher.AndroidApp
import androidx.compose.runtime.collectAsState
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState
import androidx.compose.ui.graphics.vector.ImageVector
import dev.omalauncher.linux.FILES_COMMAND
import dev.omalauncher.linux.LinuxApp

/** One app in the bar's results. */
internal sealed interface AppHit {
    /** Stable id, also the [dev.omalauncher.AppUsage] key. */
    val key: String
    val label: String

    data class Linux(
        val app: LinuxApp,
    ) : AppHit {
        override val key get() = "l:" + app.id
        override val label get() = app.name
    }

    data class Android(
        val app: AndroidApp,
    ) : AppHit {
        override val key get() = "a:" + app.component.flattenToShortString()
        override val label get() = app.label
    }
}

/** What the bar's query matched: apps best first, and whether Enter opens the first rather than asking the agent. */
internal class BarSearch(
    val hits: List<AppHit>,
    val opensApp: Boolean,
) {
    /** The result Enter acts on: an index into [hits], or -1 for the agent. */
    fun highlighted(picked: Int?) = picked?.coerceIn(-1, hits.lastIndex) ?: if (opensApp) 0 else -1
}

private fun allApps(runtime: Runtime): List<AppHit> = runtime.linuxApps.map { AppHit.Linux(it) } + runtime.androidApps.apps.map { AppHit.Android(it) }

/** [AppHit.key]s of the apps pinned to home, on their own or in a folder. */
private fun pinnedKeys(runtime: Runtime): Set<String> =
    runtime.widgets.shortcuts.items.mapNotNullTo(HashSet()) { s ->
        s.component?.let { "a:" + it.flattenToShortString() } ?: s.linux?.let { AppHit.Linux(it).key }
    }

internal fun searchApps(
    runtime: Runtime,
    query: String,
): BarSearch {
    if (query.isBlank()) return BarSearch(emptyList(), false)
    val now = System.currentTimeMillis()
    val ranked = rankMatches(allApps(runtime), query, { it.label }, boost = { (runtime.usage.score(it.key, now) * 10).toInt() })
    return BarSearch(ranked.map { it.first }, ranked.firstOrNull()?.let { isStrongMatch(it.second, query) } == true)
}

/** What the app cells do: open, pin to home, and the Android app's info and uninstall. */
internal class AppHandlers(
    val launch: (AppHit) -> Unit,
    val pin: (AppHit) -> Unit,
    val info: (AndroidApp) -> Unit,
    val uninstall: (AndroidApp) -> Unit,
)

/** The long-press menu of an app cell: pin to home, and for Android apps their info and uninstall. */
private fun AppHit.actions(handlers: AppHandlers): List<Pair<String, () -> Unit>> =
    when (this) {
        is AppHit.Linux -> listOf("add to home" to { handlers.pin(this) })
        is AppHit.Android ->
            listOfNotNull(
                "add to home" to { handlers.pin(this) },
                "app info" to { handlers.info(app) },
                if (app.system) null else "uninstall" to { handlers.uninstall(app) },
            )
    }

/** The app's icon and its silhouette (each null until loaded or if it has none), and a glyph for built-in apps (Files). */
private fun AppHit.icons(runtime: Runtime): Triple<ImageBitmap?, ImageBitmap?, ImageVector?> =
    when (this) {
        is AppHit.Linux -> Triple(runtime.linuxIcons[app.id], runtime.linuxMasks[app.id], if (app.exec == FILES_COMMAND) Icons.Folder else null)
        is AppHit.Android -> Triple(runtime.androidApps.icons[app.component], runtime.androidApps.masks[app.component], null)
    }

@Composable
private fun HitCell(
    runtime: Runtime,
    hit: AppHit,
    handlers: AppHandlers,
    selected: Boolean = false,
) {
    val (icon, mask, glyph) = hit.icons(runtime)
    AppCell(hit.label, icon, mask, onClick = { handlers.launch(hit) }, actions = { hit.actions(handlers) }, glyph = glyph, selected = selected)
}

/** Height of the recent apps strip; home keeps this much room for it above the bar, even while it's empty. */
val RecentStripHeight = 56.dp

/**
 * The apps opened lately, on home right above the bar, scrolling sideways; [vertical] stands it up
 * beside the grid in landscape, scrolling down. A home tile like the others: frame, fill and gaps,
 * or bare when home icons are. Can be hidden in themes.
 */
@Composable
internal fun RecentStrip(
    runtime: Runtime,
    handlers: AppHandlers,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val apps = recentApps(runtime, 20)
    // Nothing opened yet: no strip, but its room stays, so home doesn't shift when the first app shows up.
    if (apps.isEmpty()) {
        Spacer(if (vertical) modifier.width(RecentStripHeight) else modifier.height(RecentStripHeight))
        return
    }
    val bare = LauncherStyle.bareAppIcons
    val listState = rememberLazyListState()
    // Back on home, the strip starts at the app opened last, wherever it was scrolled to before.
    LaunchedEffect(runtime.usage.settled) { listState.scrollToItem(0) }
    Box(
        modifier
            .then(if (vertical) Modifier.fillMaxHeight().width(RecentStripHeight) else Modifier.padding(horizontal = HomeGap).fillMaxWidth().height(RecentStripHeight))
            .then(if (bare) Modifier else Modifier.tile()),
        contentAlignment = if (vertical) Alignment.TopCenter else Alignment.CenterStart,
    ) {
        val cells: LazyListScope.() -> Unit = {
            items(apps, key = { it.key }) { hit ->
                val (icon, mask, glyph) = hit.icons(runtime)
                StripCell(hit.label, icon, mask, onClick = { handlers.launch(hit) }, actions = { hit.actions(handlers) }, glyph = glyph)
            }
        }
        if (vertical) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, content = cells)
        } else {
            LazyRow(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, content = cells)
        }
    }
}

/** An app in the recent strip, drawn like a pinned app's tile: the icon alone, or on a badge when bare. */
@Composable
private fun StripCell(
    label: String,
    icon: ImageBitmap?,
    mask: ImageBitmap?,
    onClick: () -> Unit,
    /** Built when the menu opens: a fresh list of lambdas would keep the cell from skipping recomposition. */
    actions: () -> List<Pair<String, () -> Unit>>,
    glyph: ImageVector? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val mono = LauncherStyle.monochromeIcons
    val bare = LauncherStyle.bareAppIcons
    val size = 40.dp
    Box(
        Modifier
            .size(48.dp)
            .clip(LauncherStyle.shape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = if (mono) mask else icon
        when {
            glyph != null -> Image(glyph, label, Modifier.size(size * 0.7f), colorFilter = ColorFilter.tint(Palette.accent))
            // Bare silhouettes and initials sit on a badge, as pinned apps do, so they read on any wallpaper.
            bare && (mono || bmp == null) ->
                Box(Modifier.size(size).clip(LauncherStyle.shape(size / 2)).background(Palette.surface), contentAlignment = Alignment.Center) {
                    if (bmp != null) {
                        Image(bmp, label, Modifier.size(size * 0.75f), colorFilter = ColorFilter.tint(Palette.accent))
                    } else {
                        BasicText(initials(label), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Body))
                    }
                }
            bmp != null -> Image(bmp, label, Modifier.size(size * if (mono) 0.85f else 1f), colorFilter = if (mono) ColorFilter.tint(Palette.accent) else null)
            else -> BasicText(initials(label), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Large))
        }
        if (menuOpen) AppActions(actions(), onDismiss = { menuOpen = false })
    }
}

/** The apps opened lately, last opened first; ones not installed (or not loaded yet) are skipped. */
private fun recentApps(
    runtime: Runtime,
    limit: Int,
): List<AppHit> {
    val byKey = allApps(runtime).associateBy { it.key }
    return runtime.usage.recent.mapNotNull { byKey[it] }.take(limit)
}

/**
 * The bar's results, growing up from it. Empty query: Linux and Android apps to browse.
 * Typing: matches best first, and a row that asks the agent.
 */
@Composable
internal fun AppPanel(
    runtime: Runtime,
    state: LauncherState,
    search: BarSearch,
    highlighted: Int,
    handlers: AppHandlers,
    onAsk: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Cheap when nothing changed: the list is only replaced if the .desktop entries differ.
    LaunchedEffect(Unit) { runtime.refreshApps() }
    val linuxState by runtime.bootstrap.state.collectAsState()
    val query = state.query
    val listState = rememberLazyListState()
    // New results start at the bar, where the best match is.
    LaunchedEffect(query.isBlank(), search) { listState.scrollToItem(0) }
    // Set to list only what home doesn't have; search still finds everything.
    val hidden = if (LauncherStyle.homeAppsInDrawer) emptySet() else pinnedKeys(runtime)

    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    // Pulling down at the end of the list drags the panel closed, like stock drawers: only a pull that starts there
    // (decided as the finger lands, as Launcher3 does). Scrolling up to the end just stops there.
    val pullToClose =
        remember(state, screenHeight) {
            object : NestedScrollConnection {
                /** The list was already at its end when this gesture started. */
                var fromEnd = false

                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Half-closed and scrolling back up: reopen before scrolling the list.
                    if (source != NestedScrollSource.UserInput || available.y >= 0f || state.drawer.value >= 1f) return Offset.Zero
                    state.dragBy(-available.y / screenHeight)
                    return Offset(0f, available.y)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source != NestedScrollSource.UserInput || available.y <= 0f || !fromEnd) return Offset.Zero
                    state.dragBy(-available.y / screenHeight)
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (state.drawer.value >= 1f) return Velocity.Zero
                    state.settle(available.y)
                    return available
                }
            }
        }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // Full width: a wide screen fits more icons in each row rather than leaving the sides empty.
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 12.dp, end = 12.dp), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(LauncherStyle.shape(22.dp))
                        .background(Palette.surface)
                        .clickable(onClick = onSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(Icons.Gear, "settings", Modifier.size(22.dp), colorFilter = ColorFilter.tint(Palette.accent))
                    // Updates are waiting: settings › system updates lists them.
                    if ((runtime.updates.pending?.count ?: 0) > 0) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(8.dp)
                                .background(Palette.red, CircleShape),
                        )
                    }
                }
            }
            // Only as tall as its rows, so taps above a short list reach the scrim behind and close the panel.
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Bottom) {
                BoxWithConstraints(Modifier.weight(1f, fill = false)) {
                    val columns = (maxWidth / 76.dp).toInt().coerceAtLeast(1)
                    // Rows bottom-up: the first item sits right above the bar.
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .pointerInput(listState) {
                                    // Bottom-up, so forward is up: the end is the top row.
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        pullToClose.fromEnd = !listState.canScrollForward
                                    }
                                }.nestedScroll(pullToClose),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 4.dp),
                    ) {
                        fun row(
                            key: String,
                            cells: List<AppHit>,
                            selected: Int = -1,
                        ) = item(key = key) {
                            Row(Modifier.fillMaxWidth()) {
                                cells.forEachIndexed { i, hit -> Box(Modifier.weight(1f)) { HitCell(runtime, hit, handlers, selected = i == selected) } }
                                repeat(columns - cells.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }

                        // Each section reads top-down above the one before it.
                        fun section(
                            title: String,
                            apps: List<AppHit>,
                        ) {
                            if (apps.isEmpty()) return
                            val rows = apps.chunked(columns)
                            for (r in rows.indices.reversed()) row("$title:$r", rows[r])
                            item(key = "h:$title") { SectionLabel(title, Modifier.padding(start = 8.dp)) }
                        }

                        if (query.isNotBlank()) {
                            search.hits.chunked(columns).forEachIndexed { r, cells -> row("q:$r", cells, highlighted - r * columns) }
                        } else {
                            if (linuxState == LinuxState.Installed) {
                                section("linux", runtime.linuxApps.map { AppHit.Linux(it) }.filter { it.key !in hidden })
                            } else {
                                // Linux apps would be listed here; without Linux, one quiet offer instead.
                                item(key = "linux-offer") {
                                    Row(Modifier.padding(top = 12.dp, bottom = 4.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        BasicText("linux apps", Modifier.weight(1f), style = Mono.copy(color = Palette.muted))
                                        BracketAction(if (linuxState is LinuxState.Installing) "installing…" else "install linux", Palette.accent) {
                                            state.closeNow()
                                            state.linuxOpen = true
                                        }
                                    }
                                }
                            }
                            section("android", runtime.androidApps.apps.map { AppHit.Android(it) }.filter { it.key !in hidden })
                        }
                    }
                }
                if (query.isNotBlank()) AskRow(runtime, linuxState == LinuxState.Installed, query, highlighted == -1, onAsk)
            }
        }
    }
}

/** The one line above the bar that sends what's typed to the agent. */
@Composable
private fun AskRow(
    runtime: Runtime,
    linux: Boolean,
    query: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val agent = runtime.agents.default
    val label =
        when {
            !linux -> "ask an agent · install linux"
            agent == null -> "ask an agent · install one"
            else -> "ask ${agent.bin}: " + query.trim().replace('\n', ' ')
        }
    Row(
        Modifier
            .padding(bottom = 6.dp)
            .fillMaxWidth()
            .then(if (selected) Modifier.frame(2.dp, Palette.accent, Palette.background) else Modifier.clip(LauncherStyle.shape()))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("⏎ ", style = Mono.copy(color = if (selected) Palette.accent else Palette.muted))
        BasicText(
            label,
            style = Mono.copy(color = if (selected) Palette.foreground else Palette.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SearchField(
    query: String,
    onChange: (String) -> Unit,
    onGo: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val style = Mono.copy(fontSize = TextSize.Item)
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(Palette.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier =
            modifier
                .fillMaxWidth()
                .clip(LauncherStyle.shape(24.dp))
                .background(Palette.surface)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (query.isEmpty()) BasicText(placeholder, style = style.copy(color = Palette.muted))
                inner()
            }
        },
    )
}

/**
 * One drawer entry: a 52dp icon over a label two lines tall. Linux apps without an icon get initials.
 * Long-press opens a popup of [actions] above the icon, like stock launchers.
 */
@Composable
private fun AppCell(
    label: String,
    icon: ImageBitmap?,
    /** The icon's silhouette, shown instead in the monochrome icon style. */
    mask: ImageBitmap?,
    onClick: () -> Unit,
    /** Built when the menu opens, as in [StripCell]. */
    actions: () -> List<Pair<String, () -> Unit>>,
    /** Built-in apps (Files) draw a theme-colored glyph instead of a bitmap. */
    glyph: ImageVector? = null,
    /** What Enter in the bar would open: outlined in the accent color. */
    selected: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(LauncherStyle.shape(16.dp))
            .then(if (selected) Modifier.frame(2.dp, Palette.accent) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            ).padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val mono = LauncherStyle.monochromeIcons
        // Badges are circles when rounded, squares with Omarchy's sharp edges.
        val badge = LauncherStyle.shape(26.dp)
        if (glyph != null) {
            Box(
                Modifier.size(52.dp).clip(badge).background(if (mono) Palette.surface else Palette.accent),
                contentAlignment = Alignment.Center,
            ) {
                Image(glyph, label, Modifier.size(26.dp), colorFilter = ColorFilter.tint(if (mono) Palette.accent else Palette.background))
            }
        } else if (mono && mask != null) {
            Box(Modifier.size(52.dp).clip(badge).background(Palette.surface), contentAlignment = Alignment.Center) {
                Image(mask, label, Modifier.size(36.dp), colorFilter = ColorFilter.tint(Palette.accent))
            }
        } else if (!mono && icon != null) {
            Image(icon, label, Modifier.size(52.dp))
        } else {
            Box(Modifier.size(52.dp).clip(badge).background(Palette.surface), contentAlignment = Alignment.Center) {
                BasicText(initials(label), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title))
            }
        }
        Spacer(Modifier.height(6.dp))
        BasicText(
            label,
            style = Mono.copy(fontSize = TextSize.Small, textAlign = TextAlign.Center),
            // Always two lines tall, so a row keeps its height whichever apps fill it while typing.
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (menuOpen) {
            AppActions(actions(), onDismiss = { menuOpen = false })
        }
    }
}

/** The long-press popup: sits above the cell (below it when there's no room), closes on outside tap. */
@Composable
private fun AppActions(
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position =
        remember(gap) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                    val above = anchorBounds.top - popupContentSize.height - gap
                    val y = if (above >= 0) above else anchorBounds.bottom + gap
                    return IntOffset(x, y)
                }
            }
        }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        MenuItems(actions, onDismiss, minWidth = 160.dp, itemPadding = 12.dp, fontSize = TextSize.Item)
    }
}
