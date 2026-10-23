package dev.omalauncher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.linux.AGENTS
import dev.omalauncher.linux.Agent
import androidx.compose.runtime.rememberCoroutineScope
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.Stage
import dev.omalauncher.theme.BundledTheme
import dev.omalauncher.theme.ThemeStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The first-run screen: pick a theme and, optionally, set up Linux. Start
 * fetches Omarchy's themes, then the home screen opens; a Linux install
 * carries on in the background.
 */
@Composable
internal fun SetupScreen(runtime: Runtime) {
    val themes = runtime.themes
    var linux by rememberSaveable { mutableStateOf(false) }
    // Claude Code first, like Omarchy's default agent; the first picked becomes the launcher's default.
    var picked by rememberSaveable { mutableStateOf(listOf(AGENTS.first().bin)) }
    var started by remember { mutableStateOf(false) }
    val install = rememberLinuxInstall(runtime)
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Only the picked theme now; the rest download in the background once home is up.
    fun fetchTheme() {
        error = null
        scope.launch {
            error = themes.fetchPicked()
            if (error == null) runtime.finishWelcome()
        }
    }
    // Keys start on the picked theme: on the first, the strip would pick that one as focus lands on it.
    val pickedFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().holdsKeys(start = pickedFocus), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = Gutter, vertical = 32.dp),
            ) {
                Wordmark()
                if (started) {
                    ThemeDownloadProgress(themes.choices.firstOrNull { it.name == themes.picked }?.title?.lowercase() ?: themes.picked, error, onRetry = ::fetchTheme, onSkip = runtime::finishWelcome)
                } else {
                    Welcome(themes, pickedFocus, linux, { linux = !linux }, picked) { bin -> picked = if (bin in picked) picked - bin else picked + bin }
                }
            }
        }
        // The start button stays in reach while the agent list scrolls.
        if (!started) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(start = Gutter, end = Gutter, top = 12.dp, bottom = 16.dp)) {
                Appear(3) {
                    StartButton(linux) {
                        started = true
                        if (linux) install(picked)
                        fetchTheme()
                    }
                }
            }
        }
    }
}

/** The theme's colors as a strip of blocks, like fastfetch's palette, over the name. */
@Composable
private fun Wordmark() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (name in listOf("red", "yellow", "green", "cyan", "blue", "magenta")) {
            Box(Modifier.size(width = 22.dp, height = 8.dp).background(Palette.named(name) ?: Palette.accent))
        }
    }
    Spacer(Modifier.height(20.dp))
    BasicText("◆ omalauncher", style = Mono.copy(color = Palette.accent, fontSize = 28.sp, fontWeight = FontWeight.Bold))
}

@Composable
private fun Welcome(
    themes: ThemeStore,
    pickedFocus: FocusRequester,
    linux: Boolean,
    onToggleLinux: () -> Unit,
    picked: List<String>,
    onToggle: (String) -> Unit,
) {
    Appear(0) {
        Column {
            Spacer(Modifier.height(10.dp))
            BasicText("omarchy on your home screen", style = Mono.copy(fontSize = TextSize.Large))
            Spacer(Modifier.height(10.dp))
            BasicText(
                "Omarchy's themes and wallpapers, your Android apps, and home-screen widgets. Arch Linux adds " +
                    "terminal apps and coding agents, now or later in settings.",
                style = Mono.copy(color = Subtle, lineHeight = 20.sp),
            )
        }
    }
    Spacer(Modifier.height(32.dp))
    // The theme picked starts downloading, the default one right away; another once it stays picked a moment, so
    // arrowing along the strip doesn't start one for each theme passed (start fetches the picked one anyway).
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(themes.picked) {
        if (!first) delay(PrefetchSettle)
        first = false
        themes.prefetch(themes.picked)
    }
    Appear(1) {
        // A key moving along the strip picks as it goes, as a tap does.
        ThemeStrip(themes, pickOnFocus = true, pickedFocus = pickedFocus)
    }
    Spacer(Modifier.height(32.dp))
    Appear(2) {
        Column {
            Label("linux")
            Spacer(Modifier.height(12.dp))
            Choice("also set up Arch Linux", "terminal apps, agents, pacman · ~1 GB download, 3 GB space", linux, onToggleLinux)
            if (linux) {
                Spacer(Modifier.height(24.dp))
                AgentPicker(picked, onToggle)
            }
        }
    }
}

/** The coding agents to install with Linux; the first picked becomes the default. */
@Composable
internal fun AgentPicker(
    picked: List<String>,
    onToggle: (String) -> Unit,
) {
    Column {
        Label("coding agents")
        BasicText(
            "Pick any to install with Linux. You can add or remove them later in settings.",
            Modifier.padding(top = 4.dp, bottom = 14.dp),
            style = Mono.copy(color = Subtle, fontSize = TextSize.Small),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (agent in AGENTS) Choice(agent.name, agent.description, agent.bin in picked) { onToggle(agent.bin) }
        }
    }
}

@Composable
private fun StartButton(
    linux: Boolean,
    onStart: () -> Unit,
) {
    Column {
        // Keys on it wait out the theme's download, for try again should it fail.
        PrimaryButton(if (linux) "start and set up linux" else "start", Modifier.passesKeys(), onStart)
        BasicText(
            if (linux) {
                "Linux downloads about 1 GB, so Wi-Fi is best, and takes 3 GB of space. It installs in the " +
                    "background in 10 to 20 minutes; the home screen is ready right away."
            } else {
                "Downloads the theme's wallpaper now and Omarchy's other themes (about 70 MB) in the background."
            },
            Modifier.padding(top = 10.dp).fillMaxWidth(),
            style = Mono.copy(color = Subtle, fontSize = TextSize.Small, lineHeight = 16.sp),
        )
    }
}

/** After start: the picked theme's wallpaper downloading, or what to do when it failed. */
@Composable
private fun ThemeDownloadProgress(
    theme: String,
    error: String?,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
) {
    Spacer(Modifier.height(10.dp))
    BasicText(
        if (error != null) "the theme didn't download" else "getting $theme",
        style = Mono.copy(fontSize = TextSize.Large, color = if (error != null) Palette.red else Palette.foreground),
    )
    Spacer(Modifier.height(28.dp))
    StageRow(
        label = "download its wallpaper",
        status = if (error != null) StageStatus.Failed else StageStatus.Running,
        detail = error,
        progress = null,
    )
    if (error != null) {
        Spacer(Modifier.height(28.dp))
        PrimaryButton("try again", Modifier.takesPassedKeys().passesKeys(), onRetry)
        BasicText(
            "continue without wallpapers",
            Modifier.padding(top = 16.dp).clickable(onClick = onSkip),
            style = Mono.copy(color = Palette.accent, fontSize = TextSize.Body),
        )
        BasicText(
            "The theme's colors apply anyway. The launcher tries again later, or download them in settings › style.",
            Modifier.padding(top = 6.dp),
            style = Mono.copy(color = Subtle, fontSize = TextSize.Small, lineHeight = 16.sp),
        )
    }
}

/** A toggle as a bordered row: [title], a one-line [description], and a check box. */
@Composable
private fun Choice(
    title: String,
    description: String,
    on: Boolean,
    onToggle: () -> Unit,
) {
    val border by animateColorAsState(if (on) Palette.accent else Palette.border, label = "border")
    Row(
        Modifier
            .fillMaxWidth()
            .frame(if (on) 2.dp else 1.dp, border, if (on) Palette.accent.copy(alpha = 0.08f) else Palette.background, radius = 12.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = Mono.copy(fontSize = TextSize.Body, color = if (on) Palette.foreground else Palette.foreground.copy(alpha = 0.8f)))
            BasicText(description, style = Mono.copy(color = Subtle, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Check(on)
    }
}

@Composable
private fun Check(on: Boolean) {
    val fill by animateColorAsState(if (on) Palette.accent else Color.Transparent, label = "check")
    Box(
        Modifier.size(22.dp).frame(1.5.dp, if (on) Palette.accent else Palette.border, fill, radius = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (on) BasicText("✓", style = Mono.copy(color = Palette.background, fontSize = 13.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
internal fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .frame(0.dp, Palette.accent, Palette.accent, radius = 14.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = Mono.copy(color = Palette.background, fontSize = TextSize.Item, fontWeight = FontWeight.Bold))
    }
}

/** Linux setup's stages as a checklist: done, running (with its step and progress), failed or still to come. */
@Composable
internal fun LinuxProgress(
    state: LinuxState,
    agents: List<Agent>,
    onRetry: () -> Unit,
    /** Stops the install and deletes what it put on disk; null once it can't be (an update of an installed system). */
    onCancel: (() -> Unit)?,
) {
    val failed = state as? LinuxState.Failed
    val working = state as? LinuxState.Installing
    val current = working?.stage ?: failed?.stage ?: Stage.Download
    val log = working?.log ?: failed?.log.orEmpty()
    BasicText(
        if (failed != null) "setup stopped" else "setting up linux",
        style = Mono.copy(fontSize = TextSize.Large, color = if (failed != null) Palette.red else Palette.foreground),
    )
    Spacer(Modifier.height(24.dp))
    val stages = Stage.entries.filter { it != Stage.Agents || agents.isNotEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        for (stage in stages) {
            val status =
                when {
                    stage.ordinal < current.ordinal -> StageStatus.Done
                    stage != current -> StageStatus.Pending
                    failed != null -> StageStatus.Failed
                    else -> StageStatus.Running
                }
            StageRow(
                label = if (stage == Stage.Agents) "install ${agents.joinToString { it.name }}" else stage.label,
                status = status,
                detail = working?.takeIf { stage == current }?.step ?: failed?.takeIf { stage == current }?.message,
                progress = working?.takeIf { stage == current }?.progress,
            )
        }
    }
    Spacer(Modifier.height(24.dp))
    if (failed != null) {
        PrimaryButton("try again", Modifier.takesPassedKeys().passesKeys(), onRetry)
        Spacer(Modifier.height(16.dp))
    }
    if (onCancel != null) {
        BracketAction("cancel install", Palette.red, Modifier.takesPassedKeys().passesKeys(), onCancel)
        Spacer(Modifier.height(16.dp))
    }
    LogTail(log, expandedByDefault = failed != null)
    if (failed == null) {
        BasicText(
            "You can close this and use the launcher; setup keeps going in the background.",
            Modifier.padding(top = 16.dp),
            style = Mono.copy(color = Subtle, fontSize = TextSize.Small),
        )
    }
}

private enum class StageStatus { Done, Running, Failed, Pending }

@Composable
private fun StageRow(
    label: String,
    status: StageStatus,
    detail: String?,
    progress: Float?,
) {
    Row {
        Box(Modifier.width(28.dp)) {
            when (status) {
                StageStatus.Done -> Symbol(Glyph.Check, Palette.green)
                StageStatus.Running -> Spinner()
                StageStatus.Failed -> Symbol(Glyph.Close, Palette.red)
                StageStatus.Pending -> BasicText("·", style = Mono.copy(color = Palette.border))
            }
        }
        Column(Modifier.weight(1f)) {
            BasicText(
                label,
                style =
                    Mono.copy(
                        fontSize = TextSize.Body,
                        color =
                            when (status) {
                                StageStatus.Pending -> Subtle
                                StageStatus.Done -> Palette.foreground.copy(alpha = 0.7f)
                                else -> Palette.foreground
                            },
                    ),
            )
            if (detail != null && status != StageStatus.Done) {
                BasicText(
                    detail,
                    Modifier.padding(top = 4.dp),
                    style = Mono.copy(color = if (status == StageStatus.Failed) Palette.red else Subtle, fontSize = TextSize.Small),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (progress != null && status == StageStatus.Running) {
                val shown by animateFloatAsState(progress.coerceIn(0f, 1f), label = "progress")
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).background(Palette.surface)) {
                    Box(Modifier.fillMaxWidth(shown).height(4.dp).background(Palette.accent))
                }
            }
        }
    }
}

/** A braille spinner, as terminal apps draw them. */
@Composable
private fun Spinner() {
    val frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(80)
            i = (i + 1) % frames.length
        }
    }
    BasicText(frames[i].toString(), style = Mono.copy(color = Palette.accent, fontWeight = FontWeight.Bold))
}

/** The last lines of setup's output; tap for more. */
@Composable
private fun LogTail(
    log: List<String>,
    expandedByDefault: Boolean,
) {
    if (log.isEmpty()) return
    var expanded by remember(expandedByDefault) { mutableStateOf(expandedByDefault) }
    Column(
        Modifier
            .fillMaxWidth()
            .frame(1.dp, Palette.border, Palette.surface, radius = 10.dp)
            .clickable { expanded = !expanded }
            .padding(12.dp),
    ) {
        Row {
            Label("output")
            Spacer(Modifier.weight(1f))
            BasicText(if (expanded) "less" else "more", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small))
        }
        Spacer(Modifier.height(6.dp))
        for (line in log.takeLast(if (expanded) 40 else 3)) {
            BasicText(line, style = Mono.copy(color = Subtle, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Omarchy's themes as swatches in a horizontal row; tapping one recolors everything right away.
 * [gutter] is the parent's side padding, which the row scrolls under. By default a pick only
 * recolors, for setup where the full themes aren't downloaded yet. With [onBrowse], the row
 * ends in a tile that opens the community themes.
 */
@Composable
internal fun ThemeStrip(
    themes: ThemeStore,
    gutter: Dp = Gutter,
    onPick: (BundledTheme) -> Unit = themes::pick,
    onBrowse: (() -> Unit)? = null,
    pickOnFocus: Boolean = false,
    /** Gives keys the picked theme's swatch. */
    pickedFocus: FocusRequester? = null,
) {
    RescanThemes(themes)
    val list = themes.choices
    if (list.isEmpty()) return
    Column {
        Row {
            Label("theme")
            Spacer(Modifier.weight(1f))
            BasicText(
                list.firstOrNull { it.name == themes.picked }?.title?.lowercase().orEmpty(),
                style = Mono.copy(color = Subtle, fontSize = TextSize.Small),
            )
        }
        Spacer(Modifier.height(12.dp))
        val start = list.indexOfFirst { it.name == themes.picked }.coerceAtLeast(0)
        LazyRow(
            // Scrolls under the gutters to the screen edges, starting aligned with the text above.
            Modifier.bleed(gutter),
            state = rememberLazyListState(initialFirstVisibleItemIndex = start),
            contentPadding = PaddingValues(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { it.name }) { theme ->
                val selected = theme.name == themes.picked
                ThemeSwatch(theme, selected, pickOnFocus, pickedFocus) { onPick(theme) }
            }
            if (onBrowse != null) item(key = "browse") { BrowseSwatch(onBrowse) }
        }
    }
}

/** A theme drawn in its own colors: its background, name and a row of its accents. */
@Composable
private fun ThemeSwatch(
    theme: BundledTheme,
    selected: Boolean,
    pickOnFocus: Boolean,
    /** The picked theme's swatch, which keys start on. */
    picked: FocusRequester?,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    fun color(key: String) = hexColor(theme.colors[key]) ?: Color.Gray
    val border by animateColorAsState(if (selected) Palette.accent else Palette.border, label = "swatch")
    Column(
        Modifier
            .width(112.dp)
            .frame(if (selected) 2.dp else 1.dp, border, color("background"), radius = 12.dp)
            .then(if (selected && picked != null) Modifier.focusRequester(picked) else Modifier)
            .onFocusChanged {
                if (!pickOnFocus || !it.isFocused || selected) return@onFocusChanged
                // Only as keys move it: as setup first shows, Android gives the strip's first swatch focus by itself,
                // which goes on to the picked one for keys to start on.
                if (focusFromKey()) onClick() else view.post { picked?.requestFocus() }
            }
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        BasicText(theme.title.lowercase(), style = Mono.copy(color = color("foreground"), fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (key in listOf("accent", "red", "yellow", "green", "magenta")) {
                Box(Modifier.size(width = 14.dp, height = 6.dp).background(color(key)))
            }
        }
    }
}

/** The strip's last tile, shaped like a [ThemeSwatch]: opens the community themes. */
@Composable
private fun BrowseSwatch(onClick: () -> Unit) {
    Column(
        Modifier
            .width(112.dp)
            .frame(1.dp, Palette.border, Palette.surface, radius = 12.dp)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        BasicText("more themes ›", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(5) { Box(Modifier.size(width = 14.dp, height = 6.dp).background(Palette.border)) }
        }
    }
}

@Composable
internal fun Label(text: String) {
    BasicText(text.uppercase(), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small, letterSpacing = 1.5.sp))
}

/** Fades and slides content in, [index] steps after the screen appears; its space is kept from the start. */
@Composable
private fun Appear(
    index: Int,
    content: @Composable () -> Unit,
) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(120L + index * 110L)
        shown.animateTo(1f, tween(450))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = shown.value
            translationY = (1f - shown.value) * 24.dp.toPx()
        },
    ) { content() }
}

/**
 * Secondary text on these screens. Some themes' dim colors match their surfaces
 * (white's dark_foreground is its lighter_background), so fade the foreground instead.
 */
private val Subtle get() = Palette.foreground.copy(alpha = 0.6f)

/** Side padding of the setup screens. */
private val Gutter = 24.dp

/** How long a theme stays picked in setup before its download starts. */
private const val PrefetchSettle = 700L

