package dev.omalauncher.ui

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.omalauncher.Runtime
import dev.omalauncher.widgets.HomeItem
import dev.omalauncher.widgets.WidgetItem
import dev.omalauncher.widgets.WidgetSpec
import dev.omalauncher.widgets.editWidgetCommand

/**
 * Home's pages, left to right: the pages of tiles and the faces (`face "…" { }`), whose long presses are
 * their own, so they're arranged here. Drag a row by its handle to move the page; letting go numbers home's pages again
 * ([dev.omalauncher.widgets.WidgetEngine.arrangePages]). A face can be edited with the agent ([onAgent]) or
 * shared ([onShare]); any page can be removed, after asking.
 */
@Composable
internal fun PagesScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
    onAgent: (title: String, command: (bin: String) -> String) -> Unit,
    onShare: (WidgetSpec) -> Unit,
) {
    val engine = runtime.widgets
    val pages = engine.pages
    // While a row is dragged, the order it's shown in; otherwise home's own. One state for good: the handles' gestures
    // outlive compositions, and one keyed by the page list left them moving rows in a list no longer shown.
    var order by remember { mutableStateOf(pages) }
    var dragged by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Int, Int>() }
    var removing by remember { mutableStateOf<Int?>(null) }
    val haptics = LocalHapticFeedback.current
    val gap = with(LocalDensity.current) { RowGap.roundToPx() }
    OverlayPanel("pages", onDismiss, contentWidth = ReadingWidth) {
        BasicText(
            "Home's pages, left to right. Drag one by ≡ to move it. Home opens on the first with tiles; faces before it sit left of it.",
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
        Spacer(Modifier.height(12.dp))
        // Opened on its own after the process died, settings comes before the engine has read the widgets: the pages
        // it would show are only those of the pinned apps, and rows would move under the finger as the rest arrive.
        if (!engine.loaded) {
            // Home starts the engine; settings opened without it (after the process died) has to, or this would wait forever.
            LaunchedEffect(Unit) { runtime.start() }
            BasicText("loading…", style = Mono.copy(color = Palette.muted))
            return@OverlayPanel
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(RowGap)) {
            for (page in if (dragged == null) pages else order) {
                key(page) {
                    val lifted = dragged == page
                    PageRow(
                        page = page,
                        items = engine.itemsOn(page),
                        owner = engine.layout.owner(engine.itemsOn(page)) as? WidgetItem,
                        lifted = lifted,
                        onAgent = onAgent,
                        onShare = onShare,
                        onRemove = { removing = page },
                        modifier =
                                Modifier
                                    .zIndex(if (lifted) 1f else 0f)
                                    .graphicsLayer { translationY = if (lifted) offset else 0f }
                                    .onSizeChanged { heights[page] = it.height },
                        // The handle drags at once, without waiting for a long press; the rest of the row scrolls the list.
                        handle =
                            Modifier.pointerInput(page) {
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            order = engine.pages
                                            dragged = page
                                            offset = 0f
                                        },
                                        onVerticalDrag = { change, dy ->
                                            change.consume()
                                            offset += dy
                                            // Past the middle of the row beside it, the two trade places. A row whose page
                                            // went or was renumbered since it was composed isn't in the list to move.
                                            val i = order.indexOf(page)
                                            if (i < 0) return@detectVerticalDragGestures
                                            val next = order.getOrNull(i + 1)
                                            val prev = order.getOrNull(i - 1)
                                            val nextHeight = next?.let { heights[it] } ?: 0
                                            val prevHeight = prev?.let { heights[it] } ?: 0
                                            if (next != null && offset > nextHeight / 2f) {
                                                order = order.toMutableList().apply { add(i + 1, removeAt(i)) }
                                                offset -= nextHeight + gap
                                            } else if (prev != null && offset < -prevHeight / 2f) {
                                                order = order.toMutableList().apply { add(i - 1, removeAt(i)) }
                                                offset += prevHeight + gap
                                            }
                                        },
                                        onDragEnd = {
                                            dragged = null
                                            offset = 0f
                                            // Read now, not as composed: the drag outlives the composition it started in.
                                            if (order != engine.pages) engine.arrangePages(order)
                                        },
                                        onDragCancel = {
                                            dragged = null
                                            offset = 0f
                                        },
                                    )
                                },
                    )
                }
            }
        }
    }
    removing?.let { page ->
        val items = engine.itemsOn(page)
        val owner = engine.layout.owner(items) as? WidgetItem
        ConfirmDialog(
            if (owner != null) "remove ${owner.spec.name}?" else "remove this page?",
            if (owner != null) {
                "Its file goes to .removed/ in the widgets folder."
            } else {
                "${pageContents(items)}. Its widgets go to .removed/ in the widgets folder; its apps come off home and stay installed."
            },
            "remove",
            onDismiss = { removing = null },
        ) {
            removing = null
            engine.removePage(page)
        }
    }
}

private val RowGap = 8.dp

/** What's on a page of tiles: its widgets by name, then how many apps. */
private fun pageContents(items: List<HomeItem>): String {
    val widgets = items.filterIsInstance<WidgetItem>().map { it.spec.name }
    val apps = items.size - widgets.size
    return (widgets + listOfNotNull(apps.takeIf { it > 0 }?.let { if (it == 1) "1 app" else "$it apps" })).joinToString(", ").ifEmpty { "empty" }
}

/** One page: a face by its name, a page of tiles by what's on it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageRow(
    page: Int,
    items: List<HomeItem>,
    owner: WidgetItem?,
    lifted: Boolean,
    onAgent: (title: String, command: (bin: String) -> String) -> Unit,
    onShare: (WidgetSpec) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
    /** The handle's drag. */
    handle: Modifier,
) {
    Row(
        modifier
            .height(IntrinsicSize.Min)
            .frame(if (lifted) 2.dp else 1.dp, if (lifted) Palette.accent else Palette.border, Palette.background, radius = 12.dp)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row's whole height is the handle's to grab, wide enough for a thumb.
        Box(handle.width(56.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            BasicText("≡", style = Mono.copy(color = if (lifted) Palette.accent else Palette.muted, fontSize = 34.sp))
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val spec = owner?.spec
            // By what's on it, not its number, which changes as pages move.
            Row(verticalAlignment = Alignment.Top) {
                BasicText(spec?.name ?: pageContents(items), Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText(
                    when {
                        spec?.error != null -> "doesn't compile"
                        spec != null -> "face"
                        page == 1 -> "home"
                        else -> ""
                    },
                    style = Mono.copy(color = if (spec?.error != null) Palette.red else Palette.muted, fontSize = TextSize.Small),
                )
            }
            // Pulled left by the buttons' own padding, so the first bracket lines up with the name.
            FlowRow(Modifier.offset(x = (-8).dp)) {
                if (spec != null) {
                    BracketAction("edit", Palette.accent) { onAgent("Edit ${spec.name}") { editWidgetCommand(it, spec) } }
                    BracketAction("share", Palette.accent) { onShare(spec) }
                }
                BracketAction("remove", Palette.red, onRemove)
            }
        }
    }
}
