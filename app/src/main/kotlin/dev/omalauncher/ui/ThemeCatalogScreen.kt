package dev.omalauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.openUrl
import dev.omalauncher.theme.CatalogTheme
import dev.omalauncher.theme.ThemeCatalog
import dev.omalauncher.theme.ThemeDirs
import dev.omalauncher.theme.ThemeInstall
import dev.omalauncher.theme.ThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The community themes on omarchy.org/themes, installed from their repos with one tap. */
@Composable
fun ThemeCatalogScreen(
    store: ThemeStore,
    onDismiss: () -> Unit,
) {
    val catalog = store.catalog
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf<CatalogTheme?>(null) }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(catalog) { catalog.refresh() }
    RescanThemes(store)
    // Which repos are installed, by github.com/owner/repo; read from the themes' source markers.
    val installedFrom by produceState(emptyMap<String, String>(), store.themes) {
        value =
            withContext(Dispatchers.IO) {
                store.themes.mapNotNull { t -> ThemeDirs.sourceOf(t)?.let { it.label.lowercase() to t.name } }.toMap()
            }
    }
    OverlayPanel(
        "community themes",
        onDismiss,
        actions = {
            RefreshButton(catalog.loading) { scope.launch { catalog.refresh() } }
        },
    ) {
        val q = query.trim().lowercase()
        val shown = catalog.entries.filter { q.isEmpty() || q in it.title.lowercase() || q in it.repo.label.lowercase() }
        SearchField(query, { query = it }, onGo = { keyboard?.hide() }, placeholder = "search ${catalog.entries.size} themes")
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    BasicText(
                        "made by the community, listed on omarchy.org/themes · installed from GitHub",
                        Modifier.clickable { context.openUrl(ThemeCatalog.PAGE) },
                        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                    )
                    InstallStatus(store)
                    when {
                        catalog.loading && catalog.entries.isEmpty() -> BasicText("loading…", style = Mono.copy(color = Palette.muted))
                        catalog.error != null && catalog.entries.isEmpty() -> BasicText(catalog.error.orEmpty(), style = Mono.copy(color = Palette.red))
                        catalog.entries.isNotEmpty() && shown.isEmpty() -> BasicText("nothing matches \"${query.trim()}\"", style = Mono.copy(color = Palette.muted))
                    }
                }
            }
            items(shown, key = { it.repo.label.lowercase() }) { theme ->
                val installedAs = installedFrom[theme.repo.label.lowercase()]
                val running = (store.installing as? ThemeInstall.Running)?.name == theme.repo.themeName
                CatalogCard(
                    store,
                    theme,
                    status =
                        when {
                            running -> "installing…" to Palette.accent
                            installedAs != null && installedAs == store.current?.name -> "✓ in use" to Palette.green
                            installedAs != null -> "✓ installed" to Palette.green
                            else -> theme.repo.label.removePrefix("github.com/") to Palette.muted
                        },
                ) {
                    when {
                        // Installed: tapping applies it, like a theme in the theme menu.
                        installedAs != null -> store.themes.firstOrNull { it.name == installedAs }?.let { scope.launch { store.apply(it) } }
                        store.installing !is ThemeInstall.Running -> confirming = theme
                    }
                }
            }
        }
    }
    confirming?.let { theme ->
        val name = theme.repo.themeName
        val existing = store.themes.firstOrNull { it.name == name }
        val replaces =
            when {
                existing == null -> ""
                existing.own != null -> "\n\nIt replaces your ${existing.title}."
                else -> "\n\nIt goes over Omarchy's ${existing.title}; removing it brings that back."
            }
        ConfirmDialog(
            "install ${theme.title.lowercase()}?",
            "From ${theme.repo.label}. Linux gets its colors and wallpapers, not its scripts or terminal configs.$replaces",
            "install",
            onDismiss = { confirming = null },
            confirmColor = Palette.accent,
        ) {
            confirming = null
            store.clearInstallError()
            scope.launch { store.install(theme.repo.url) }
        }
    }
}

@Composable
private fun CatalogCard(
    store: ThemeStore,
    theme: CatalogTheme,
    status: Pair<String, Color>,
    onClick: () -> Unit,
) {
    // Decoded per card, not kept: 146 screenshots in memory would be a lot.
    val image by produceState<ImageBitmap?>(null, theme.image) {
        val file = store.catalog.image(theme) ?: return@produceState
        value = withContext(Dispatchers.IO) { decodeSampled(file, 400, 0)?.asImageBitmap() }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .frame(1.dp, Palette.border, radius = 12.dp)
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(LauncherStyle.shape(8.dp)).background(Palette.surface), contentAlignment = Alignment.Center) {
            image?.let { Image(it, theme.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        BasicText(theme.title, style = Mono.copy(fontSize = TextSize.Body), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(status.first, style = Mono.copy(color = status.second, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** How installing a theme from its repo is going; tapping a failure clears it. Nothing while idle. */
@Composable
internal fun InstallStatus(store: ThemeStore) {
    when (val state = store.installing) {
        is ThemeInstall.Running -> BasicText("installing ${state.name}… ${state.megabytes} MB", style = Mono.copy(color = Palette.accent))
        is ThemeInstall.Failed -> BasicText("install failed: ${state.message}", Modifier.clickable { store.clearInstallError() }, style = Mono.copy(color = Palette.red))
        ThemeInstall.Idle -> {}
    }
}

/** Picks up themes added or removed in Linux since the launcher started, when the calling screen opens. */
@Composable
internal fun RescanThemes(store: ThemeStore) {
    LaunchedEffect(store) { if (store.loaded) store.rescan() }
}
