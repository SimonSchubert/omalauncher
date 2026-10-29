package dev.omalauncher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LauncherFont
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
import dev.omalauncher.widgets.WidgetEngine
import dev.omalauncher.widgets.editWidgetCommand
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
    /** Opens the agent on keybar.owl. */
    onAgent: (title: String, command: (bin: String) -> String) -> Unit,
) {
    val store = runtime.themes
    val scope = rememberCoroutineScope()
    RescanThemes(store)
    var installOpen by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<OmarchyTheme?>(null) }
    val linux by runtime.bootstrap.state.collectAsState()
    OverlayPanel(stringResource(R.string.style_title), onDismiss) {
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
            Heading(R.string.style_home_screen)
            val style = runtime.style
            Flag(R.string.style_icons, R.string.style_monochrome, R.string.style_colored, style, Style.MONOCHROME_ICONS)
            Flag(R.string.style_home_apps, R.string.style_boxed, R.string.style_icon_only, style, Style.APP_TILES)
            Flag(R.string.style_recent_apps, R.string.style_shown, R.string.style_hidden, style, Style.RECENT_APPS)
            Flag(R.string.style_empty_page_left, R.string.style_shown, R.string.style_hidden, style, Style.EMPTY_PAGE_LEFT)
            Flag(R.string.style_empty_page_right, R.string.style_shown, R.string.style_hidden, style, Style.EMPTY_PAGE_RIGHT)
            CountOption(R.string.style_columns, Style.COLUMNS, style)
            Flag(R.string.style_pages, R.string.style_pages_one, R.string.style_pages_auto, style, Style.FORCE_SINGLE_PAGE)
            NumberOption(R.string.style_corners, Corners.entries.map { it.label to it.scale.toDouble() }, Style.CORNER_SCALE, style)
            HomePreview(runtime)
            // Like Hyprland's gaps_in, gaps_out and border_size; the colors are in style.toml.
            Heading(R.string.style_widgets)
            Stepper(R.string.style_gaps_in, style, Style.GAPS_IN)
            Stepper(R.string.style_gaps_out, style, Style.GAPS_OUT)
            Stepper(R.string.style_border_width, style, Style.TILE_BORDER_WIDTH)
            NumberSlider(R.string.style_opacity, style, Style.TILE_OPACITY)
            Flag(R.string.style_frosted_glass, R.string.style_on, R.string.style_off, style, Style.TILE_FROST)
            WidgetPreview()
            Heading(R.string.style_screens)
            Stepper(R.string.style_gaps_out, style, Style.SCREEN_GAPS_OUT)
            Stepper(R.string.style_border_width, style, Style.SCREEN_BORDER_WIDTH)
            // keybar.owl reads these as gapsOut and borderWidth.
            Heading(R.string.style_keybar)
            KeybarOption(runtime, onAgent)
            Stepper(R.string.style_gaps_out, style, Style.KEYBAR_GAPS_OUT)
            Stepper(R.string.style_border_width, style, Style.KEYBAR_BORDER_WIDTH)
            // Both bars at once; style.toml can set them apart. Background is the band behind their icons.
            Heading(R.string.style_system_bars)
            Flag(R.string.style_status_bar, R.string.style_shown, R.string.style_hidden, style, Style.STATUS_BAR_SHOWN)
            Flag(R.string.style_bars_background, R.string.style_shown, R.string.style_hidden, style, Style.STATUS_BAR.framed, Style.NAVIGATION_BAR.framed)
            Stepper(R.string.style_border_width, style, Style.STATUS_BAR.borderWidth, Style.NAVIGATION_BAR.borderWidth)
            if (linux == LinuxState.Installed) {
                // Linux's fonts, which the terminal section lists.
                Heading(R.string.style_launcher_widgets)
                val fonts = listOf(Style.SYSTEM_FONT) + runtime.terminalFont.families
                val systemFont = stringResource(R.string.style_system_font)
                FontOption(fonts, style.style[Style.FONT], label = { if (it == Style.SYSTEM_FONT) systemFont else it }) { family ->
                    style.update { it.with(Style.FONT, family) }
                }
                TerminalSection(runtime.terminalFont, runtime.launcherFont, style)
                BasicText(
                    stringResource(R.string.style_more_settings, "~/${StyleStore.GUEST_DIR}/defaults.toml"),
                    Modifier.padding(top = 20.dp),
                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                )
            }
        }
        if (installOpen) {
            TextDialog(
                stringResource(R.string.style_install_theme),
                "",
                stringResource(R.string.style_install),
                hint = stringResource(R.string.style_install_hint),
                onDismiss = { installOpen = false },
            ) { url ->
                installOpen = false
                scope.launch { store.install(url) }
            }
        }
        removing?.let { theme ->
            val builtIn = theme.dirs.size > 1
            ConfirmDialog(
                stringResource(R.string.style_remove_theme_title, theme.title.lowercase()),
                stringResource(if (builtIn) R.string.style_remove_theme_builtin else R.string.style_remove_deleted),
                stringResource(R.string.style_remove),
                onDismiss = { removing = null },
            ) {
                removing = null
                scope.launch { store.remove(theme) }
            }
        }
    }
}

/**
 * The terminal key bar over the keyboard: keybar.owl, as stock, edited, broken or removed, with a dropdown to start the
 * agent on it or put the stock keys back. Here because nothing else says the bar is a file of the user's; removed, the
 * stock keys show, and back to stock is the way to have the file again.
 */
@Composable
private fun KeybarOption(
    runtime: Runtime,
    onAgent: (title: String, command: (bin: String) -> String) -> Unit,
) {
    val spec = runtime.widgets.specs.firstOrNull { it.file.name == WidgetEngine.KEYBAR }
    val file = File(runtime.widgets.dir, WidgetEngine.KEYBAR)
    val stockFile = File(runtime.widgets.dir, WidgetEngine.STOCK_KEYBAR)
    // Whether it's still the bar as shipped; read again when the file reloads, which makes a new spec.
    val stock = remember(spec) { spec != null && runCatching { spec.file.readBytes().contentEquals(stockFile.readBytes()) }.getOrDefault(false) }
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    val actions = listOfNotNull(KeybarAction.Edit.takeIf { spec != null }, KeybarAction.Stock.takeIf { !stock })

    fun restore() {
        // The widgets folder is watched: the new file reloads the bar.
        scope.launch(Dispatchers.IO) { runCatching { stockFile.copyTo(file, overwrite = true) } }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OptionLabel(stringResource(R.string.style_keys))
        Box(Modifier.weight(1f, fill = false)) {
            Row(
                Modifier
                    .frame(1.dp, Palette.border, radius = 12.dp)
                    .clickable { open = !open }
                    .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    stringResource(
                        when {
                            spec == null -> R.string.style_keybar_removed
                            spec.program == null -> R.string.style_keybar_errors
                            stock -> R.string.style_keybar_stock
                            else -> R.string.style_keybar_edited
                        },
                    ),
                    style = Mono.copy(color = if (spec != null && spec.program == null) Palette.red else Palette.foreground),
                )
                Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 6.dp))
            }
            if (open) {
                val labels = KeybarAction.entries.associateWith { stringResource(it.label) }
                ChoicePopup(actions, null, BelowAnchorStart, onDismiss = { open = false }, onPick = { action ->
                    when (action) {
                        KeybarAction.Edit -> spec?.let { onAgent("Edit ${it.name}") { bin -> editWidgetCommand(bin, it) } }
                        // Nothing of the user's to replace.
                        KeybarAction.Stock -> if (spec == null) restore() else restoring = true
                    }
                }, label = { labels.getValue(it) })
            }
        }
    }
    OptionHint(if (spec == null) stringResource(R.string.style_keybar_gone, file.name) else file.name)
    if (restoring) {
        ConfirmDialog(
            stringResource(R.string.style_keybar_restore_title),
            stringResource(R.string.style_keybar_restore_message),
            stringResource(R.string.style_replace),
            onDismiss = { restoring = false },
        ) {
            restoring = false
            restore()
        }
    }
}

private enum class KeybarAction(@StringRes val label: Int) {
    Edit(R.string.style_keybar_edit),
    Stock(R.string.style_keybar_back_to_stock),
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
    Heading(R.string.style_theme) {
        BracketAction(stringResource(R.string.style_browse), Palette.accent, onClick = onBrowse)
        BracketAction(stringResource(R.string.style_install), Palette.accent, onClick = onInstall)
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
                source?.label ?: stringResource(R.string.style_own_theme),
                Modifier.weight(1f),
                style = Mono.copy(color = Palette.muted),
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (source != null) BracketAction(stringResource(R.string.style_update), Palette.accent) { scope.launch { store.update(current) } }
            BracketAction(stringResource(R.string.style_remove), Palette.red) { onRemove(current) }
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
    Heading(R.string.style_background)
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
                BasicText("[ ${stringResource(R.string.style_add_image)} ]", style = Mono.copy(color = Palette.accent))
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
                stringResource(R.string.style_wallpaper_active),
                style = Mono.copy(color = Palette.green),
            )
        } else {
            BasicText("[ ${stringResource(R.string.style_wallpaper_use)} ]", style = Mono.copy(color = Palette.accent))
        }
    }
    removing?.let { file ->
        ConfirmDialog(
            stringResource(R.string.style_remove_background_title),
            stringResource(R.string.style_remove_deleted),
            stringResource(R.string.style_remove),
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
    launcherFont: LauncherFont,
    style: StyleStore,
) {
    LaunchedEffect(Unit) { font.refreshFamilies() }
    Heading(R.string.style_terminal)
    FontOption(font.families, font.family) { family -> style.update { it.with(Style.TERMINAL_FONT, family) } }
    OptionHint(stringResource(R.string.style_terminal_hint), Modifier.padding(bottom = 4.dp))
    NumberOption(R.string.style_text, TerminalText.entries.map { it.label to it.em.toDouble() }, Style.TERMINAL_TEXT, style, unit = " dp")
    // In portrait, where columns run short; landscape has plenty.
    val metrics = LocalContext.current.resources.displayMetrics
    val columns = font.columns(minOf(metrics.widthPixels, metrics.heightPixels))
    OptionHint(pluralStringResource(R.plurals.style_terminal_size, columns, TerminalFont.format(font.size).removeSuffix(".0"), columns), Modifier.padding(top = 2.dp))
    TerminalPreview(font, launcherFont)
}

/**
 * Terminal output in the chosen [TerminalFont] and the theme's terminal colors,
 * drawn here as foot will draw it: at its pixel size and pad, wrapped at its
 * columns, without ligatures. The family loads from Linux's files; until it
 * has, the one before it (or Android's monospace) stands in.
 */
@Composable
private fun TerminalPreview(
    font: TerminalFont,
    launcherFont: LauncherFont,
) {
    val family by produceState<FontFamily?>(null, font.family) { value = launcherFont.family(font.family) }
    val density = LocalDensity.current
    val style =
        TextStyle(
            fontFamily = family ?: FontFamily.Monospace,
            fontSize = with(density) { font.emPx.toSp() },
            color = Palette.foreground,
            fontFeatureSettings = "liga 0, calt 0",
        )
    val measurer = rememberTextMeasurer()
    PreviewLabel()
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .frame(2.dp, Palette.border, Palette.background, radius = 12.dp)
            .padding(with(density) { font.padPx.toDp() }),
    ) {
        val cell = remember(style) { measurer.measure("0".repeat(100), style).size.width / 100f }
        val columns = (constraints.maxWidth / cell).toInt().coerceAtLeast(1)
        val palette = Palette.current
        BasicText(remember(columns, palette) { previewText(columns) }, style = style, softWrap = false)
    }
}

/**
 * A sketch of home's bottom: a row of pinned apps, the recent strip and the prompt bar,
 * drawn with home's own frames, so the options above show as they change.
 */
@Composable
private fun HomePreview(runtime: Runtime) {
    val columns = runtime.style.style[Style.COLUMNS]
    val apps = familiarApps(runtime, columns + 4)
    val mono = LauncherStyle.monochromeIcons
    val bare = LauncherStyle.bareAppIcons
    PreviewScreen {
        // A row of home's grid: square cells, as most pinned apps are.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HomeGap)) {
            for (i in 0 until columns) {
                val app = apps.getOrNull(i)
                PreviewApp(app?.label ?: stringResource(R.string.style_preview_app), app?.icons(runtime)?.let { if (mono) it.second else it.first }, Modifier.weight(1f).aspectRatio(1f))
            }
        }
        if (LauncherStyle.recentApps) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .then(if (bare) Modifier else Modifier.tile())
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (app in apps.drop(columns)) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        PreviewIcon(app.label, app.icons(runtime).let { if (mono) it.second else it.first }, 30.dp, strip = true)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(32.dp).tile())
    }
}

/** A pinned app as ShortcutTile draws it, without what makes it one. */
@Composable
private fun PreviewApp(
    label: String,
    icon: ImageBitmap?,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.tileFrame(focused = false).padding(4.dp)) {
        val showLabel = showsLabel()
        val iconSize = tileIconSize(showLabel)
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            PreviewIcon(label, icon, iconSize)
            if (showLabel) TileLabel(label)
        }
    }
}

/** An app's icon as home draws it: a silhouette in the accent color when monochrome, on a badge when bare; [strip] icons fill their cell. */
@Composable
private fun PreviewIcon(
    label: String,
    icon: ImageBitmap?,
    size: Dp,
    strip: Boolean = false,
) {
    val mono = LauncherStyle.monochromeIcons
    when {
        LauncherStyle.bareAppIcons && (mono || icon == null) ->
            IconBadge(size) {
                if (icon != null) {
                    Image(icon, null, Modifier.size(size * 0.75f), colorFilter = ColorFilter.tint(Palette.accent))
                } else {
                    BasicText(initials(label), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Body))
                }
            }
        icon != null -> Image(icon, null, Modifier.size(size * if (mono && strip) 0.85f else 1f), colorFilter = if (mono) ColorFilter.tint(Palette.accent) else null)
        else -> BasicText(initials(label), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Large))
    }
}

/**
 * Widgets on home as empty tiles: a wide one over two small ones, the last in use and so
 * outlined as focused, with the gaps between them and to the screen's edges.
 */
@Composable
private fun WidgetPreview() {
    PreviewScreen {
        Box(Modifier.fillMaxWidth().height(40.dp).tile())
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(HomeGap)) {
            Box(Modifier.weight(1f).fillMaxHeight().tile())
            Box(Modifier.weight(1f).fillMaxHeight().tile(focused = true))
        }
    }
}

/**
 * A patch of home for the previews, in the controls' column so it reads as a sketch rather than one of them:
 * home's gaps out to its edges, and [content] stacked home's gaps in apart.
 */
@Composable
private fun PreviewScreen(content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        OptionLabel(stringResource(R.string.style_preview))
        Column(
            Modifier
                .weight(1f)
                .clip(LauncherStyle.shape(12.dp))
                .background(Palette.surface)
                .padding(EdgeGap),
            verticalArrangement = Arrangement.spacedBy(HomeGap),
            content = content,
        )
    }
}

/** Over a preview, so it isn't mistaken for controls. */
@Composable
private fun PreviewLabel() = BasicText(stringResource(R.string.style_preview), Modifier.padding(top = 12.dp, bottom = 4.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))

/** A stretch of [PREVIEW] in theme colors (foreground and none without them), maybe bold or italic. */
private class Run(
    val text: String,
    val color: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val background: String? = null,
)

/** A prompt, as the preview's shell shows it. */
private val PROMPT = listOf(Run("~", "green"), Run(" "), Run("❯", "blue"), Run(" "))

/**
 * What the preview shows: ls, some code with the characters fonts tell apart and
 * an italic docstring, git's colors, and a prompt with the theme's block cursor.
 */
private val PREVIEW =
    listOf(
        PROMPT + Run("ls"),
        listOf(Run("Documents", "blue", bold = true), Run("  "), Run("Projects", "blue", bold = true), Run("  notes.md  "), Run("run.sh", "green")),
        PROMPT + Run("cat greet.py"),
        listOf(Run("def", "magenta"), Run(" "), Run("greet", "blue"), Run("(name):  "), Run("# 0O 1lI {} [] <=", "color8")),
        listOf(Run("    "), Run("\"\"\"Say hi, nicely.\"\"\"", "green", italic = true)),
        listOf(Run("    "), Run("return", "magenta"), Run(" "), Run("f\"hi {name}\"", "green")),
        PROMPT + Run("git status -s"),
        listOf(Run(" M", "red"), Run(" "), Run("greet.py", "cyan"), Run("  "), Run("??", "yellow"), Run(" "), Run("todo.txt", "cyan")),
        PROMPT + Run(" ", "background", background = "cursor"),
    )

/** [PREVIEW] wrapped as foot wraps, at [columns] characters rather than between words. */
private fun previewText(columns: Int) =
    buildAnnotatedString {
        PREVIEW.forEachIndexed { i, line ->
            if (i > 0) append('\n')
            var column = 0
            for (run in line) {
                val style =
                    SpanStyle(
                        color = run.color?.let(Palette::named) ?: Color.Unspecified,
                        fontWeight = if (run.bold) FontWeight.Bold else null,
                        fontStyle = if (run.italic) FontStyle.Italic else null,
                        background = run.background?.let(Palette::named) ?: Color.Unspecified,
                    )
                withStyle(style) {
                    for (c in run.text) {
                        if (column == columns) {
                            append('\n')
                            column = 0
                        }
                        append(c)
                        column++
                    }
                }
            }
        }
    }

/** A section's title, set apart from the muted labels of the options under it by the accent color, as the weather screen sets its sections apart; [actions] go at the far end. */
@Composable
private fun Heading(
    @StringRes text: Int,
    actions: @Composable RowScope.() -> Unit = {},
) {
    ActionsRow(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), actions) {
        BasicText(stringResource(text), style = Mono.copy(color = Palette.accent), softWrap = false)
    }
}

/** Font names run long and pacman can add many: the picked one shows, and tapping it lists the others under it. */
@Composable
private fun FontOption(
    families: List<String>,
    selected: String,
    label: (String) -> String = { it },
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OptionLabel(stringResource(R.string.style_font))
        Box(Modifier.weight(1f, fill = false)) {
            Row(
                Modifier
                    .frame(1.dp, Palette.border, radius = 12.dp)
                    .clickable { open = !open }
                    .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(label(selected), Modifier.weight(1f, fill = false), style = Mono.copy(color = Palette.foreground), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 6.dp))
            }
            if (open) {
                ChoicePopup(families, selected, BelowAnchorStart, onDismiss = { open = false }, onPick = onSelect, label = label)
            }
        }
    }
}

/** Just under the anchor, lined up with its start, and kept on screen. */
private object BelowAnchorStart : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ) = IntOffset(
        anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        (anchorBounds.bottom + 8).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
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
    @StringRes label: Int,
    style: StyleStore,
    vararg settings: Setting.Number,
) {
    val all = settings.toList()
    val setting = all.first()
    val value = style.style[setting]
    fun set(v: Double) = style.update { it.with(all) { s -> v.coerceIn(s.range) } }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OptionLabel(stringResource(label))
        // Keys at the end of the range go on to the other step, and from reset as it goes, to a step.
        val minus = remember { FocusRequester() }
        val plus = remember { FocusRequester() }
        Row(Modifier.frame(1.dp, Palette.border, radius = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val down = value > setting.range.start
            val up = value < setting.range.endInclusive
            BasicText(
                "−",
                Modifier
                    .focusRequester(minus)
                    .passesKeys(plus)
                    .clickable(enabled = down) { set(ceil(value - 1)) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = Mono.copy(color = if (down) Palette.foreground else Palette.muted),
            )
            BasicText(Toml.format(value) + " dp", Modifier.widthIn(min = 64.dp), style = Mono.copy(textAlign = TextAlign.Center), softWrap = false)
            BasicText(
                "+",
                Modifier
                    .focusRequester(plus)
                    .passesKeys(minus)
                    .clickable(enabled = up) { set(floor(value + 1)) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = Mono.copy(color = if (up) Palette.foreground else Palette.muted),
            )
        }
        if (all.any { style.style[it] != it.default }) {
            BracketAction(stringResource(R.string.style_reset), Palette.muted, Modifier.passesKeys(minus, plus)) { style.update { it.with(all) { s -> s.default } } }
        }
    }
    if (style.style.common(all) == null) OptionHint(stringResource(R.string.style_differ_step), Modifier.padding(bottom = 4.dp))
}

/**
 * A [Style] number dragged or tapped across its range in steps of 0.01, applied as it moves
 * (style.toml is saved once it settles).
 */
@Composable
private fun NumberSlider(
    @StringRes label: Int,
    style: StyleStore,
    setting: Setting.Number,
) {
    val value = style.style[setting]
    val range = setting.range
    fun set(
        x: Float,
        width: Int,
    ) {
        val f = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        val v = Math.round((range.start + f * (range.endInclusive - range.start)) * 100) / 100.0
        style.update { it.with(setting, v) }
    }
    val accent = Palette.accent
    val track = Palette.surface
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OptionLabel(stringResource(label))
        Spacer(
            Modifier
                .weight(1f)
                .height(40.dp)
                // Keys step it, a twentieth of its range or with Shift a hundredth.
                .keyStepped(vertical = false) { by, fine ->
                    val span = range.endInclusive - range.start
                    val v = Math.round((value + by * span / (if (fine) 100 else 20)).coerceIn(range.start, range.endInclusive) * 100) / 100.0
                    // At the end, the arrow goes on, to reset.
                    if (v == value) return@keyStepped false
                    style.update { it.with(setting, v) }
                    true
                }.pointerInput(setting) { detectTapGestures { set(it.x, size.width) } }
                .pointerInput(setting) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        set(change.position.x, size.width)
                    }
                }.drawBehind {
                    val f = ((value - range.start) / (range.endInclusive - range.start)).toFloat().coerceIn(0f, 1f)
                    val line = 4.dp.toPx()
                    val thumbW = 10.dp.toPx()
                    val thumbH = 20.dp.toPx()
                    val cy = size.height / 2
                    drawRect(track, Offset(0f, cy - line / 2), Size(size.width, line))
                    drawRect(accent, Offset(0f, cy - line / 2), Size(size.width * f, line))
                    drawRoundRect(accent, Offset((size.width - thumbW) * f, cy - thumbH / 2), Size(thumbW, thumbH), CornerRadius(3.dp.toPx() * LauncherStyle.cornerScale))
                },
        )
        BasicText(Toml.format(value), Modifier.widthIn(min = 56.dp).padding(start = 12.dp), style = Mono.copy(textAlign = TextAlign.End), softWrap = false)
        // Always there, so the slider doesn't narrow under the finger as it leaves the default.
        BracketAction(stringResource(R.string.style_reset), Palette.muted) { style.update { it.with(setting, setting.default) } }
    }
}

/** [Style] flags as a choice of two, [on] first, set together. */
@Composable
private fun Flag(
    @StringRes label: Int,
    @StringRes on: Int,
    @StringRes off: Int,
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
    StyleOption(label, listOf(stringResource(on), stringResource(off)), selected, stringResource(R.string.style_differ)) { i -> style.update { it.with(all) { i == 0 } } }
}

/** A [Style] number as named [presets]; a value set in style.toml between them shows as custom. */
@Composable
private fun NumberOption(
    @StringRes label: Int,
    presets: List<Pair<Int, Double>>,
    setting: Setting.Number,
    style: StyleStore,
    unit: String = "",
) {
    val value = style.style[setting]
    StyleOption(label, presets.map { stringResource(it.first) }, presets.indexOfFirst { it.second == value }, Toml.format(value) + unit) { i ->
        style.update { it.with(setting, presets[i].second) }
    }
}

@Composable
private fun CountOption(
    @StringRes label: Int,
    setting: Setting.Count,
    style: StyleStore,
) {
    val counts = setting.range.toList()
    StyleOption(label, counts.map(Int::toString), counts.indexOf(style.style[setting])) { i -> style.update { it.with(setting, counts[i]) } }
}

/**
 * A labeled segmented choice: the picked option fills with the accent color.
 * None is picked for a value set in style.toml outside the options; [custom] says what it is.
 */
@Composable
private fun StyleOption(
    @StringRes label: Int,
    options: List<String>,
    selected: Int,
    custom: String? = null,
    onSelect: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OptionLabel(stringResource(label))
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
    if (selected < 0 && custom != null) OptionHint(stringResource(R.string.style_custom, custom), Modifier.padding(bottom = 4.dp))
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
            stringResource(if (partial) R.string.style_themes_downloading else R.string.style_themes_not_installed),
            style = Mono,
        )
        when (state) {
            is ThemeDownload.Running -> BasicText(stringResource(R.string.style_downloading, state.megabytes), style = Mono.copy(color = Palette.accent))
            is ThemeDownload.Failed -> {
                BasicText(stringResource(R.string.style_failed, state.message), style = Mono.copy(color = Palette.red))
                BracketAction(stringResource(R.string.style_retry), Palette.accent, onClick = onDownload)
            }
            ThemeDownload.Idle -> BracketAction(stringResource(R.string.style_download_themes), Palette.accent, onClick = onDownload)
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
        // The card takes the tap, so keys stop on it once rather than on it and its picture.
        Thumbnail(theme.preview, selected = false, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f), onClick = null)
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
    /** Null when what holds it takes the tap. */
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val image by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, 480, 0)?.asImageBitmap() }
    }
    Box(
        modifier
            .frame(2.dp, if (selected) Palette.accent else Color.Transparent, Palette.surface, radius = 12.dp)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.then(onLongClick?.let { Modifier.menuLongPresses(it) } ?: Modifier).combinedClickable(
                        onClick = onClick,
                        onLongClick =
                            onLongClick?.let {
                                {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    it()
                                }
                            },
                    )
                },
            ),
    ) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

private fun String?.toColor(): Color = hexColor(this) ?: Color.Gray
