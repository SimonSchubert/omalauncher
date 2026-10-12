package dev.omalauncher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.TerminalFont
import dev.omalauncher.linux.TerminalText
import dev.omalauncher.theme.OmarchyTheme
import dev.omalauncher.theme.ThemeDirs
import dev.omalauncher.theme.ThemeDownload
import dev.omalauncher.theme.ThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Omarchy's Style menu: the theme, its background, how the home screen
 * draws itself and, with Linux, the terminal's text size.
 */
@Composable
fun StyleScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
    /** Opens the community themes over this screen. */
    onBrowse: () -> Unit,
) {
    val store = runtime.themes
    val scope = rememberCoroutineScope()
    RescanThemes(store)
    var installOpen by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<OmarchyTheme?>(null) }
    val linux by runtime.bootstrap.state.collectAsState()
    OverlayPanel("style", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ThemeSection(
                store,
                onBrowse = onBrowse,
                onInstall = {
                    store.clearInstallError()
                    installOpen = true
                },
                onRemove = { removing = it },
            )
            store.current?.let { BackgroundSection(store, it) }
            // Style choices don't depend on a theme, so they're offered before themes are downloaded too.
            Heading("home screen")
            StyleOption("icons", listOf("monochrome", "colored"), if (LauncherStyle.monochromeIcons) 0 else 1) { store.setMonochromeIcons(it == 0) }
            StyleOption("home apps", listOf("boxed", "icon only"), if (LauncherStyle.bareAppIcons) 1 else 0) { store.setBareAppIcons(it == 1) }
            StyleOption("recent apps", listOf("shown", "hidden"), if (LauncherStyle.recentApps) 0 else 1) { store.setRecentApps(it == 0) }
            StyleOption("keyboard", listOf("opens with apps", "on tap"), if (LauncherStyle.drawerKeyboard) 0 else 1) { store.setDrawerKeyboard(it == 0) }
            StyleOption("drawer", listOf("all apps", "not on home"), if (LauncherStyle.homeAppsInDrawer) 0 else 1) { store.setHomeAppsInDrawer(it == 0) }
            StyleOption("corners", Corners.entries.map { it.label }, LauncherStyle.corners.ordinal) { store.setCorners(Corners.entries[it]) }
            if (linux == LinuxState.Installed) TerminalSection(runtime.terminalFont)
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

/** The installed themes in a row, starting at the current one; browse has Omarchy's whole catalog. */
@Composable
private fun ThemeSection(
    store: ThemeStore,
    onBrowse: () -> Unit,
    onInstall: () -> Unit,
    onRemove: (OmarchyTheme) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val current = store.current
    Heading("theme") {
        BracketAction("browse", Palette.accent, onBrowse)
        BracketAction("install", Palette.accent, onInstall)
    }
    InstallStatus(store)
    if (!store.complete) {
        DownloadPrompt(store.download, partial = store.themes.isNotEmpty()) { scope.launch { store.ensureRelease() } }
        Spacer(Modifier.height(8.dp))
    }
    if (store.themes.isNotEmpty()) {
        val start = store.themes.indexOfFirst { it.name == current?.name }.coerceAtLeast(0)
        LazyRow(
            // Scrolls under the panel's padding to its frame, starting aligned with the heading.
            Modifier.bleed(PanelPadding),
            state = rememberLazyListState(start),
            contentPadding = PaddingValues(horizontal = PanelPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(store.themes, key = { it.name }) { theme ->
                ThemeCard(theme, selected = theme.name == current?.name, Modifier.width(160.dp)) { scope.launch { store.apply(theme) } }
            }
        }
    }
    if (current?.own != null) {
        val source = remember(current) { ThemeDirs.sourceOf(current) }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                source?.label ?: "your own theme",
                Modifier.weight(1f),
                style = Mono.copy(color = Palette.muted),
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (source != null) BracketAction("update", Palette.accent) { scope.launch { store.update(current) } }
            BracketAction("remove", Palette.red) { onRemove(current) }
        }
    }
}

/** [theme]'s backgrounds, one of the user's own images, and handing it all to Android. */
@Composable
private fun BackgroundSection(
    store: ThemeStore,
    theme: OmarchyTheme,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Keyed on the background too, so an image just added shows up.
    val backgrounds = remember(theme, store.background) { theme.backgrounds() }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { store.addBackground(uri) }
    }
    Heading("background")
    LazyRow(Modifier.bleed(PanelPadding), contentPadding = PaddingValues(horizontal = PanelPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(backgrounds, key = { it.path }) { file ->
            Thumbnail(
                file,
                selected = file == store.background,
                modifier = Modifier.width(120.dp).aspectRatio(16f / 10f),
                onClick = { scope.launch { store.setBackground(file) } },
            )
        }
        // Omarchy's Install > Style > Background, with Android's image picker instead of a folder.
        item(key = "add") {
            Box(
                Modifier
                    .width(120.dp)
                    .aspectRatio(16f / 10f)
                    .frame(2.dp, Palette.border, Palette.surface, radius = 12.dp)
                    .clickable { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText("[ + image ]", style = Mono.copy(color = Palette.accent))
            }
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
}

/** The terminals' font and the size they open at, on top of Android's font size; see [TerminalFont]. */
@Composable
private fun TerminalSection(font: TerminalFont) {
    LaunchedEffect(Unit) { font.refreshFamilies() }
    Heading("terminal")
    FontOption(font.families, font.family, font::select)
    BasicText(
        "terminals opened from now on · more with pacman, like ttf-cascadia-code",
        Modifier.padding(start = 104.dp, bottom = 4.dp),
        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
    )
    StyleOption("text", TerminalText.entries.map { it.label }, font.text.ordinal) { font.select(TerminalText.entries[it]) }
    // In portrait, where columns run short; landscape has plenty.
    val metrics = LocalContext.current.resources.displayMetrics
    val columns = font.columns(minOf(metrics.widthPixels, metrics.heightPixels))
    BasicText(
        "${TerminalFont.format(font.size).removeSuffix(".0")} pt · $columns columns in portrait",
        Modifier.padding(start = 104.dp, top = 2.dp),
        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
    )
}

/** A section's title, set apart from the muted labels of the options under it by the accent color, as the weather screen sets its sections apart; [actions] go at the far end. */
@Composable
private fun Heading(
    text: String,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(text, Modifier.weight(1f), style = Mono.copy(color = Palette.accent), softWrap = false)
        actions()
    }
}

/** Font names run long: each is a chip of its own, and they wrap rather than scroll out of sight. */
@Composable
private fun FontOption(
    families: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        BasicText("font", Modifier.widthIn(min = 104.dp).padding(top = 8.dp, end = 8.dp), style = Mono.copy(color = Palette.muted), softWrap = false)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (family in families) {
                val active = family == selected
                BasicText(
                    family,
                    Modifier
                        .frame(1.dp, Palette.border, if (active) Palette.accent else Color.Transparent, radius = 12.dp)
                        .clickable { onSelect(family) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    style = Mono.copy(color = if (active) Palette.background else Palette.foreground),
                    softWrap = false,
                )
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
        BasicText(label, Modifier.widthIn(min = 104.dp).padding(end = 8.dp), style = Mono.copy(color = Palette.muted), softWrap = false)
        // At a large Android font size the options may not fit: they scroll rather than wrap.
        Box(Modifier.horizontalScroll(rememberScrollState())) {
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
                        softWrap = false,
                    )
                }
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
                BracketAction("retry", Palette.accent, onDownload)
            }
            ThemeDownload.Idle -> BracketAction("download themes", Palette.accent, onDownload)
        }
    }
}

@Composable
private fun ThemeCard(
    theme: OmarchyTheme,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val bg = theme.colors["background"].toColor()
    Column(
        modifier
            .frame(2.dp, if (selected) Palette.accent else Palette.border, bg, radius = 12.dp)
            .clickable(onClick = onClick),
    ) {
        Thumbnail(theme.preview, selected = false, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f), onClick = onClick)
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(theme.title, Modifier.weight(1f), style = Mono.copy(color = theme.colors["foreground"].toColor(), fontSize = TextSize.Small), maxLines = 1)
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
