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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.linux.Agent
import dev.omalauncher.widgets.Market
import dev.omalauncher.widgets.WidgetEngine
import dev.omalauncher.widgets.market.Catalog
import dev.omalauncher.widgets.market.CatalogEntry
import kotlinx.coroutines.launch

/**
 * The widget store: built-in terminal tiles (a plain shell, and one per installed coding agent), then
 * reviewed widgets from the marketplace repo as a grid of previews, with
 * search and category tabs. Tapping a widget opens its page, which shows what
 * it installs and runs; from there it goes on the current page (or updates).
 */
@Composable
fun StoreScreen(
    engine: WidgetEngine,
    agents: List<Agent>,
    page: Int,
    /** The Omarchy theme in use, so previews show widgets in its colors. */
    theme: String?,
    onDismiss: () -> Unit,
) {
    val market = engine.market
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val filter = remember { StoreFilter() }
    LaunchedEffect(market) { market.refresh() }
    OverlayPanel(
        "widget store",
        onDismiss,
        actions = {
            Box(Modifier.clip(CircleShape).clickable { scope.launch { market.refresh() } }.padding(6.dp)) {
                Image(Icons.Refresh, "refresh", Modifier.size(20.dp), colorFilter = ColorFilter.tint(Palette.muted))
            }
        },
    ) {
        BoxWithConstraints {
            val wide = maxWidth >= 720.dp
            val paneWidth = (maxWidth * 0.42f).coerceIn(340.dp, 520.dp)
            val detail = open?.let { id -> market.entries.firstOrNull { it.id == id } }
            val select = { id: String ->
                open = id
                message = null
            }
            val showDetail = @Composable { e: CatalogEntry, showBack: Boolean, modifier: Modifier ->
                Detail(
                    market = market,
                    e = e,
                    theme = theme,
                    linux = engine.linux,
                    page = page,
                    message = message,
                    showBack = showBack,
                    modifier = modifier,
                    onBack = {
                        open = null
                        message = null
                    },
                    onInstall = {
                        scope.launch {
                            message = market.install(e, page)
                            if (message == null) onDismiss()
                        }
                    },
                )
            }
            val list = @Composable { modifier: Modifier ->
                StoreList(engine, agents, page, theme, filter, if (wide) open else null, modifier, select, onDismiss)
            }
            when {
                // Side by side: the grid, and the picked widget's page beside it.
                wide ->
                    Row {
                        list(Modifier.weight(1f))
                        Box(Modifier.padding(horizontal = 12.dp).width(1.dp).fillMaxHeight().background(Palette.border))
                        val pane = Modifier.width(paneWidth).fillMaxHeight()
                        if (detail != null) {
                            showDetail(detail, false, pane)
                        } else {
                            Box(pane, contentAlignment = Alignment.Center) {
                                BasicText("pick a widget to see what it installs and runs", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                            }
                        }
                    }
                detail != null -> {
                    BackHandler { open = null }
                    showDetail(detail, true, Modifier.widthIn(max = ReadingWidth).align(Alignment.TopCenter))
                }
                else -> list(Modifier)
            }
        }
    }
}

/** What the grid shows and where it's scrolled, kept while a widget's page covers it or the layout changes. */
private class StoreFilter {
    var query by mutableStateOf("")
    var tab by mutableStateOf(ALL)
    val grid = LazyGridState()
}

/** The store's search, category tabs and grid; [selected] is the widget whose page shows beside it. */
@Composable
private fun StoreList(
    engine: WidgetEngine,
    agents: List<Agent>,
    page: Int,
    theme: String?,
    filter: StoreFilter,
    selected: String?,
    modifier: Modifier,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val market = engine.market
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier) {
        val installed = market.entries.filter { market.installedVersion(it) != null }
        val tabs =
            buildList {
                add(ALL to market.entries.size)
                for (c in Catalog.CATEGORIES) market.entries.count { it.category == c }.takeIf { it > 0 }?.let { add(c to it) }
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
                        INSTALLED -> e in installed
                        OTHER -> e.category == null
                        else -> e.category == filter.tab
                    }
                }.filter { e -> q.isEmpty() || listOf(e.name, e.description, e.author, e.category.orEmpty()).any { q in it.lowercase() } }
        val updates = installed.count { market.hasUpdate(it) }

        SearchField(filter.query, { filter.query = it }, onGo = { keyboard?.hide() }, placeholder = "search ${market.entries.size} widgets")
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
            if (filter.tab == ALL && q.isEmpty()) {
                item(key = "builtin", span = { GridItemSpan(maxLineSpan) }) { Caption("built in") }
                item(key = "terminal", span = { GridItemSpan(maxLineSpan) }) {
                    BuiltInEntry("terminal", "6x4 · a shell in ~", "A shell on the home screen. Add as many as you like; tap one to type.", added = false, page = page) {
                        scope.launch {
                            engine.addTerminalWidget(page)
                            onDismiss()
                        }
                    }
                }
                items(agents, key = { "agent:" + it.bin }, span = { GridItemSpan(maxLineSpan) }) { agent ->
                    BuiltInEntry(
                        agent.name,
                        "6x6 · runs ${agent.bin} in ~",
                        "${agent.description}, in a terminal on the home screen. Tap the tile to type.",
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
                    Caption("reviewed widgets from github.com/${Market.REPO}")
                    when {
                        market.loading && market.entries.isEmpty() -> BasicText("loading…", style = Mono.copy(color = Palette.muted))
                        market.error != null && market.entries.isEmpty() -> BasicText(market.error.orEmpty(), style = Mono.copy(color = Palette.red))
                        market.entries.isEmpty() -> BasicText("no widgets yet", style = Mono.copy(color = Palette.muted))
                        shown.isEmpty() -> BasicText("nothing matches \"${filter.query.trim()}\"", style = Mono.copy(color = Palette.muted))
                    }
                }
            }
            items(shown, key = { it.id }) { e -> Card(market, e, theme, engine.linux, selected = e.id == selected) { onOpen(e.id) } }
        }
    }
}

private const val ALL = "all"
private const val OTHER = "other"
private const val INSTALLED = "installed"

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

/** A built-in entry: a terminal tile, plain or running an agent's CLI, straight from the launcher rather than the catalog. */
@Composable
private fun BuiltInEntry(
    name: String,
    details: String,
    description: String,
    added: Boolean,
    page: Int,
    onAdd: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .frame(1.dp, Palette.border, radius = 12.dp)
            .clickable(enabled = !added, onClick = onAdd)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(name, Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item))
            BasicText(if (added) "on the home screen" else "tap to add to page $page", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
        BasicText("built in · $details", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        BasicText(description, style = Mono.copy(fontSize = TextSize.Small))
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
    val preview = rememberPreview(market, e, theme)
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
        Box(Modifier.fillMaxWidth().height(104.dp), contentAlignment = Alignment.Center) {
            if (preview != null) {
                Image(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Box(Modifier.fillMaxSize().background(Palette.surface), contentAlignment = Alignment.Center) {
                    BasicText(e.name, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                }
            }
        }
        BasicText(e.name, style = Mono.copy(fontSize = TextSize.Body), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val (status, color) =
            when {
                busy != null -> busy to Palette.accent
                update -> "update available" to Palette.accent
                installed -> "✓ installed" to Palette.green
                !e.supported -> "needs a newer launcher" to Palette.red
                !linux && e.needsLinux -> "needs linux" to Palette.muted
                else -> "${e.category ?: OTHER}${e.size?.let { " · ${it.first}x${it.second}" } ?: ""}" to Palette.muted
            }
        BasicText(status, style = Mono.copy(color = color, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A widget's page: a large preview, what it is, what it installs and runs, and the button that adds it. */
@Composable
private fun Detail(
    market: Market,
    e: CatalogEntry,
    theme: String?,
    linux: Boolean,
    page: Int,
    message: String?,
    /** The way back to the grid; off where the grid stays beside the page. */
    showBack: Boolean,
    modifier: Modifier,
    onBack: () -> Unit,
    onInstall: () -> Unit,
) = key(e.id) {
    val installed = market.installedVersion(e)
    val update = market.hasUpdate(e)
    val preview = rememberPreview(market, e, theme)
    val busy = market.busy[e.id]
    // Keyed by the widget, so picking another one beside the grid starts its page at the top.
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showBack) {
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onBack).padding(vertical = 6.dp).padding(end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Image(Icons.Back, null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(Palette.muted))
                BasicText("all widgets", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
        preview?.let {
            // As wide as the panel, but no taller than 320 dp, so a tall widget doesn't push its description off screen:
            // narrowing the width keeps the image inside its slot instead of drawing over the text below.
            val ratio = it.width.toFloat() / it.height
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(it, null, Modifier.widthIn(max = 320.dp * ratio).fillMaxWidth().aspectRatio(ratio), contentScale = ContentScale.Fit)
            }
        }
        Spacer(Modifier.height(4.dp))
        BasicText(e.name, style = Mono.copy(fontSize = TextSize.Title))
        val size = e.size?.let { " · ${it.first}x${it.second}" } ?: ""
        BasicText("${e.category ?: OTHER} · ${e.author} · v${e.version} · ${e.license}$size", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        BasicText(e.description, style = Mono.copy(fontSize = TextSize.Body))
        Spacer(Modifier.height(4.dp))
        when {
            busy != null -> BasicText(busy, style = Mono.copy(color = Palette.accent))
            !e.supported -> BasicText("needs a newer launcher (owl ${e.owl}); update the launcher to install it", style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
            update -> BracketAction("update ${installed?.version} → ${e.version}", Palette.accent, onInstall)
            installed != null -> BasicText("✓ on your home screen", style = Mono.copy(color = Palette.green))
            else -> BracketAction("add to page $page", Palette.accent, onInstall)
        }
        message?.let { BasicText(it, style = Mono.copy(color = Palette.red, fontSize = TextSize.Small)) }
        if (!linux && e.needsLinux) {
            BasicText(
                "needs Linux: it runs commands in Arch, so it waits as a placeholder until you install Linux in settings",
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
        }

        // What it installs and runs, before it's added.
        Spacer(Modifier.height(4.dp))
        val packages = e.requires.entries.joinToString("; ") { (m, l) -> "$m: ${l.joinToString(", ")}" }.ifEmpty { "none" }
        BasicText("installs packages: $packages", style = Mono.copy(fontSize = TextSize.Small))
        if (market.changedSinceApproval(e)) {
            BasicText("its commands or packages changed since the version you approved", style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
        }
        BasicText(if (e.commands.isEmpty()) "runs no commands" else "runs these commands in Arch:", style = Mono.copy(fontSize = TextSize.Small))
        for (c in e.commands) {
            BasicText(
                c.trimEnd(),
                Modifier
                    .fillMaxWidth()
                    .background(Palette.surface)
                    .horizontalScroll(rememberScrollState())
                    .padding(6.dp),
                style = Mono.copy(fontSize = TextSize.Small),
            )
        }
    }
}
