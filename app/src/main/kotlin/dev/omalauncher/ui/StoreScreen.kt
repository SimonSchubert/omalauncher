package dev.omalauncher.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.omalauncher.R
import dev.omalauncher.linux.Agent
import dev.omalauncher.widgets.Market
import dev.omalauncher.widgets.StoreMessage
import dev.omalauncher.widgets.WidgetEngine
import dev.omalauncher.widgets.lang.OwlKind
import dev.omalauncher.widgets.market.Catalog
import dev.omalauncher.widgets.market.CatalogEntry
import kotlinx.coroutines.launch

/**
 * The store, with a toggle between widgets, faces and apps. Widgets: built-in terminal tiles (a plain shell,
 * and one per installed coding agent), then reviewed widgets from the marketplace repo as a grid of
 * previews, with search and category tabs (and one for the newest). Faces and apps: the reviewed ones, the same way.
 * Tapping one opens its page, which shows what it installs and runs; from there a widget goes on the current page,
 * a face left of page 1 and an app in the drawer (or any of them updates). An installed app opens from its page too.
 */
@Composable
fun StoreScreen(
    engine: WidgetEngine,
    agents: List<Agent>,
    page: Int,
    /** The Omarchy theme in use, so previews show widgets in its colors. */
    theme: String?,
    onDismiss: () -> Unit,
    /** The kind it opens on. */
    initialKind: OwlKind = OwlKind.Widget,
    /** Opens the installed app in this file, as the drawer does. */
    onOpenApp: (String) -> Unit = {},
) {
    var kind by remember { mutableStateOf(initialKind) }
    val market = engine.market(kind)
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<StoreMessage?>(null) }
    // Each kind keeps its search, tab and scroll while the other shows.
    val filters = remember { OwlKind.entries.associateWith { StoreFilter() } }
    val filter = filters.getValue(kind)
    LaunchedEffect(market) { market.refresh() }
    val detail = open?.let { id -> market.entries.firstOrNull { it.id == id } }
    val back = {
        open = null
        message = null
    }
    BoxWithConstraints {
        // What the panel's content gets: the screen less the gap and padding on both sides.
        val wide = maxWidth - (PanelGap + PanelPadding) * 2 >= 720.dp
        // On its own, an entry's page takes the header: a chevron back to the grid and its name, nothing else.
        val alone = detail?.takeIf { !wide }
        OverlayPanel(
            alone?.name ?: stringResource(R.string.store_title),
            onDismiss,
            onBack = if (alone != null) back else LocalPanelBack.current,
            actions = {
                if (alone == null) {
                    KindToggle(kind, engine.markets.map { it.kind }) {
                        kind = it
                        open = null
                        message = null
                    }
                    RefreshButton(market.loading) { scope.launch { market.refresh() } }
                }
            },
        ) {
            BoxWithConstraints {
                val paneWidth = (maxWidth * 0.42f).coerceIn(340.dp, 520.dp)
                // Side by side, the pane is never empty: until one is tapped, the grid's first entry shows in it.
                val picked = if (wide) detail ?: shownEntries(market, filter).firstOrNull() else detail
                val select = { id: String ->
                    open = id
                    message = null
                }
                val showDetail = @Composable { e: CatalogEntry, modifier: Modifier ->
                    // Acting on the preselected entry picks it, so its message stays with it when the grid's first entry changes.
                    val pick = { open = e.id }
                    Detail(
                        market = market,
                        e = e,
                        theme = theme,
                        linux = engine.linux,
                        page = page,
                        message = message,
                        modifier = modifier,
                        onOpen = { market.installedVersion(e)?.let { onOpenApp(it.file) } },
                        onInstall = {
                            pick()
                            scope.launch {
                                message = market.install(e, page)
                                // A widget closes the store to show it on its page; an app or a face stays, its page saying where it went.
                                if (message == null && market.kind == OwlKind.Widget) onDismiss()
                            }
                        },
                        onAddPages = {
                            pick()
                            scope.launch { message = market.addPages(e) }
                        },
                        onRemove = {
                            pick()
                            message = null
                            market.remove(e)
                        },
                    )
                }
                val list = @Composable { modifier: Modifier ->
                    StoreList(engine, market, agents, page, theme, filter, if (wide) picked?.id else null, modifier, select, onDismiss)
                }
                when {
                    // Side by side: the grid, and the picked widget's page beside it.
                    wide ->
                        Row {
                            list(Modifier.weight(1f))
                            Box(Modifier.padding(horizontal = 12.dp).width(1.dp).fillMaxHeight().background(Palette.border))
                            val pane = Modifier.width(paneWidth).fillMaxHeight()
                            if (picked != null) {
                                showDetail(picked, pane)
                            } else {
                                Box(pane, contentAlignment = Alignment.Center) {
                                    BasicText(stringResource(pickText(kind)), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                                }
                            }
                        }
                    detail != null -> {
                        BackHandler(onBack = back)
                        showDetail(detail, Modifier.padding(top = 8.dp).widthIn(max = ReadingWidth).align(Alignment.TopCenter))
                    }
                    else -> list(Modifier)
                }
            }
        }
    }
}

/** Widgets, faces or apps: segments in the store's header, the shown one filled. */
@Composable
private fun KindToggle(
    kind: OwlKind,
    /** The kinds the store has; panels have none. */
    kinds: List<OwlKind>,
    onPick: (OwlKind) -> Unit,
) {
    Row(Modifier.padding(end = 4.dp).frame(1.dp, Palette.border, radius = 14.dp)) {
        for (k in kinds) {
            val label = stringResource(kindPlural(k))
            val on = k == kind
            BasicText(
                label,
                Modifier
                    .background(if (on) Palette.accent.copy(alpha = 0.15f) else Color.Transparent)
                    // The shown one still takes a tap, doing nothing, so keys on it as it's picked stay there.
                    .clickable { if (!on) onPick(k) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = Mono.copy(color = if (on) Palette.accent else Palette.muted, fontSize = TextSize.Small),
            )
        }
    }
}

/** What the grid shows and where it's scrolled, kept while an entry's page covers it or the layout changes. */
private class StoreFilter {
    var query by mutableStateOf("")
    var tab by mutableStateOf(ALL)
    val grid = LazyGridState()
}

/** The store's search, category tabs and grid for [market]'s kind; [selected] is the entry whose page shows beside it. */
@Composable
private fun StoreList(
    engine: WidgetEngine,
    market: Market,
    agents: List<Agent>,
    page: Int,
    theme: String?,
    filter: StoreFilter,
    selected: String?,
    modifier: Modifier,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val apps = market.kind == OwlKind.App
    // Faces get a "new" tab like widgets; apps have never had one.
    val noun = stringResource(kindPlural(market.kind))
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier) {
        val installed = market.entries.filter { market.installedVersion(it) != null }
        val tabs =
            buildList {
                add(ALL to market.entries.size)
                // There even when nothing is new.
                if (!apps) add(NEW to market.entries.count { it.new })
                // The biggest categories first, ties in the catalog's order. Ones the store added after this launcher was built
                // get a tab too, so a new category doesn't wait for an update.
                val known = { c: String -> Catalog.CATEGORIES.indexOf(c).takeIf { it >= 0 } ?: Int.MAX_VALUE }
                market.entries.mapNotNull { it.category }.filter { it != OTHER }.groupingBy { it }.eachCount().entries
                    .sortedWith(compareBy({ -it.value }, { known(it.key) }, { it.key }))
                    .forEach { (c, n) -> add(c to n) }
                market.entries.count { it.category == null }.takeIf { it > 0 }?.let { add(OTHER to it) }
                // There even when nothing is, as "new" is, so each kind's store has the same tabs.
                add(INSTALLED to installed.size)
            }
        val q = filter.query.trim().lowercase()
        val shown = shownEntries(market, filter)
        val updates = installed.count { market.hasUpdate(it) }

        SearchField(filter.query, { filter.query = it }, onGo = { keyboard?.hide() }, placeholder = stringResource(R.string.store_search, market.entries.size, noun))
        Spacer(Modifier.height(8.dp))
        // Scrolls under the panel's padding, up to its frame or, side by side, the divider.
        Row(Modifier.bleed(PanelPadding).horizontalScroll(rememberScrollState()).padding(horizontal = PanelPadding), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((name, n) in tabs) {
                val label = if (name == INSTALLED && updates > 0) stringResource(R.string.store_tab_updates, tabName(name), n, updates) else stringResource(R.string.store_tab, tabName(name), n)
                Chip(label, selected = filter.tab == name) { filter.tab = name }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            state = filter.grid,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (market.kind == OwlKind.Widget && filter.tab == ALL && q.isEmpty()) {
                // One row that scrolls sideways, so a long list of agents doesn't push the catalog off the screen.
                item(key = "builtin", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Caption(stringResource(R.string.store_builtin))
                        // Scrolls under the panel's padding, as the tabs do.
                        Row(
                            Modifier.bleed(PanelPadding).horizontalScroll(rememberScrollState()).padding(horizontal = PanelPadding),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BuiltInEntry(stringResource(R.string.store_terminal), stringResource(R.string.store_terminal_details), added = false, page = page) {
                                scope.launch {
                                    engine.addTerminalWidget(page)
                                    onDismiss()
                                }
                            }
                            for (agent in agents) {
                                key(agent.bin) {
                                    BuiltInEntry(
                                        agent.name,
                                        stringResource(R.string.store_agent_details, agent.command),
                                        added = engine.hasAgentWidget(agent),
                                        page = page,
                                    ) {
                                        scope.launch {
                                            engine.addAgentWidget(agent, page)
                                            onDismiss()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "catalog", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Caption(stringResource(R.string.store_reviewed, noun, Market.REPO))
                    when {
                        market.loading && market.entries.isEmpty() -> BasicText(stringResource(R.string.store_loading), style = Mono.copy(color = Palette.muted))
                        market.error != null && market.entries.isEmpty() -> BasicText(market.error?.resolve().orEmpty(), style = Mono.copy(color = Palette.red))
                        market.entries.isEmpty() -> BasicText(stringResource(R.string.store_empty, noun), style = Mono.copy(color = Palette.muted))
                        shown.isEmpty() && q.isEmpty() && filter.tab == NEW -> BasicText(stringResource(R.string.store_no_new, noun), style = Mono.copy(color = Palette.muted))
                        shown.isEmpty() && q.isEmpty() && filter.tab == INSTALLED -> BasicText(stringResource(R.string.store_none_installed, noun), style = Mono.copy(color = Palette.muted))
                        shown.isEmpty() -> BasicText(stringResource(R.string.store_no_match, filter.query.trim()), style = Mono.copy(color = Palette.muted))
                    }
                }
            }
            items(shown, key = { it.id }) { e -> Card(market, e, theme, engine.linux, selected = e.id == selected) { onOpen(e.id) } }
        }
    }
}

/** The entries [filter]'s tab and search leave of [market]'s, in the order the grid shows them. */
private fun shownEntries(
    market: Market,
    filter: StoreFilter,
): List<CatalogEntry> {
    val q = filter.query.trim().lowercase()
    // In the catalog's order, which the store repo sets in order.txt.
    return market.entries
        .filter { e ->
            when (filter.tab) {
                ALL -> true
                NEW -> e.new
                INSTALLED -> market.installedVersion(e) != null
                OTHER -> e.category == null
                else -> e.category == filter.tab
            }
        }.filter { e -> q.isEmpty() || listOf(e.name, e.description, e.author, e.category.orEmpty()).any { q in it.lowercase() } }
        // Newest first; a stable sort, so a day's widgets keep the catalog's order.
        .let { l -> if (filter.tab == NEW) l.sortedByDescending { it.added } else l }
}

private const val ALL = "all"
private const val NEW = "new"
private const val OTHER = "other"
private const val INSTALLED = "installed"

/** A tab's name: one of the store's own, or a category as the catalog names it. */
@Composable
private fun tabName(tab: String) =
    when (tab) {
        ALL -> stringResource(R.string.store_tab_all)
        NEW -> stringResource(R.string.store_tab_new)
        OTHER -> stringResource(R.string.store_category_other)
        INSTALLED -> stringResource(R.string.store_tab_installed)
        else -> tab
    }

/** What [kind]s are called, as the store lists them. */
@StringRes
private fun kindPlural(kind: OwlKind) =
    when (kind) {
        OwlKind.Widget -> R.string.store_kind_widgets
        OwlKind.Face -> R.string.store_kind_faces
        OwlKind.App -> R.string.store_kind_apps
        OwlKind.Panel -> R.string.store_kind_panels
        OwlKind.Keybar -> R.string.store_kind_keybars
    }

/** What one [kind] is called. */
@StringRes
internal fun kindName(kind: OwlKind) =
    when (kind) {
        OwlKind.Widget -> R.string.store_kind_widget
        OwlKind.Face -> R.string.store_kind_face
        OwlKind.App -> R.string.store_kind_app
        OwlKind.Panel -> R.string.store_kind_panel
        OwlKind.Keybar -> R.string.store_kind_keybar
    }

@StringRes
private fun pickText(kind: OwlKind) =
    when (kind) {
        OwlKind.Widget -> R.string.store_pick_widget
        OwlKind.Face -> R.string.store_pick_face
        OwlKind.App -> R.string.store_pick_app
        OwlKind.Panel -> R.string.store_pick_panel
        OwlKind.Keybar -> R.string.store_pick_keybar
    }

@Composable
private fun StoreMessage.resolve() = if (arg == null) stringResource(text) else stringResource(text, arg)

private val CardIconSize = 32.dp

/** A built-in entry's width, room for "tap to add to page 10". */
private val BuiltInWidth = 180.dp
private val DetailIconSize = 48.dp

/** How tall an entry's page shows its preview and screens, so its description stays in view. */
private val ScreenHeight = 320.dp

/** Width over height of a phone screen, for an app's screens before they load. */
private const val PHONE_RATIO = 0.45f

@Composable
private fun Caption(text: String) = BasicText(text, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    BasicText(
        label,
        Modifier
            .frame(1.dp, if (selected) Palette.accent else Palette.border, if (selected) Palette.accent.copy(alpha = 0.15f) else Color.Transparent, radius = 14.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = Mono.copy(color = if (selected) Palette.accent else Palette.foreground, fontSize = TextSize.Small),
    )
}

/** A built-in entry, a card in the built-in row: a terminal tile, plain or running an agent's CLI, straight from the launcher rather than the catalog. */
@Composable
private fun BuiltInEntry(
    name: String,
    details: String,
    added: Boolean,
    page: Int,
    onAdd: () -> Unit,
) {
    Column(
        Modifier
            .width(BuiltInWidth)
            .frame(1.dp, Palette.border, radius = 12.dp)
            .clickable(enabled = !added, onClick = onAdd)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val small = Mono.copy(color = Palette.muted, fontSize = TextSize.Small)
        BasicText(name, style = Mono.copy(fontSize = TextSize.Body), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(details, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (added) {
            BasicText(stringResource(R.string.store_on_home), style = small.copy(color = Palette.green), maxLines = 1)
        } else {
            BasicText(stringResource(R.string.store_tap_to_add, page), style = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun rememberPreview(
    market: Market,
    e: CatalogEntry,
    theme: String?,
): ImageBitmap? {
    val preview by produceState<ImageBitmap?>(null, e.previewFor(theme)) { value = market.preview(e, theme) }
    return preview
}

/** [e]'s icon, as the drawer draws it, or null while it loads or when it has none. */
@Composable
private fun rememberIcon(
    market: Market,
    e: CatalogEntry,
    size: Dp,
): ImageBitmap? {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val accent = Palette.accent.toArgb()
    val icon by produceState<ImageBitmap?>(null, e.icon, e.iconFile, px, accent) { value = market.icon(e, px, accent) }
    return icon
}

/**
 * [e]'s preview the size a card draws it. Cards are at least 150 dp wide and, with an adaptive grid, under twice that and the gap;
 * less their padding, a preview is [CardPreviewWidth] at most and [CardPreviewHeight] high, and a whole screen shows
 * its top [CardPreviewHeight] / [CardPreviewMinWidth] of its width at most.
 */
@Composable
private fun rememberCardPreview(
    market: Market,
    e: CatalogEntry,
    theme: String?,
): ImageBitmap? {
    val (w, h) = with(LocalDensity.current) { CardPreviewWidth.roundToPx() to CardPreviewHeight.roundToPx() }
    val top = if (market.kind == OwlKind.Widget) null else CardPreviewHeight / CardPreviewMinWidth
    val preview by produceState<ImageBitmap?>(null, e.previewFor(theme), w, h) { value = market.thumbnail(e, theme, w, h, top) }
    return preview
}

private val CardPreviewHeight = 104.dp
private val CardPreviewMinWidth = 150.dp - 16.dp
private val CardPreviewWidth = 150.dp * 2 + 8.dp - 16.dp

/** A grid cell: the preview, the name, and whether it's installed; [selected] while its page shows beside the grid. */
@Composable
private fun Card(
    market: Market,
    e: CatalogEntry,
    theme: String?,
    linux: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
) {
    val preview = rememberCardPreview(market, e, theme)
    val busy = market.busy[e.id]
    val update = market.hasUpdate(e)
    val installed = market.installedVersion(e) != null
    Column(
        Modifier
            .fillMaxWidth()
            .frame(1.dp, if (selected) Palette.accent else Palette.border, radius = 12.dp)
            .clickable(onClick = onOpen)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(CardPreviewHeight), contentAlignment = Alignment.Center) {
            if (preview != null && market.kind != OwlKind.Widget) {
                // A whole phone screen would be a sliver this high: its top, as wide as the card, reads as the app or face.
                Image(preview, null, Modifier.fillMaxSize(), alignment = Alignment.TopCenter, contentScale = ContentScale.FillWidth)
            } else if (preview != null) {
                Image(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Box(Modifier.fillMaxSize().background(Palette.surface), contentAlignment = Alignment.Center) {
                    BasicText(e.name, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                }
            }
        }
        val (status, color) =
            when {
                busy != null -> stringResource(busy) to Palette.accent
                update -> stringResource(R.string.store_update_available) to Palette.accent
                installed -> stringResource(R.string.store_installed) to Palette.green
                !e.supported -> stringResource(R.string.store_needs_newer) to Palette.red
                !linux && e.needsLinux -> stringResource(R.string.store_needs_linux) to Palette.muted
                else -> "${category(e)}${e.size?.let { " · ${it.first}x${it.second}" } ?: pagesText(e)}" to Palette.muted
            }
        // An app's icon goes before its name and status, as in the drawer; widgets have none.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (e.icon != null || e.iconFile != null) {
                val icon = rememberIcon(market, e, CardIconSize)
                Box(Modifier.size(CardIconSize)) { icon?.let { Image(it, null, Modifier.fillMaxSize()) } }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText(e.name, Modifier.weight(1f, fill = false), style = Mono.copy(fontSize = TextSize.Body), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (e.new) NewBadge()
                }
                BasicText(status, style = Mono.copy(color = color, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Marks an entry added to the store in the last few days, beside its name; its status stays as it is. */
@Composable
private fun NewBadge() =
    BasicText(
        stringResource(R.string.store_badge_new),
        Modifier.frame(1.dp, Palette.accent, Palette.accent.copy(alpha = 0.15f), radius = 6.dp).padding(horizontal = 5.dp, vertical = 1.dp),
        style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small),
    )

/** An app's [preview] and its other [screens] side by side, swiped through; each one loads as it scrolls into view. */
@Composable
private fun Screens(
    market: Market,
    preview: ImageBitmap?,
    screens: List<String>,
) {
    // Until one has loaded, a slot is phone-shaped.
    val ratio = preview?.let { it.width.toFloat() / it.height } ?: PHONE_RATIO
    // Keys stop at each, so the arrows go through them, the row scrolling to follow.
    val shot = Modifier.height(ScreenHeight).aspectRatio(ratio).frame(1.dp, Palette.border, radius = 8.dp).clip(RoundedCornerShape(8.dp)).keyScrolled()
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "preview") { Box(shot.background(Palette.surface)) { preview?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } } }
        items(screens, key = { it }) { path ->
            val img by produceState<ImageBitmap?>(null, path) { value = market.image(path) }
            Box(shot.background(Palette.surface)) { img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } }
        }
    }
}

/** A widget's or app's page: a large preview, what it is, what it installs and runs, and the button that adds it. */
@Composable
private fun Detail(
    market: Market,
    e: CatalogEntry,
    theme: String?,
    linux: Boolean,
    page: Int,
    message: StoreMessage?,
    modifier: Modifier,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
    onAddPages: () -> Unit,
    onRemove: () -> Unit,
) = key(e.id) {
    val installed = market.installedVersion(e)
    val update = market.hasUpdate(e)
    val preview = rememberPreview(market, e, theme)
    val busy = market.busy[e.id]
    // Keyed by the widget, so picking another one beside the grid starts its page at the top.
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val screens = e.screensFor(theme)
        if (screens.isNotEmpty()) {
            Screens(market, preview, screens)
        } else {
            preview?.let {
                // As wide as the panel, but no taller than 320 dp, so a tall widget doesn't push its description off screen:
                // narrowing the width keeps the image inside its slot instead of drawing over the text below.
                val ratio = it.width.toFloat() / it.height
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(it, null, Modifier.widthIn(max = ScreenHeight * ratio).fillMaxWidth().aspectRatio(ratio), contentScale = ContentScale.Fit)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (e.icon != null || e.iconFile != null) {
                val icon = rememberIcon(market, e, DetailIconSize)
                Box(Modifier.size(DetailIconSize)) { icon?.let { Image(it, null, Modifier.fillMaxSize()) } }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText(e.name, Modifier.weight(1f, fill = false), style = Mono.copy(fontSize = TextSize.Title))
                    if (e.new) NewBadge()
                }
                val size = e.size?.let { " · ${it.first}x${it.second}" } ?: pagesText(e)
                BasicText("${category(e)} · ${e.author} · v${e.version} · ${e.license}$size", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
        BasicText(e.description, style = Mono.copy(fontSize = TextSize.Body))
        Spacer(Modifier.height(4.dp))
        when {
            busy != null -> BasicText(stringResource(busy), style = Mono.copy(color = Palette.accent))
            !e.supported -> BasicText(stringResource(R.string.store_needs_newer_detail, e.owl), style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
            // Settings leaves an edited one alone; here it's updated knowing what goes.
            update && market.edited(e) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText(
                        stringResource(if (installed?.sha256 == null) R.string.store_may_have_changes else R.string.store_you_changed),
                        style = Mono.copy(color = Palette.red, fontSize = TextSize.Small),
                    )
                    BracketAction(stringResource(R.string.store_update_anyway, installed?.version.toString(), e.version), Palette.accent, Modifier.passesKeys(), onInstall)
                }
            update -> BracketAction(stringResource(R.string.store_update, installed?.version.toString(), e.version), Palette.accent, Modifier.passesKeys(), onInstall)
            // An app opens from here, unless it waits for Linux.
            installed != null && market.kind == OwlKind.App && (linux || !e.needsLinux) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AddedMark(market.kind)
                    Spacer(Modifier.width(8.dp))
                    BracketAction(stringResource(R.string.store_open), Palette.accent, Modifier.takesPassedKeys(), onOpen)
                }
            // A face's page removed on home, or one its version added, comes back only from here.
            installed != null && market.missingPages(e) > 0 ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AddedMark(market.kind)
                    Spacer(Modifier.width(8.dp))
                    val n = market.missingPages(e)
                    BracketAction(pluralStringResource(R.plurals.store_add_missing_pages, n, n), Palette.accent, Modifier.passesKeys(), onAddPages)
                }
            installed != null -> AddedMark(market.kind)
            else ->
                BracketAction(
                    when (market.kind) {
                        OwlKind.Widget -> stringResource(R.string.store_add_to_page, page)
                        OwlKind.Face -> stringResource(R.string.store_add_as_face)
                        OwlKind.App, OwlKind.Panel, OwlKind.Keybar -> stringResource(R.string.store_install)
                    },
                    Palette.accent,
                    // Keys on it wait out the install, for the open an app ends with.
                    Modifier.passesKeys(),
                    onInstall,
                )
        }
        if (installed != null && busy == null) {
            // Asked first, as removing a page is.
            var confirming by remember { mutableStateOf(false) }
            val app = market.kind == OwlKind.App
            val word = stringResource(if (app) R.string.store_uninstall else R.string.store_remove)
            BracketAction(word, Palette.red) { confirming = true }
            if (confirming) {
                ConfirmDialog(
                    stringResource(if (app) R.string.store_uninstall_confirm else R.string.store_remove_confirm, e.name),
                    if (installed.pages.isEmpty()) {
                        stringResource(R.string.store_remove_file)
                    } else {
                        stringResource(R.string.store_remove_files)
                    },
                    word,
                    onDismiss = { confirming = false },
                ) {
                    confirming = false
                    onRemove()
                }
            }
        }
        message?.let { BasicText(it.resolve(), style = Mono.copy(color = Palette.red, fontSize = TextSize.Small)) }
        if (!linux && e.needsLinux) NeedsLinuxNote(market.kind == OwlKind.App)

        Spacer(Modifier.height(4.dp))
        InstallDisclosure(e.requires, e.commands) {
            if (market.changedSinceApproval(e)) {
                BasicText(stringResource(R.string.store_changed_since_approval), style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
            }
        }
    }
}

/** " · 2 pages" for a face with several, else nothing. */
@Composable
private fun pagesText(e: CatalogEntry) = if (e.pages.isEmpty()) "" else " · " + pluralStringResource(R.plurals.store_pages, 1 + e.pages.size, 1 + e.pages.size)

/** [e]'s category, or other when the catalog gives it none. */
@Composable
private fun category(e: CatalogEntry) = e.category ?: stringResource(R.string.store_category_other)

/** Where an added widget, face, app or panel went. */
@Composable
internal fun AddedMark(kind: OwlKind) =
    BasicText(
        when (kind) {
            OwlKind.Widget -> stringResource(R.string.store_added_widget)
            OwlKind.Face -> stringResource(R.string.store_added_face)
            OwlKind.App -> stringResource(R.string.store_added_app)
            OwlKind.Panel -> stringResource(R.string.store_added_panel)
            // Shown only as keybar.owl, the file's name in the widgets folder.
            OwlKind.Keybar -> stringResource(R.string.store_added_keybar)
        },
        style = Mono.copy(color = Palette.green),
    )

/** Why one that runs commands in Arch doesn't work yet, and where it waits meanwhile. */
@Composable
internal fun NeedsLinuxNote(app: Boolean) =
    BasicText(
        if (app) {
            stringResource(R.string.store_needs_linux_app)
        } else {
            stringResource(R.string.store_needs_linux_widget)
        },
        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
    )

/**
 * What a widget or app installs and runs, shown before it's added: the store's
 * entries and shared links say it the same way. [notice] goes between the two.
 */
@Composable
internal fun InstallDisclosure(
    requires: Map<String, List<String>>,
    commands: List<String>,
    notice: @Composable () -> Unit = {},
) {
    val packages = requires.entries.joinToString("; ") { (m, l) -> "$m: ${l.joinToString(", ")}" }.ifEmpty { stringResource(R.string.store_packages_none) }
    // A stop for keys, at the end of a page that may have nothing else to scroll it there.
    Column(Modifier.keyScrolled(around = true), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(stringResource(R.string.store_installs_packages, packages), style = Mono.copy(fontSize = TextSize.Small))
        notice()
        BasicText(stringResource(if (commands.isEmpty()) R.string.store_runs_no_commands else R.string.store_runs_commands), style = Mono.copy(fontSize = TextSize.Small))
    }
    for (c in commands) CodeBlock(c.trimEnd())
}
