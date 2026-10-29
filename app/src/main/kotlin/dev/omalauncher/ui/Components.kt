package dev.omalauncher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.omalauncher.R
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.omalauncher.linux.JobState

/**
 * A full-screen framed panel with a title, optional [actions] and a close button: themes, agents, the store.
 * With a [contentWidth], a wide screen centers the content at that width; the frame and title still span the panel.
 * With [onBack], or a [LocalPanelBack] around it, the title is a [BackTitle] in place of the close mark.
 */
@Composable
fun OverlayPanel(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    contentWidth: Dp = Dp.Unspecified,
    onBack: (() -> Unit)? = LocalPanelBack.current,
    content: @Composable ColumnScope.() -> Unit,
) {
    val close = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.overlay)
            // A tap on the scrim closes the panel; keys move between what's in it, not to the scrim.
            .focusProperties { canFocus = false }
            .clickable(onClick = onDismiss)
            .safeDrawingPadding()
            .padding(PanelGap),
    ) {
        PanelFrame(
            onClose = onDismiss.takeIf { onBack == null },
            // Taps inside the panel don't reach the scrim behind it.
            modifier = Modifier.pointerInput(Unit) { detectTapGestures { } }.holdsKeys(start = close),
            closeFocus = close,
            title = { BackTitle(title, onBack) },
            actions = {
                actions()
                // Without the ✕, one unseen and no wide keeps the header its usual height, so the title stays where it was.
                if (onBack != null) {
                    Symbol(
                        Glyph.Close,
                        Color.Transparent,
                        Modifier.layout { m, c -> m.measure(c).let { p -> layout(0, p.height) {} } }.padding(8.dp),
                        TextSize.Title,
                    )
                }
            },
            contentWidth = contentWidth,
            content = content,
        )
    }
}

/** [OverlayPanel]'s frame without the scrim, for a screen of its own (Files): [title] fills the header up to the [actions] and close mark. */
@Composable
fun PanelFrame(
    /** The ✕; null for a window Android closes (Back, Recents), like an owl app's. */
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Gives keys the ✕, where a panel's keys start. */
    closeFocus: FocusRequester? = null,
    title: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    contentWidth: Dp = Dp.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .frame(LauncherStyle.panelBorderWidth, Palette.accent, Palette.background, radius = 20.dp)
            .padding(PanelPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { title() }
            actions()
            if (onClose != null) {
                val focus = closeFocus?.let { Modifier.focusRequester(it) } ?: Modifier
                Symbol(Glyph.Close, Palette.red, focus.clickable(onClick = onClose).padding(8.dp), TextSize.Title)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (contentWidth == Dp.Unspecified) {
            content()
        } else {
            Column(Modifier.weight(1f).widthIn(max = contentWidth).fillMaxWidth().align(Alignment.CenterHorizontally), content = content)
        }
    }
}

/** A panel's title, in the accent color. */
@Composable
fun PanelTitle(text: String) = BasicText(text, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title), maxLines = 1, overflow = TextOverflow.Ellipsis)

/** The way back to the screen under an [OverlayPanel], for one opened from another (a settings page from settings); null for one on its own. */
val LocalPanelBack = compositionLocalOf<(() -> Unit)?> { null }

/** A [PanelTitle] that, with [onBack], has a back chevron before it; the chevron and the title both go back. */
@Composable
fun BackTitle(
    text: String,
    onBack: (() -> Unit)?,
) {
    if (onBack == null) return PanelTitle(text)
    Row(Modifier.clickable(onClick = onBack).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // The font subset has no left chevron; the right one turned around is the same shape.
        Symbol(Glyph.ChevronRight, Palette.accent, Modifier.padding(end = 6.dp).rotate(180f), TextSize.Title)
        PanelTitle(text)
    }
}

/** An [OverlayPanel] action drawn with one of [Icons]: muted, the size of [RefreshButton]. */
@Composable
fun PanelIcon(
    icon: ImageVector,
    description: String,
    /** Degrees, to point [Icons.Chevron] another way. */
    rotation: Float = 0f,
    onClick: () -> Unit,
) {
    Box(Modifier.clip(CircleShape).clickable(onClick = onClick).padding(6.dp)) {
        Image(icon, description, Modifier.size(20.dp).rotate(rotation), colorFilter = ColorFilter.tint(Palette.muted))
    }
}

/** An [OverlayPanel] action that reloads a list; it spins in the accent color while [loading], and taps then do nothing. */
@Composable
fun RefreshButton(
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Box(Modifier.clip(CircleShape).clickable(enabled = !loading, onClick = onRefresh).padding(6.dp)) {
        val turn =
            if (loading) {
                val angle by rememberInfiniteTransition(label = "refresh")
                    .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "turn")
                Modifier.rotate(angle)
            } else {
                Modifier
            }
        Image(Icons.Refresh, stringResource(if (loading) R.string.home_refreshing else R.string.home_refresh), Modifier.size(20.dp).then(turn), colorFilter = ColorFilter.tint(if (loading) Palette.accent else Palette.muted))
    }
}

/** [OverlayPanel]'s padding inside its frame; rows that scroll sideways [bleed] through it to the frame. */
val PanelPadding = 12.dp

/** Widens the element by [gutter] on both sides, drawing over its parent's padding. */
fun Modifier.bleed(gutter: Dp) =
    layout { measurable, constraints ->
        val extra = gutter.roundToPx() * 2
        val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
        layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
    }

/** A settings card: full width, the surface color in the corner style, [padding] inside; tappable with [onClick]. */
fun Modifier.card(
    padding: Dp = 14.dp,
    onClick: (() -> Unit)? = null,
): Modifier =
    fillMaxWidth()
        .clip(LauncherStyle.shape(12.dp))
        .background(Palette.surface)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(padding)

/**
 * Commands or a widget's source, small, scrolling sideways rather than wrapping. Keys read through it every way.
 * Lines are cut at [MAX_CODE_LINE]: each is measured whole, and Compose can't lay out one wider than about 16M px,
 * which a 1 MB widget's one-line data string can be.
 */
@Composable
fun CodeBlock(text: String) =
    BasicText(remember(text) { text.cutLongLines() }, Modifier.fillMaxWidth().background(Palette.surface).horizontalScroll(rememberScrollState()).keyScrolled().padding(6.dp), style = Mono.copy(fontSize = TextSize.Small))

private const val MAX_CODE_LINE = 10_000

private fun String.cutLongLines(): String =
    if (lineSequence().none { it.length > MAX_CODE_LINE }) this else lineSequence().joinToString("\n") { if (it.length > MAX_CODE_LINE) it.take(MAX_CODE_LINE) + "…" else it }

/**
 * A framed popup list at [position] with [selected] marked; picking one closes it first.
 * [sublabel] adds a muted second line, and [footer] goes under the options.
 */
@Composable
fun <T> ChoicePopup(
    options: List<T>,
    selected: T?,
    position: PopupPositionProvider,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
    label: (T) -> String,
    minWidth: Dp = 180.dp,
    sublabel: ((T) -> String)? = null,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        // Keys start on the one picked now; a list longer than the screen scrolls to follow them.
        val selectedFocus = remember { FocusRequester() }
        Column(
            Modifier
                .width(IntrinsicSize.Max)
                .widthIn(min = minWidth)
                .frame(2.dp, Palette.accent, Palette.background)
                .holdsKeys(start = selectedFocus.takeIf { selected in options })
                .verticalScroll(rememberScrollState())
                .padding(4.dp),
        ) {
            for (option in options) {
                val current = option == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (current) Modifier.focusRequester(selectedFocus) else Modifier)
                        .clickable {
                            onDismiss()
                            onPick(option)
                        }.padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(if (current) "● " else "  ", style = Mono.copy(color = Palette.accent))
                    Column(Modifier.weight(1f)) {
                        BasicText(label(option), style = Mono.copy(color = if (current) Palette.accent else Palette.foreground))
                        sublabel?.let { BasicText(it(option), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small)) }
                    }
                }
            }
            footer()
        }
    }
}

/** A muted heading over a group of rows: settings sections, the app drawer's letters. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) = BasicText(text, modifier.padding(top = 12.dp, bottom = 4.dp), style = Mono.copy(color = Palette.muted))

/** A [Glyph] from the launcher's Nerd Font subset, sized like text. */
@Composable
fun Symbol(
    glyph: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextSize.Body,
) = BasicText(glyph, modifier, style = Mono.copy(color = color, fontSize = fontSize, fontFamily = NerdSymbols))

/** A terminal-style `[ label ]` button. */
@Composable
fun BracketAction(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp)) {
        BasicText("[ $label ]", style = Mono.copy(color = color))
    }
}

/**
 * [content] with [actions] at its end, or under it once beside it would leave [content] narrower than its longest
 * word: at a large Android font size, buttons beside a name squeeze it to a letter a line.
 */
@Composable
fun ActionsRow(
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(content, { Row(verticalAlignment = Alignment.CenterVertically, content = actions) }),
        modifier = modifier,
    ) { (contentPart, actionsPart), constraints ->
        val width = constraints.maxWidth
        val buttons = actionsPart.first().measure(Constraints(maxWidth = width))
        val text = contentPart.first()
        if (width - buttons.width >= text.minIntrinsicWidth(Constraints.Infinity)) {
            val placed = text.measure(Constraints.fixedWidth(width - buttons.width))
            val height = maxOf(placed.height, buttons.height)
            layout(width, height) {
                placed.place(0, (height - placed.height) / 2)
                buttons.place(width - buttons.width, (height - buttons.height) / 2)
            }
        } else {
            val placed = text.measure(Constraints.fixedWidth(width))
            layout(width, placed.height + buttons.height) {
                placed.place(0, 0)
                buttons.place(width - buttons.width, placed.height)
            }
        }
    }
}

/** The last lines a guest job printed, red once it failed; nothing while idle. */
@Composable
fun JobLog(
    state: JobState,
) {
    val log =
        when (state) {
            is JobState.Working -> state.log
            is JobState.Failed -> listOf(stringResource(R.string.home_job_failed)) + state.log
            JobState.Idle -> return
        }
    if (log.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    val style = Mono.copy(color = if (state is JobState.Failed) Palette.red else Palette.muted, fontSize = TextSize.Small)
    // A failure's reason is usually at the end of the line: let it wrap.
    for (line in log.takeLast(4)) BasicText(line, style = style, maxLines = if (state is JobState.Failed) 3 else 1)
}

/** The items of a popup menu, framed like home's edit bar and menu; picking one closes the menu first. */
@Composable
fun MenuItems(
    items: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
    minWidth: Dp,
    itemPadding: Dp,
    /** Above the items, inside the frame: an app's shortcuts ([ShortcutRows]). */
    top: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .widthIn(min = minWidth)
            .frame(2.dp, Palette.accent, Palette.background)
            .padding(vertical = 6.dp),
    ) {
        top()
        for ((label, action) in items) {
            BasicText(
                label,
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDismiss()
                        action()
                    }.padding(horizontal = 18.dp, vertical = itemPadding),
                style = Mono.copy(fontSize = TextSize.Item),
            )
        }
    }
}
