package dev.omalauncher.ui.settings

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.R
import dev.omalauncher.system.openUrl
import dev.omalauncher.theme.CatalogDetails
import dev.omalauncher.theme.CatalogTheme
import dev.omalauncher.theme.OmarchyTheme
import dev.omalauncher.theme.ThemeCatalog
import dev.omalauncher.theme.ThemeDirs
import dev.omalauncher.theme.ThemeError
import dev.omalauncher.theme.ThemeInstall
import dev.omalauncher.theme.ThemeStore
import dev.omalauncher.ui.BracketAction
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.LocalPanelBack
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.OverlayPanel
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.RefreshButton
import dev.omalauncher.ui.TextSize
import dev.omalauncher.ui.ZoomableImage
import dev.omalauncher.ui.decodeForScreen
import dev.omalauncher.ui.decodeSampled
import dev.omalauncher.ui.frame
import dev.omalauncher.ui.hexColor
import dev.omalauncher.ui.home.SearchField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The community themes on omarchy.org/themes; a tap on one opens its preview, to install it from its repo. */
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
    // The theme whose screenshot is open in the panel, in place of the grid.
    var viewing by remember { mutableStateOf<CatalogTheme?>(null) }
    // Out here, so the grid is where it was after a screenshot.
    val grid = rememberLazyGridState()
    BackHandler(onBack = onDismiss)
    BackHandler(enabled = viewing != null) { viewing = null }
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
        stringResource(R.string.store_theme_title),
        onDismiss,
        onBack = if (viewing != null) ({ viewing = null }) else LocalPanelBack.current,
        actions = {
            if (viewing == null) RefreshButton(catalog.loading) { scope.launch { catalog.refresh() } }
        },
    ) {
        viewing?.let { theme ->
            val installedAs = installedFrom[theme.repo.label.lowercase()]
            ThemePreview(
                store,
                theme,
                catalogStatus(store, theme, installedAs),
                installed = installedAs?.let { n -> store.themes.firstOrNull { it.name == n } },
                onInstall = {
                    store.clearInstallError()
                    scope.launch { store.install(theme.repo.url) }
                },
            )
            return@OverlayPanel
        }
        val q = query.trim().lowercase()
        val shown = catalog.entries.filter { q.isEmpty() || q in it.title.lowercase() || q in it.repo.label.lowercase() }
        SearchField(query, { query = it }, onGo = { keyboard?.hide() }, placeholder = stringResource(R.string.store_theme_search, catalog.entries.size))
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            state = grid,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    BasicText(
                        stringResource(R.string.store_theme_header),
                        Modifier.clickable { context.openUrl(ThemeCatalog.PAGE) },
                        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                    )
                    InstallStatus(store)
                    when {
                        catalog.loading && catalog.entries.isEmpty() -> BasicText(stringResource(R.string.store_loading), style = Mono.copy(color = Palette.muted))
                        catalog.error != null && catalog.entries.isEmpty() -> BasicText(catalog.error?.let { stringResource(R.string.store_theme_catalog_failed, it.text()) }.orEmpty(), style = Mono.copy(color = Palette.red))
                        catalog.entries.isNotEmpty() && shown.isEmpty() -> BasicText(stringResource(R.string.store_no_match, query.trim()), style = Mono.copy(color = Palette.muted))
                    }
                }
            }
            items(shown, key = { it.repo.label.lowercase() }) { theme ->
                val installedAs = installedFrom[theme.repo.label.lowercase()]
                CatalogCard(
                    store,
                    theme,
                    status = catalogStatus(store, theme, installedAs),
                ) { viewing = theme }
            }
        }
    }
}

/** What [theme] is here, in words and their color: installing, in use, installed, or the repo it would come from. */
@Composable
private fun catalogStatus(
    store: ThemeStore,
    theme: CatalogTheme,
    installedAs: String?,
): Pair<String, Color> =
    when {
        (store.installing as? ThemeInstall.Running)?.name == theme.repo.themeName -> stringResource(R.string.store_installing) to Palette.accent
        installedAs != null && installedAs == store.current?.name -> stringResource(R.string.store_theme_in_use) to Palette.green
        installedAs != null -> stringResource(R.string.store_installed) to Palette.green
        else -> theme.repo.label.removePrefix("github.com/") to Palette.muted
    }

/**
 * [theme]'s screenshot as large as the panel allows, to zoom into, or one of its wallpapers picked from the row under
 * it, with its colors, its repo and what it can do: install it with [onInstall], saying first what that brings and
 * what it replaces, or use the [installed] one. What comes from GitHub has its space kept from the start, so nothing
 * moves when it arrives.
 */
@Composable
private fun ColumnScope.ThemePreview(
    store: ThemeStore,
    theme: CatalogTheme,
    status: Pair<String, Color>,
    installed: OmarchyTheme?,
    onInstall: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val details by produceState<CatalogDetails?>(null, theme) { value = store.catalog.details(theme) }
    // The wallpaper shown large, by its path in the repo; null for the screenshot.
    var picked by remember(theme) { mutableStateOf<String?>(null) }
    // At screen size, not the card's: zoomed in, it has the detail to show.
    val image by produceState<ImageBitmap?>(null, theme, picked) {
        value = null
        val file = (picked?.let { store.catalog.wallpaper(theme, it) } ?: if (picked == null) store.catalog.image(theme) else null) ?: return@produceState
        value = withContext(Dispatchers.IO) { runCatching { decodeForScreen(file, context)?.asImageBitmap() }.getOrNull() }
    }
    BasicText(theme.title, style = Mono.copy(fontSize = TextSize.Title), maxLines = 1, overflow = TextOverflow.Ellipsis)
    BasicText(status.first, style = Mono.copy(color = status.second, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
    InstallStatus(store)
    Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .aspectRatio(image?.let { it.width.toFloat() / it.height } ?: (16f / 9f))
                .clip(LauncherStyle.shape(12.dp))
                .background(Palette.surface),
        ) {
            image?.let { ZoomableImage(it, theme.title, Modifier.fillMaxSize()) }
        }
    }
    PictureRow(store, theme, details?.wallpapers.orEmpty(), picked) { picked = it }
    ThemeDetails(details)
    // What installing brings, and which theme of the same name it replaces or goes over: said here, as [ install ] doesn't ask.
    if (installed == null) {
        val existing = store.themes.firstOrNull { it.name == theme.repo.themeName }
        val note =
            when {
                existing == null -> stringResource(R.string.store_theme_install_message, theme.repo.label)
                existing.own != null -> stringResource(R.string.store_theme_install_message_replaces, theme.repo.label, existing.title)
                else -> stringResource(R.string.store_theme_install_message_over, theme.repo.label, existing.title)
            }
        BasicText(note, Modifier.padding(bottom = 8.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        BracketAction(stringResource(R.string.store_theme_github), Palette.muted) { context.openUrl("https://${theme.repo.label}") }
        when {
            // Installing shows above, and one in use says so.
            store.installing is ThemeInstall.Running || (installed != null && installed.name == store.current?.name) -> {}
            installed != null -> BracketAction(stringResource(R.string.store_theme_use), Palette.accent) { scope.launch { store.apply(installed) } }
            else -> BracketAction(stringResource(R.string.store_install), Palette.accent, onClick = onInstall)
        }
    }
}

/** The screenshot and then [wallpapers] as small pictures, [picked] one outlined; a tap shows one large. Always its height. */
@Composable
private fun PictureRow(
    store: ThemeStore,
    theme: CatalogTheme,
    wallpapers: List<String>,
    picked: String?,
    onPick: (String?) -> Unit,
) {
    LazyRow(Modifier.fillMaxWidth().height(THUMB_HEIGHT), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "screenshot") { Thumb(picked == null, { store.catalog.image(theme) }) { onPick(null) } }
        items(wallpapers, key = { it }) { path -> Thumb(picked == path, { store.catalog.wallpaper(theme, path) }) { onPick(path) } }
    }
}

private val THUMB_HEIGHT = 56.dp

/** A small picture from [file], decoded when it comes into view; outlined in the accent when it's the one shown. */
@Composable
private fun Thumb(
    selected: Boolean,
    file: suspend () -> File?,
    onClick: () -> Unit,
) {
    val image by produceState<ImageBitmap?>(null) {
        val f = file() ?: return@produceState
        value = withContext(Dispatchers.IO) { runCatching { decodeSampled(f, 200, 0)?.asImageBitmap() }.getOrNull() }
    }
    Box(
        Modifier
            .height(THUMB_HEIGHT)
            .aspectRatio(16f / 9f)
            .frame(if (selected) 2.dp else 1.dp, if (selected) Palette.accent else Palette.border, fill = Palette.surface, radius = 8.dp)
            .clip(LauncherStyle.shape(8.dp))
            .clickable(onClick = onClick),
    ) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/**
 * A catalog theme's terminal colors on its background, and whether it's light or dark, how many wallpapers it has and
 * about how much installing it downloads; what GitHub didn't tell is left out. Takes the same space before [details]
 * are in, and when they have no colors.
 */
@Composable
private fun ThemeDetails(details: CatalogDetails?) {
    val context = LocalContext.current
    val colors = details?.colors
    val background = hexColor(colors?.get("background"))
    // color0 is usually the background itself, which the strip already shows around them.
    val terminal = colors?.let { c -> (0..15).mapNotNull { hexColor(c["color$it"]) } }?.takeIf { it.size == 16 }?.let { if (it[0] == background) it.drop(1) else it }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .then(if (terminal != null || details == null) Modifier.frame(1.dp, Palette.border, fill = background ?: Palette.surface, radius = 8.dp) else Modifier)
            .padding(6.dp)
            .height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Blank swatches while loading, as many as most themes show.
        (terminal ?: if (details == null) List(15) { Palette.border } else emptyList())
            .forEach { Box(Modifier.weight(1f).fillMaxHeight().clip(LauncherStyle.shape(3.dp)).background(it)) }
    }
    val words =
        listOfNotNull(
            colors?.let { stringResource(if (details.isLight) R.string.store_theme_light else R.string.store_theme_dark) },
            details?.wallpapers?.size?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.store_theme_wallpapers, it, it) },
            details?.bytes?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
        )
    // A space while there's nothing, so the line keeps its height.
    BasicText(words.joinToString(" · ").ifEmpty { " " }, Modifier.padding(top = 4.dp, bottom = 8.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(LauncherStyle.shape(8.dp))
                .background(Palette.surface),
            contentAlignment = Alignment.Center,
        ) {
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
        is ThemeInstall.Running -> BasicText(stringResource(R.string.store_theme_installing, state.name, state.megabytes), style = Mono.copy(color = Palette.accent))
        is ThemeInstall.Failed -> BasicText(stringResource(R.string.store_install_failed, state.error.text()), Modifier.clickable { store.clearInstallError() }, style = Mono.copy(color = Palette.red))
        ThemeInstall.Idle -> {}
    }
}

/** What [ThemeError] says, in words. */
@Composable
private fun ThemeError.text() = reason?.let { if (detail.isEmpty()) stringResource(it) else stringResource(it, detail) } ?: detail

/** Picks up themes added or removed in Linux since the launcher started, when the calling screen opens. */
@Composable
internal fun RescanThemes(store: ThemeStore) {
    LaunchedEffect(store) { if (store.loaded) store.rescan() }
}
