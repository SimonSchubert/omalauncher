package dev.omalauncher.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
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
            .padding(HomeGap),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .frame(2.dp, Palette.accent, Palette.background, radius = 20.dp)
                // Taps inside the panel don't reach the scrim behind it.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(PanelPadding),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(title, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title))
                Spacer(Modifier.weight(1f))
                actions()
                Symbol(Glyph.Close, Palette.red, Modifier.clickable(onClick = onDismiss).padding(8.dp), TextSize.Title)
            }
            Spacer(Modifier.height(8.dp))
            if (contentWidth == Dp.Unspecified) {
                content()
            } else {
                Column(Modifier.weight(1f).widthIn(max = contentWidth).fillMaxWidth().align(Alignment.CenterHorizontally), content = content)
            }
        }
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

/** The items of a popup menu; picking one closes the menu first. */
@Composable
fun MenuItems(
    items: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
    minWidth: Dp,
    itemPadding: Dp,
    fontSize: TextUnit,
) {
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .widthIn(min = minWidth)
            .frame(1.dp, Palette.border, Palette.surface)
            .padding(vertical = 6.dp),
    ) {
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
