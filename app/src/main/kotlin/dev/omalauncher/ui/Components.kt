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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.omalauncher.linux.JobState

/**
 * A full-screen framed panel with a title, optional [actions] and a close button: themes, agents, the store.
 * With a [contentWidth], a wide screen centers the content at that width; the frame and title still span the panel.
 */
@Composable
fun OverlayPanel(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    contentWidth: Dp = Dp.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.overlay)
            .clickable(onClick = onDismiss)
            .safeDrawingPadding()
            .padding(PanelGap),
    ) {
        PanelFrame(
            onClose = onDismiss,
            // Taps inside the panel don't reach the scrim behind it.
            modifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
            title = { PanelTitle(title) },
            actions = actions,
            contentWidth = contentWidth,
            content = content,
        )
    }
}

/** [OverlayPanel]'s frame without the scrim, for a screen of its own (Files): [title] fills the header up to the [actions] and close mark. */
@Composable
fun PanelFrame(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
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
            Symbol(Glyph.Close, Palette.red, Modifier.clickable(onClick = onClose).padding(8.dp), TextSize.Title)
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
        Image(Icons.Refresh, if (loading) "refreshing" else "refresh", Modifier.size(20.dp).then(turn), colorFilter = ColorFilter.tint(if (loading) Palette.accent else Palette.muted))
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
    onClick: () -> Unit,
) {
    Box(Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp)) {
        BasicText("[ $label ]", style = Mono.copy(color = color))
    }
}

/** The last lines a guest job printed, red once it failed; nothing while idle. */
@Composable
fun JobLog(
    state: JobState,
    lines: Int = 4,
) {
    val log =
        when (state) {
            is JobState.Working -> state.log
            is JobState.Failed -> listOf("failed:") + state.log
            JobState.Idle -> return
        }
    if (log.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    val style = Mono.copy(color = if (state is JobState.Failed) Palette.red else Palette.muted, fontSize = TextSize.Small)
    // A failure's reason is usually at the end of the line: let it wrap.
    for (line in log.takeLast(lines)) BasicText(line, style = style, maxLines = if (state is JobState.Failed) 3 else 1)
}

/** The items of a popup menu, framed like home's edit bar and menu; picking one closes the menu first. */
@Composable
fun MenuItems(
    items: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
    minWidth: Dp,
    itemPadding: Dp,
    fontSize: TextUnit,
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
                style = Mono.copy(fontSize = fontSize),
            )
        }
    }
}
