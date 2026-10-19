package dev.omalauncher.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.omalauncher.AndroidApp
import dev.omalauncher.Runtime
import dev.omalauncher.ui.FolderTile
import dev.omalauncher.ui.GridPad
import dev.omalauncher.ui.HomeGrid
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.OverlayPanel
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.ShortcutTile
import dev.omalauncher.ui.WidgetScreenPanel
import dev.omalauncher.ui.WidgetTile
import dev.omalauncher.ui.rankMatches
import dev.omalauncher.widgets.Shortcut
import dev.omalauncher.widgets.Folder
import dev.omalauncher.widgets.WidgetItem
import dev.omalauncher.widgets.isFacePage

internal fun matchingApps(runtime: Runtime, query: String): List<AndroidApp> = if (query.isBlank()) runtime.androidApps.apps.toList() else
    rankMatches(runtime.androidApps.apps.toList(), query, { it.label }, alias = { if (it.profile != null) "${it.label} work" else null }).map { it.first }

/** Tile pages can share a desktop row; a face always owns the whole window. */
internal fun desktopSlots(pages: List<Int>, per: Int, full: (Int) -> Boolean): List<List<Int>> {
    if (per == 1) return pages.map { listOf(it) }
    return buildList {
        val pending = ArrayList<Int>()
        for (page in pages) {
            if (full(page)) {
                if (pending.isNotEmpty()) { add(pending.toList()); pending.clear() }
                add(listOf(page))
            } else {
                pending += page
                if (pending.size == per) { add(pending.toList()); pending.clear() }
            }
        }
        if (pending.isNotEmpty()) add(pending.toList())
    }
}

/** The phone's home is never bound here; only the session's layout, focus and widget screens change. */
@Composable
internal fun DesktopHome(runtime: Runtime, session: DesktopHomeSession) {
    val widgets = session.widgets
    val pages = widgets.pages
    val per = if (session.pagesSideBySide) pages.count { it > 0 }.coerceAtLeast(1) else 1
    fun full(page: Int) = isFacePage(page) || widgets.layout.owner(widgets.itemsOn(page)) != null
    val slots = desktopSlots(pages, per, ::full)
    val pager = rememberPagerState(pageCount = { slots.size })
    val arrange = session.arrange
    var selected by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(Palette.background)) {
        runtime.themes.wallpaper?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        Column(Modifier.fillMaxSize()) {
            HomeGrid(pager = pager, keepClear = GridPad(0, 0, 0, 0), slots = slots, full = ::full,
                itemsOn = widgets::itemsOn, layout = widgets.layout, arrange = arrange,
                selected = selected, onSelect = { selected = it },
                render = { item, tile, focused ->
                    when (item) {
                        is WidgetItem -> if (item.spec.program?.terminal == true) {
                            Box(tile.background(Palette.surface).padding(12.dp)) {
                                BasicText("${item.spec.name}\nTerminal presentation is available on the phone", style = Mono)
                            }
                        } else WidgetTile(item.spec, widgets.instances[item.key], widgets.layout.cell(item.key)?.let { it.w to it.h }, focused, tile,
                            onTap = { widgets.tap(item.spec) }, needs = runtime.widgets.missing[item.key].orEmpty(), install = null,
                            onInstall = { session.unavailable("Install widget dependencies from the phone") },
                            onFix = { session.unavailable("Agent windows are unavailable on this desktop") })
                        is Shortcut -> ShortcutTile(item, runtime.shortcutLabel(item), runtime.shortcutIcon(item, LauncherStyle.monochromeIcons), focused, tile,
                            onTap = { item.component?.let { runtime.androidApps.pinned(it, item.profile) }?.let(session::launch)
                                ?: session.unavailable("This app presentation is unavailable on this desktop") },
                            paused = runtime.androidApps.paused(item.profile), clone = runtime.androidApps.cloned(item.profile))
                        is Folder -> FolderTile(item, item.apps.take(4).map { runtime.shortcutIcon(it, LauncherStyle.monochromeIcons) },
                            item.apps.take(4).map(runtime::shortcutLabel),
                            focused, tile, onTap = { arrange.folder = item.key })
                    }
                },
                actions = { item -> BasicText("remove from desktop", Modifier.clickable { widgets.remove(item); selected = null }.padding(8.dp), style = Mono.copy(color = Palette.red)) },
                onLongPressEmpty = { session.picker = true }, feed = {}, onNoRoom = widgets::noRoom, onMove = widgets::move,
                onGroup = widgets::group, onReorder = widgets::reorder, onRenameFolder = widgets::renameFolder,
                per = per, modifier = Modifier.weight(1f).fillMaxWidth())
            Row(Modifier.fillMaxWidth().background(Palette.background).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BasicText("apps", Modifier.clickable { session.uiAction("drawer") }, style = Mono)
                BasicText("widgets", Modifier.clickable { session.uiAction("widgets") }, style = Mono)
                BasicText(if (session.pagesSideBySide) "${pages.size} home pages" else "page ${pager.currentPage + 1}/${slots.size}", style = Mono.copy(color = Palette.muted))
            }
            session.message?.let { text -> BasicText(text, Modifier.fillMaxWidth().background(Palette.background).clickable { session.message = null }.padding(8.dp), style = Mono.copy(color = Palette.muted)) }
        }
        if (session.drawer) {
            val apps = matchingApps(runtime, session.query)
            val searchFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { searchFocus.requestFocus() }
            OverlayPanel("apps", session::dismiss) {
                BasicTextField(session.query, { session.query = it.take(2048) }, Modifier.fillMaxWidth().padding(12.dp).focusRequester(searchFocus), textStyle = Mono,
                    cursorBrush = SolidColor(Palette.accent), singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { apps.firstOrNull()?.let(session::launch) }))
                LazyColumn(Modifier.weight(1f), state = session.drawerScroll, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(apps, key = { it.key }) { app ->
                        Row(Modifier.fillMaxWidth().alpha(if (runtime.androidApps.paused(app.profile)) 0.5f else 1f).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).clickable { session.launch(app) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                runtime.androidApps.icons[app.key]?.let { Image(it, app.spoken, Modifier.size(40.dp)) }
                                BasicText(app.spoken, style = Mono)
                            }
                            BasicText("[pin]", Modifier.clickable { widgets.pin(app, slots.getOrNull(pager.currentPage)?.firstOrNull { it > 0 } ?: 1); session.dismiss() }.padding(8.dp), style = Mono.copy(color = Palette.accent))
                        }
                    }
                }
            }
        }
        if (session.picker) OverlayPanel("widgets", session::dismiss) {
            LazyColumn(Modifier.weight(1f)) {
                items(widgets.specs, key = { it.file.name }) { spec ->
                    BasicText(spec.name, Modifier.fillMaxWidth().clickable { widgets.show(spec); session.dismiss() }.padding(12.dp), style = Mono)
                }
            }
        }
        widgets.openScreen?.let { name ->
            val spec = widgets.specs.firstOrNull { it.file.name == name }
            val instance = widgets.instances[name]
            if (spec != null && instance?.program?.screen != null) WidgetScreenPanel(spec, instance) { widgets.openScreen = null }
        }
    }
}
