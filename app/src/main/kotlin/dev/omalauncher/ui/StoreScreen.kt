package dev.omalauncher.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.omalauncher.linux.Agent
import dev.omalauncher.widgets.Market
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
    var message by remember { mutableStateOf<String?>(null) }
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
            alone?.name ?: "store",
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
                val select = { id: String ->
                    open = id
                    message = null
                }
                val showDetail = @Composable { e: CatalogEntry, modifier: Modifier ->
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
                            scope.launch {
                                message = market.install(e, page)
                                // A widget closes the store to show it on its page; an app or a face stays, its page saying where it went.
                                if (message == null && market.kind == OwlKind.Widget) onDismiss()
                            }
                        },
                        onAddPages = { scope.launch { message = market.addPages(e) } },
                    )
                }
                val list = @Composable { modifier: Modifier ->
                    StoreList(engine, market, agents, page, theme, filter, if (wide) open else null, modifier, select, onDismiss)
                }
                when {
                    // Side by side: the grid, and the picked widget's page beside it.
                    wide ->
                        Row {
                            list(Modifier.weight(1f))
                            Box(Modifier.padding(horizontal = 12.dp).width(1.dp).fillMaxHeight().background(Palette.border))
                            val pane = Modifier.width(paneWidth).fillMaxHeight()
                            if (detail != null) {
                                showDetail(detail, pane)
                            } else {
                                Box(pane, contentAlignment = Alignment.Center) {
                                    BasicText("pick ${if (kind == OwlKind.App) "an app" else "a ${kind.word}"} to see what it installs and runs", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
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
            val label = "${k.word}s"
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
    val noun = "${market.kind.word}s"
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
                if (installed.isNotEmpty()) add(INSTALLED to installed.size)
            }
        val q = filter.query.trim().lowercase()
        // In the catalog's order, which the store repo sets in order.txt.
        val shown =
            market.entries
                .filter { e ->
                    when (filter.tab) {
                        ALL -> true
                        NEW -> e.new
                        INSTALLED -> e in installed
                        OTHER -> e.category == null
                        else -> e.category == filter.tab
                    }
                }.filter { e -> q.isEmpty() || listOf(e.name, e.description, e.author, e.category.orEmpty()).any { q in it.lowercase() } }
                // Newest first; a stable sort, so a day's widgets keep the catalog's order.
                .let { l -> if (filter.tab == NEW) l.sortedByDescending { it.added } else l }
        val updates = installed.count { market.hasUpdate(it) }

        SearchField(filter.query, { filter.query = it }, onGo = { keyboard?.hide() }, placeholder = "search ${market.entries.size} $noun")
        Spacer(Modifier.height(8.dp))
        // Scrolls under the panel's padding, up to its frame or, side by side, the divider.
        Row(Modifier.bleed(PanelPadding).horizontalScroll(rememberScrollState()).padding(horizontal = PanelPadding), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((name, n) in tabs) {
                val label = if (name == INSTALLED && updates > 0) "$name $n · $updates↑" else "$name $n"
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
                item(key = "builtin", span = { GridItemSpan(maxLineSpan) }) { Caption("built in") }
                item(key = "terminal") {
                    BuiltInEntry("terminal", "6x4 · a shell", added = false, page = page) {
                        scope.launch {
                            engine.addTerminalWidget(page)
                            onDismiss()
                        }
                    }
                }
                items(agents, key = { "agent:" + it.bin }) { agent ->
                    BuiltInEntry(
                        agent.name,
                        "6x6 · runs ${agent.command}",
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
            item(key = "catalog", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Caption("reviewed $noun from github.com/${Market.REPO}")
                    when {
                        market.loading && market.entries.isEmpty() -> BasicText("loading…", style = Mono.copy(color = Palette.muted))
                        market.error != null && market.entries.isEmpty() -> BasicText(market.error.orEmpty(), style = Mono.copy(color = Palette.red))
                        market.entries.isEmpty() -> BasicText("no $noun yet", style = Mono.copy(color = Palette.muted))
                        shown.isEmpty() && q.isEmpty() && filter.tab == NEW -> BasicText("no new $noun", style = Mono.copy(color = Palette.muted))
                        shown.isEmpty() -> BasicText("nothing matches \"${filter.query.trim()}\"", style = Mono.copy(color = Palette.muted))
                    }
                }
            }
            items(shown, key = { it.id }) { e -> Card(market, e, theme, engine.linux, selected = e.id == selected) { onOpen(e.id) } }
        }
    }
}

private const val ALL = "all"
private const val NEW = "new"
private const val OTHER = "other"
private const val INSTALLED = "installed"

private val CardIconSize = 32.dp
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

/** A built-in entry, a grid cell of its own: a terminal tile, plain or running an agent's CLI, straight from the launcher rather than the catalog. */
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
            .fillMaxWidth()
            .frame(1.dp, Palette.border, radius = 12.dp)
            .clickable(enabled = !added, onClick = onAdd)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val small = Mono.copy(color = Palette.muted, fontSize = TextSize.Small)
        BasicText(name, style = Mono.copy(fontSize = TextSize.Body), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(details, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (added) {
            BasicText("✓ on home", style = small.copy(color = Palette.green), maxLines = 1)
        } else {
            BasicText("tap to add to page $page", style = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                busy != null -> busy to Palette.accent
                update -> "update available" to Palette.accent
                installed -> "✓ installed" to Palette.green
                !e.supported -> "needs a newer launcher" to Palette.red
                !linux && e.needsLinux -> "needs linux" to Palette.muted
                else -> "${e.category ?: OTHER}${e.size?.let { " · ${it.first}x${it.second}" } ?: pagesText(e)}" to Palette.muted
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
        "new",
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
    message: String?,
    modifier: Modifier,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
    onAddPages: () -> Unit,
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
                BasicText("${e.category ?: OTHER} · ${e.author} · v${e.version} · ${e.license}$size", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
        BasicText(e.description, style = Mono.copy(fontSize = TextSize.Body))
        Spacer(Modifier.height(4.dp))
        when {
            busy != null -> BasicText(busy, style = Mono.copy(color = Palette.accent))
            !e.supported -> BasicText("needs a newer launcher (owl ${e.owl}); update the launcher to install it", style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
            // Settings leaves an edited one alone; here it's updated knowing what goes.
            update && market.edited(e) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText(
                        if (installed?.sha256 == null) "it may have your changes, and updating replaces them" else "you changed it, and updating replaces your changes",
                        style = Mono.copy(color = Palette.red, fontSize = TextSize.Small),
                    )
                    BracketAction("update anyway ${installed?.version} → ${e.version}", Palette.accent, Modifier.passesKeys(), onInstall)
                }
            update -> BracketAction("update ${installed?.version} → ${e.version}", Palette.accent, Modifier.passesKeys(), onInstall)
            // An app opens from here, unless it waits for Linux.
            installed != null && market.kind == OwlKind.App && (linux || !e.needsLinux) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AddedMark(market.kind)
                    Spacer(Modifier.width(8.dp))
                    BracketAction("open", Palette.accent, Modifier.takesPassedKeys(), onOpen)
                }
            // A face's page removed on home, or one its version added, comes back only from here.
            installed != null && market.missingPages(e) > 0 ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AddedMark(market.kind)
                    Spacer(Modifier.width(8.dp))
                    val n = market.missingPages(e)
                    BracketAction(if (n == 1) "add its missing page" else "add its $n missing pages", Palette.accent, Modifier.passesKeys(), onAddPages)
                }
            installed != null -> AddedMark(market.kind)
            else ->
                BracketAction(
                    when (market.kind) {
                        OwlKind.Widget -> "add to page $page"
                        OwlKind.Face -> "add as a face"
                        OwlKind.App, OwlKind.Panel, OwlKind.Keybar -> "install"
                    },
                    Palette.accent,
                    // Keys on it wait out the install, for the open an app ends with.
                    Modifier.passesKeys(),
                    onInstall,
                )
        }
        message?.let { BasicText(it, style = Mono.copy(color = Palette.red, fontSize = TextSize.Small)) }
        if (!linux && e.needsLinux) NeedsLinuxNote(market.kind == OwlKind.App)

        Spacer(Modifier.height(4.dp))
        InstallDisclosure(e.requires, e.commands) {
            if (market.changedSinceApproval(e)) {
                BasicText("its commands or packages changed since the version you approved", style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
            }
        }
    }
}

/** " · 2 pages" for a face with several, else nothing. */
private fun pagesText(e: CatalogEntry) = if (e.pages.isEmpty()) "" else " · ${1 + e.pages.size} pages"

/** Where an added widget, face, app or panel went. */
@Composable
internal fun AddedMark(kind: OwlKind) =
    BasicText(
        when (kind) {
            OwlKind.Widget -> "✓ on your home screen"
            OwlKind.Face -> "✓ left of your first page"
            OwlKind.App -> "✓ in your app drawer"
            OwlKind.Panel -> "✓ in gesture settings"
            // Shown only as keybar.owl, the file's name in the widgets folder.
            OwlKind.Keybar -> "✓ in your widgets folder"
        },
        style = Mono.copy(color = Palette.green),
    )

/** Why one that runs commands in Arch doesn't work yet, and where it waits meanwhile. */
@Composable
internal fun NeedsLinuxNote(app: Boolean) =
    BasicText(
        if (app) {
            "needs Linux: it runs commands in Arch, so it shows in the app drawer once you install Linux in settings"
        } else {
            "needs Linux: it runs commands in Arch, so it waits as a placeholder until you install Linux in settings"
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
    val packages = requires.entries.joinToString("; ") { (m, l) -> "$m: ${l.joinToString(", ")}" }.ifEmpty { "none" }
    // A stop for keys, at the end of a page that may have nothing else to scroll it there.
    Column(Modifier.keyScrolled(around = true), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText("installs packages: $packages", style = Mono.copy(fontSize = TextSize.Small))
        notice()
        BasicText(if (commands.isEmpty()) "runs no commands" else "runs these commands in Arch:", style = Mono.copy(fontSize = TextSize.Small))
    }
    for (c in commands) CodeBlock(c.trimEnd())
}
