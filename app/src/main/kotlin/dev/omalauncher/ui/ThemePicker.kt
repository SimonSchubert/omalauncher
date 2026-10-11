package dev.omalauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.theme.OmarchyTheme
import dev.omalauncher.theme.ThemeDirs
import dev.omalauncher.theme.ThemeDownload
import dev.omalauncher.theme.ThemeInstall
import dev.omalauncher.theme.ThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Omarchy's theme menu: pick a theme, then one of its backgrounds. */
@Composable
fun ThemePicker(
    store: ThemeStore,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Themes added in Linux since the launcher started.
    LaunchedEffect(Unit) { if (store.loaded) store.rescan() }
    var browsing by remember { mutableStateOf(false) }
    var installOpen by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<OmarchyTheme?>(null) }
    if (browsing) {
        ThemeCatalogScreen(store, onDismiss = { browsing = false })
        return
    }
    OverlayPanel("themes", onDismiss) {
        // Style choices don't depend on a theme, so they're offered before themes are downloaded too.
        StyleOption("icons", listOf("monochrome", "colored"), if (LauncherStyle.monochromeIcons) 0 else 1) { store.setMonochromeIcons(it == 0) }
        StyleOption("home apps", listOf("boxed", "icon only"), if (LauncherStyle.bareAppIcons) 1 else 0) { store.setBareAppIcons(it == 1) }
        StyleOption("dock", listOf("shown", "hidden"), if (LauncherStyle.recentApps) 0 else 1) { store.setRecentApps(it == 0) }
        StyleOption("corners", Corners.entries.map { it.label }, LauncherStyle.corners.ordinal) { store.setCorners(Corners.entries[it]) }
        Spacer(Modifier.height(8.dp))
        if (!store.complete) {
            DownloadPrompt(store.download, partial = store.themes.isNotEmpty()) { scope.launch { store.ensureRelease() } }
            if (store.themes.isEmpty()) return@OverlayPanel
            Spacer(Modifier.height(8.dp))
        }
        val current = store.current
        if (current != null) {
            val backgrounds = remember(current) { current.backgrounds() }
            BasicText("background", Modifier.padding(vertical = 4.dp), style = Mono.copy(color = Palette.muted))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(backgrounds, key = { it.path }) { file ->
                    Thumbnail(
                        file,
                        selected = file == store.background,
                        modifier = Modifier.width(120.dp).aspectRatio(16f / 10f),
                        onClick = { scope.launch { store.setBackground(file) } },
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    // Some phones (Android Go) have no live wallpaper picker.
                    .clickable(enabled = !store.liveWallpaperActive) { runCatching { context.startActivity(store.liveWallpaperIntent()) } }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (store.liveWallpaperActive) {
                    // SystemUI defers live-wallpaper color changes until the screen turns off.
                    BasicText(
                        "✓ android wallpaper & system colors follow the theme (system colors update after the screen turns off)",
                        style = Mono.copy(color = Palette.green),
                    )
                } else {
                    BasicText("[ use as android wallpaper + system colors ]", style = Mono.copy(color = Palette.accent))
                }
            }
            if (current.own != null) {
                val source = remember(current) { ThemeDirs.sourceOf(current) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        source?.label ?: "your own theme",
                        Modifier.weight(1f),
                        style = Mono.copy(color = Palette.muted),
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (source != null) BracketAction("update", Palette.accent) { scope.launch { store.update(current) } }
                    BracketAction("remove", Palette.red) { removing = current }
                }
            }
        }
        LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicText("theme", Modifier.weight(1f), style = Mono.copy(color = Palette.muted))
                        BracketAction("browse", Palette.accent) { browsing = true }
                        BracketAction("install", Palette.accent) {
                            store.clearInstallError()
                            installOpen = true
                        }
                    }
                    InstallStatus(store)
                }
            }
            items(store.themes, key = { it.name }) { theme ->
                ThemeCard(theme, selected = theme.name == current?.name) { scope.launch { store.apply(theme) } }
            }
        }
        if (installOpen) {
            TextDialog(
                "install theme",
                "",
                "install",
                hint = "A theme's GitHub repo. It replaces an installed theme of the same name.",
                onDismiss = { installOpen = false },
            ) { url ->
                installOpen = false
                scope.launch { store.install(url) }
            }
        }
        removing?.let { theme ->
            val builtIn = theme.dirs.size > 1
            ConfirmDialog(
                "remove ${theme.title.lowercase()}?",
                if (builtIn) "Your changes to it go; Omarchy's version stays." else "It's deleted from the phone and from Linux.",
                "remove",
                onDismiss = { removing = null },
            ) {
                removing = null
                scope.launch { store.remove(theme) }
            }
        }
    }
}

/** A labeled segmented choice: the picked option fills with the accent color. */
@Composable
private fun StyleOption(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.width(96.dp), style = Mono.copy(color = Palette.muted))
        Row(Modifier.frame(1.dp, Palette.border, radius = 12.dp)) {
            options.forEachIndexed { i, option ->
                val active = i == selected
                BasicText(
                    option,
                    Modifier
                        .background(if (active) Palette.accent else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    style = Mono.copy(color = if (active) Palette.background else Palette.foreground),
                )
            }
        }
    }
}

@Composable
private fun DownloadPrompt(
    state: ThemeDownload,
    /** Some themes are here already (first run fetches only the picked one). */
    partial: Boolean,
    onDownload: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(
            if (partial) {
                "Omarchy's other themes are downloading from github.com/omacom/omarchy (~70 MB)."
            } else {
                "Omarchy's themes aren't installed yet. They're downloaded from github.com/omacom/omarchy (~70 MB)."
            },
            style = Mono,
        )
        when (state) {
            is ThemeDownload.Running -> BasicText("downloading… ${state.megabytes} MB", style = Mono.copy(color = Palette.accent))
            is ThemeDownload.Failed -> {
                BasicText("failed: ${state.message}", style = Mono.copy(color = Palette.red))
                Box(Modifier.clickable(onClick = onDownload).padding(8.dp)) { BasicText("[ retry ]", style = Mono.copy(color = Palette.accent)) }
            }
            ThemeDownload.Idle -> Box(Modifier.clickable(onClick = onDownload).padding(8.dp)) { BasicText("[ download themes ]", style = Mono.copy(color = Palette.accent)) }
        }
    }
}

@Composable
private fun ThemeCard(
    theme: OmarchyTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = theme.colors["background"].toColor()
    Column(
        Modifier
            .frame(2.dp, if (selected) Palette.accent else Palette.border, bg, radius = 12.dp)
            .clickable(onClick = onClick),
    ) {
        Thumbnail(theme.preview, selected = false, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f), onClick = onClick)
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(theme.title, Modifier.weight(1f), style = Mono.copy(color = theme.colors["foreground"].toColor(), fontSize = Mono.fontSize * 0.9f), maxLines = 1)
            for (key in listOf("accent", "red", "green", "yellow")) {
                Box(Modifier.padding(start = 2.dp).size(8.dp).background(theme.colors[key].toColor()))
            }
        }
    }
}

@Composable
private fun Thumbnail(
    file: File,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val image by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, 480, 0)?.asImageBitmap() }
    }
    Box(
        modifier
            .frame(2.dp, if (selected) Palette.accent else Color.Transparent, Palette.surface, radius = 12.dp)
            .clickable(onClick = onClick),
    ) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

private fun String?.toColor(): Color = hexColor(this) ?: Color.Gray

/** How installing a theme from its repo is going; tapping a failure clears it. Nothing while idle. */
@Composable
internal fun InstallStatus(store: ThemeStore) {
    when (val state = store.installing) {
        is ThemeInstall.Running -> BasicText("installing ${state.name}… ${state.megabytes} MB", style = Mono.copy(color = Palette.accent))
        is ThemeInstall.Failed -> BasicText("install failed: ${state.message}", Modifier.clickable { store.clearInstallError() }, style = Mono.copy(color = Palette.red))
        ThemeInstall.Idle -> {}
    }
}
