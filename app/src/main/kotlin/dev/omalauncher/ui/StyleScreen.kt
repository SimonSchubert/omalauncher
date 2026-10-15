package dev.omalauncher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.TerminalFont
import dev.omalauncher.linux.TerminalText
import dev.omalauncher.theme.OmarchyTheme
import dev.omalauncher.theme.Setting
import dev.omalauncher.theme.Style
import dev.omalauncher.theme.StyleStore
import dev.omalauncher.theme.ThemeDirs
import dev.omalauncher.theme.ThemeDownload
import dev.omalauncher.theme.ThemeStore
import dev.omalauncher.theme.Toml
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            val style = runtime.style
            Flag("icons", "monochrome", "colored", style, Style.MONOCHROME_ICONS)
            Flag("home apps", "boxed", "icon only", style, Style.APP_TILES)
            Flag("recent apps", "shown", "hidden", style, Style.RECENT_APPS)
            NumberOption("corners", Corners.entries.map { it.label to it.scale.toDouble() }, Style.CORNER_SCALE, style)
            // Like Hyprland's gaps_in, gaps_out and border_size; the colors are in style.toml.
            Heading("widgets")
            OpacityStepper("opacity", style, Style.WIDGET_OPACITY)
            Stepper("blur", style, Style.WIDGET_BLUR, step = 4.0)
            Stepper("gaps in", style, Style.GAPS_IN)
            Stepper("gaps out", style, Style.GAPS_OUT)
            Stepper("border width", style, Style.TILE_BORDER_WIDTH)
            Heading("screens")
            Stepper("gaps out", style, Style.SCREEN_GAPS_OUT)
            Stepper("border width", style, Style.SCREEN_BORDER_WIDTH)
            // Both bars at once; style.toml can set them apart. Background is the band behind their icons.
            Heading("system bars")
            Flag("background", "shown", "hidden", style, Style.STATUS_BAR.framed, Style.NAVIGATION_BAR.framed)
            Stepper("border width", style, Style.STATUS_BAR.borderWidth, Style.NAVIGATION_BAR.borderWidth)
            OpacityStepper("opacity", style, Style.STATUS_BAR.opacity, Style.NAVIGATION_BAR.opacity)
            if (linux == LinuxState.Installed) {
                TerminalSection(runtime.terminalFont, runtime.terminals, style)
                BasicText(
                    "more settings, like border and bar colors: ~/${StyleStore.GUEST_DIR}/defaults.toml",
                    Modifier.padding(top = 20.dp),
                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                )
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
    // Keyed on the background too, so an image just added shows up; removals bump [removed].
    var removed by remember { mutableStateOf(0) }
    val backgrounds = remember(theme, store.background, removed) { theme.backgrounds() }
    var removing by remember { mutableStateOf<File?>(null) }
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
                // Only the user's own images; the theme's come back with it anyway.
                onLongClick = if (theme.isExtraBackground(file)) ({ removing = file }) else null,
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
    removing?.let { file ->
        ConfirmDialog(
            "remove background?",
            "It's deleted from the phone and from Linux.",
            "remove",
            onDismiss = { removing = null },
        ) {
            removing = null
            scope.launch {
                store.removeBackground(file)
                removed++
            }
        }
    }
}

/** The terminals' font and the size they open at, on top of Android's font size; see [TerminalFont]. */
@Composable
private fun TerminalSection(
    font: TerminalFont,
    tiles: TerminalTiles,
    style: StyleStore,
) {
    LaunchedEffect(Unit) { font.refreshFamilies() }
    Heading("terminal")
    FontOption(font.families, font.family) { family -> style.update { it.with(Style.TERMINAL_FONT, family) } }
    OptionHint("terminals opened from now on · more with pacman, like ttf-cascadia-code", Modifier.padding(bottom = 4.dp))
    NumberOption("text", TerminalText.entries.map { it.label to it.em.toDouble() }, Style.TERMINAL_TEXT, style, unit = " dp")
    OpacityStepper("opacity", style, Style.TERMINAL_OPACITY)
    OptionHint("see-through also needs foot's alpha, under [colors] in ~/.config/foot/foot.ini", Modifier.padding(bottom = 4.dp))
    // In portrait, where columns run short; landscape has plenty.
    val metrics = LocalContext.current.resources.displayMetrics
    val columns = font.columns(minOf(metrics.widthPixels, metrics.heightPixels))
    OptionHint("${TerminalFont.format(font.size).removeSuffix(".0")} pt · $columns columns in portrait", Modifier.padding(top = 2.dp))
    TerminalPreview(font, tiles)
}

/**
 * A real foot in the chosen [TerminalFont], as terminals will draw it. foot
 * keeps the family it started with, so a change starts another.
 */
@Composable
private fun TerminalPreview(
    font: TerminalFont,
    tiles: TerminalTiles,
) {
    val term = remember(font.family, font.size) { tiles.create(PREVIEW, font.size) }
    DisposableEffect(term) { onDispose { term.close() } }
    TerminalView(
        term,
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .height(160.dp)
            // Inset within the frame so its corners don't cut into foot's pixels.
            .frame(2.dp, Palette.border, Palette.background, radius = 12.dp)
            .padding(4.dp),
        interactive = false,
    )
}

/** What the preview prints, without a cursor, then waits for it to be closed. */
private val PREVIEW =
    """
    printf '\033[?25l\033[32m~\033[0m \033[34m❯\033[0m ls\n\033[1;34mDocuments\033[0m  \033[1;34mProjects\033[0m  notes.md  \033[32mrun.sh\033[0m\n\033[32m~\033[0m \033[34m❯\033[0m cat greet.py\n\033[35mdef\033[0m \033[34mgreet\033[0m(name):  \033[90m# 0O 1lI {} [] <=\033[0m\n    \033[35mreturn\033[0m \033[32mf"hi {name}"\033[0m\n'
    exec sleep infinity
    """.trimIndent()

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
        OptionLabel("font", Modifier.padding(top = 8.dp))
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

/** The column option names take, so the controls beside them line up. */
private val LabelWidth = 104.dp

/** An option's name; a long one wraps rather than push its control out of line with the others. */
@Composable
private fun OptionLabel(
    text: String,
    modifier: Modifier = Modifier,
) = BasicText(text, modifier.width(LabelWidth).padding(end = 8.dp), style = Mono.copy(color = Palette.muted))

/** A note under an option, lined up with its control. */
@Composable
private fun OptionHint(
    text: String,
    modifier: Modifier = Modifier,
) = BasicText(text, modifier.padding(start = LabelWidth), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))

/**
 * [Style] numbers stepped together by 1 within their range, from the first one's value; a value set
 * in style.toml between steps snaps to the next one. Reset shows once one is off its default.
 */
@Composable
private fun Stepper(
    label: String,
    style: StyleStore,
    vararg settings: Setting.Number,
    step: Double = 1.0,
    format: (Double) -> String = { Toml.format(it) + " dp" },
) {
    val all = settings.toList()
    val setting = all.first()
    val value = style.style[setting]
    // Snapped to the step, so a value set in style.toml between steps lands back on one.
    fun set(v: Double) = style.update { it.with(all) { s -> (Math.round(v / step) * step).coerceIn(s.range) } }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OptionLabel(label)
        Row(Modifier.frame(1.dp, Palette.border, radius = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val down = value > setting.range.start
            val up = value < setting.range.endInclusive
            BasicText(
                "−",
                Modifier.clickable(enabled = down) { set(ceil(value / step - 1) * step) }.padding(horizontal = 16.dp, vertical = 8.dp),
                style = Mono.copy(color = if (down) Palette.foreground else Palette.muted),
            )
            BasicText(format(value), Modifier.widthIn(min = 64.dp), style = Mono.copy(textAlign = TextAlign.Center), softWrap = false)
            BasicText(
                "+",
                Modifier.clickable(enabled = up) { set(floor(value / step + 1) * step) }.padding(horizontal = 16.dp, vertical = 8.dp),
                style = Mono.copy(color = if (up) Palette.foreground else Palette.muted),
            )
        }
        if (all.any { style.style[it] != it.default }) BracketAction("reset", Palette.muted) { style.update { it.with(all) { s -> s.default } } }
    }
    if (style.style.common(all) == null) OptionHint("they differ in style.toml; a step sets them all", Modifier.padding(bottom = 4.dp))
}

/** A 0–1 [Style] number in 5% steps, shown as a percentage: how much a surface covers the wallpaper. */
@Composable
private fun OpacityStepper(
    label: String,
    style: StyleStore,
    vararg settings: Setting.Number,
) = Stepper(label, style, *settings, step = 0.05, format = { "${Math.round(it * 100)}%" })

/** [Style] flags as a choice of two, [on] first, set together. */
@Composable
private fun Flag(
    label: String,
    on: String,
    off: String,
    style: StyleStore,
    vararg settings: Setting.Flag,
) {
    val all = settings.toList()
    val selected =
        when (style.style.common(all)) {
            true -> 0
            false -> 1
            null -> -1
        }
    StyleOption(label, listOf(on, off), selected, "they differ") { i -> style.update { it.with(all) { i == 0 } } }
}

/** A [Style] number as named [presets]; a value set in style.toml between them shows as custom. */
@Composable
private fun NumberOption(
    label: String,
    presets: List<Pair<String, Double>>,
    setting: Setting.Number,
    style: StyleStore,
    unit: String = "",
) {
    val value = style.style[setting]
    StyleOption(label, presets.map { it.first }, presets.indexOfFirst { it.second == value }, Toml.format(value) + unit) { i ->
        style.update { it.with(setting, presets[i].second) }
    }
}

/**
 * A labeled segmented choice: the picked option fills with the accent color.
 * None is picked for a value set in style.toml outside the options; [custom] says what it is.
 */
@Composable
private fun StyleOption(
    label: String,
    options: List<String>,
    selected: Int,
    custom: String? = null,
    onSelect: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OptionLabel(label)
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
    if (selected < 0 && custom != null) OptionHint("custom: $custom, from style.toml", Modifier.padding(bottom = 4.dp))
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
    onLongClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val image by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, 480, 0)?.asImageBitmap() }
    }
    Box(
        modifier
            .frame(2.dp, if (selected) Palette.accent else Color.Transparent, Palette.surface, radius = 12.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick =
                    onLongClick?.let {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            it()
                        }
                    },
            ),
    ) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

private fun String?.toColor(): Color = hexColor(this) ?: Color.Gray
