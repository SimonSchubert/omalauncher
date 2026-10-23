package dev.omalauncher.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.omalauncher.AddWidgetActivity
import dev.omalauncher.AppWidgetCatalog
import dev.omalauncher.Runtime

/** Which apps' widgets the picker lists: one app's, from its long-press menu, or with a null [packageName] every app's. */
data class WidgetPick(
    val packageName: String? = null,
)

/**
 * The phone's apps' widgets, by app, to put one on home [page]: a preview of each, its name and the cells it takes.
 * Only apps in the launcher's own profile. Picking one closes the list; Android may ask first, and the widget may
 * show its settings, before it lands on the page.
 */
@Composable
internal fun WidgetPicker(
    runtime: Runtime,
    pick: WidgetPick,
    page: Int,
    onDismiss: () -> Unit,
) {
    val catalog = runtime.appWidgets
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    // The drawer's name for each app, else the one Android has for it (an app with widgets but nothing to open).
    fun appLabel(pkg: String) =
        runtime.androidApps.apps.firstOrNull { it.profile == null && it.component.packageName == pkg }?.label
            ?: runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)
    val groups =
        remember(catalog.providers, pick) {
            catalog.providers
                .filter { pick.packageName == null || it.provider.packageName == pick.packageName }
                .groupBy { it.provider.packageName }
                .map { (pkg, infos) -> Triple(pkg, appLabel(pkg), infos.map { it to catalog.label(it) }.sortedBy { it.second.lowercase() }) }
                .sortedBy { it.second.lowercase() }
        }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) groups else groups.filter { (_, app, infos) -> q in app.lowercase() || infos.any { q in it.second.lowercase() } }
    val title = pick.packageName?.let { pkg -> groups.firstOrNull()?.second ?: appLabel(pkg) }?.let { "$it widgets" } ?: "android widgets"
    OverlayPanel(title, onDismiss, contentWidth = ReadingWidth) {
        if (pick.packageName == null) {
            SearchField(query, { query = it }, onGo = {}, placeholder = "find a widget")
            Spacer(Modifier.height(12.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shown.isEmpty()) {
                item { BasicText(if (groups.isEmpty()) "no app on this phone offers a widget" else "no widget matches", style = Mono.copy(color = Palette.muted)) }
            }
            for ((pkg, app, infos) in shown) {
                // A single app's list says whose it is in its title.
                if (pick.packageName == null) {
                    item(key = pkg) { AppHeader(runtime, pkg, app) }
                }
                items(infos, key = { it.first.provider.flattenToString() }) { (info, label) ->
                    WidgetChoice(info, label, catalog, catalog.item(0, info, page, runtime.widgets.layout).size) {
                        onDismiss()
                        AddWidgetActivity.start(context, info.provider, page)
                    }
                }
            }
        }
    }
}

/** An app's name over its widgets, with its icon as the drawer shows it. */
@Composable
private fun AppHeader(
    runtime: Runtime,
    pkg: String,
    label: String,
) {
    val app = runtime.androidApps.apps.firstOrNull { it.profile == null && it.component.packageName == pkg }
    val icon = app?.let { runtime.androidApps.icons[it.key] }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Image(icon, null, Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
        }
        BasicText(label, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
    }
}

/** One widget to pick: its preview (or its app's icon), its name, the cells it takes and what its app says it is. */
@Composable
private fun WidgetChoice(
    info: AppWidgetProviderInfo,
    label: String,
    catalog: AppWidgetCatalog,
    cells: Pair<Int, Int>,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val maxPx = with(LocalDensity.current) { PreviewSize.roundToPx() }
    val preview by produceState<ImageBitmap?>(null, info) { value = catalog.preview(info, maxPx) }
    val description = remember(info) { if (android.os.Build.VERSION.SDK_INT >= 31) runCatching { info.loadDescription(context)?.toString() }.getOrNull() else null }
    Row(Modifier.card(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(PreviewSize), contentAlignment = Alignment.Center) {
            preview?.let { Image(it, label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(label, style = Mono.copy(fontSize = TextSize.Item))
            BasicText("${cells.first}×${cells.second}", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            if (!description.isNullOrBlank()) BasicText(description, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
    }
}

private val PreviewSize = 96.dp
