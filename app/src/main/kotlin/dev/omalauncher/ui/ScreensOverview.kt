package dev.omalauncher.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import dev.omalauncher.Runtime
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.Folder
import dev.omalauncher.widgets.HomeItem
import dev.omalauncher.widgets.Shortcut
import dev.omalauncher.widgets.WidgetItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Snapshot of a home screen for safe, non-destructive reordering in the overview UI. */
@Immutable
private data class ScreenSnapshot(
    val id: String,
    val originalPage: Int,
    val items: List<HomeItem>,
)

/**
 * Screen reorganization overview triggered by pinch gesture or from the home menu.
 * Reordering operates on clean snapshots in UI state, allowing users to:
 * - Pick a target position directly via the "move" dropdown.
 * - Nudge left/right via [ < ] and [ > ] buttons.
 * - Drag and drop cards horizontally.
 * - Commit changes atomically via [ save ] or by tapping a screen card to jump to it.
 */
@Composable
fun ScreensOverview(
    runtime: Runtime,
    state: LauncherState,
) {
    if (!state.screensOverviewOpen) return

    val engine = runtime.widgets
    val layout = engine.layout
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    val screens = remember { mutableStateListOf<ScreenSnapshot>() }
    var hasChanges by remember { mutableStateOf(false) }

    // Initialize snapshots ONLY when the overview dialog opens
    LaunchedEffect(state.screensOverviewOpen) {
        if (state.screensOverviewOpen) {
            screens.clear()
            val existingPages = engine.pages.ifEmpty { listOf(1) }
            for (p in existingPages) {
                screens.add(
                    ScreenSnapshot(
                        id = "screen_$p",
                        originalPage = p,
                        items = engine.itemsOn(p),
                    ),
                )
            }
            hasChanges = false
        }
    }

    val cardWidth = 200.dp
    val cardHeight = 360.dp
    val cardGap = 16.dp
    val stepPx = with(density) { (cardWidth + cardGap).toPx() }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val liftScale = 1.05f

    // The lifted card, by id so it survives reorders; it stays lifted while it settles after release.
    var draggingId by remember { mutableStateOf<String?>(null) }
    var fingerDown by remember { mutableStateOf(false) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var lastDragDx by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    fun moveScreen(fromIndex: Int, toIndex: Int) {
        if (draggingId != null) return
        if (fromIndex == toIndex || fromIndex !in screens.indices || toIndex !in screens.indices) return
        val item = screens.removeAt(fromIndex)
        screens.add(toIndex, item)
        hasChanges = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun saveAndClose(targetPageNumber: Int? = null) {
        if (hasChanges) {
            val orderedPages = screens.map { it.originalPage }
            engine.applyScreenOrder(orderedPages)
        }
        if (targetPageNumber != null) {
            state.requestedPageNumber = targetPageNumber
        }
        state.screensOverviewOpen = false
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun swapDragged(from: Int, to: Int) {
        // LazyRow keeps its first visible item in place across moves; pin the scroll position so a
        // swap involving that item doesn't drag the whole row along with it.
        val first = listState.firstVisibleItemIndex
        if (from == first || to == first) {
            listState.requestScrollToItem(first, listState.firstVisibleItemScrollOffset)
        }
        screens.add(to, screens.removeAt(from))
        hasChanges = true
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun followDrag() {
        var curr = screens.indexOfFirst { it.id == draggingId }
        if (curr < 0) return
        while (dragOffsetX > stepPx * 0.5f && curr < screens.lastIndex) {
            swapDragged(curr, curr + 1)
            curr++
            dragOffsetX -= stepPx
        }
        while (dragOffsetX < -stepPx * 0.5f && curr > 0) {
            swapDragged(curr, curr - 1)
            curr--
            dragOffsetX += stepPx
        }
    }

    fun startDrag(id: String) {
        settleJob?.cancel()
        if (draggingId != id) dragOffsetX = 0f
        draggingId = id
        fingerDown = true
        lastDragDx = 0f
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun dragBy(dx: Float) {
        dragOffsetX += dx
        if (dx != 0f) lastDragDx = dx
        followDrag()
    }

    fun endDrag() {
        fingerDown = false
        val from = dragOffsetX
        settleJob = scope.launch {
            animate(from, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> dragOffsetX = v }
            draggingId = null
        }
    }

    // Holding a card near either edge scrolls the row, so it can travel past what's on screen.
    LaunchedEffect(fingerDown) {
        if (!fingerDown) return@LaunchedEffect
        val edge = with(density) { 56.dp.toPx() }
        val maxSpeed = with(density) { 16.dp.toPx() }
        while (true) {
            withFrameNanos { }
            val info = listState.layoutInfo
            val card = info.visibleItemsInfo.firstOrNull { it.key == draggingId } ?: continue
            val left = card.offset + dragOffsetX
            val right = left + card.size
            val speed = when {
                lastDragDx < 0f && left < info.viewportStartOffset + edge ->
                    -maxSpeed * ((info.viewportStartOffset + edge - left) / edge).coerceAtMost(1f)
                lastDragDx > 0f && right > info.viewportEndOffset - edge ->
                    maxSpeed * ((right - info.viewportEndOffset + edge) / edge).coerceAtMost(1f)
                else -> 0f
            }
            if (speed != 0f) {
                // The card's slot moves with the content; shift it back so it stays under the finger.
                dragOffsetX += listState.scrollBy(speed)
                followDrag()
            }
        }
    }

    // The header keeps only the title (and ✕, which closes without saving): with the actions beside it, a portrait
    // phone cut the title down to "arr…". The actions sit under the screens instead.
    OverlayPanel(
        title = "arrange screens",
        onDismiss = { state.screensOverviewOpen = false },
    ) {
        Column(Modifier.fillMaxSize()) {
            val hint = Mono.copy(color = Palette.muted, fontSize = TextSize.Small)
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText("· drag a screen by its handle, or long-press it", style = hint)
                BasicText("· [ < ] [ > ] move it one place, [ move ▾ ] anywhere", style = hint)
                BasicText("· tap a screen to save and go to it", style = hint)
            }

            LazyRow(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                userScrollEnabled = !fingerDown,
                horizontalArrangement = Arrangement.spacedBy(cardGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(screens, key = { _, screen -> screen.id }) { index, screen ->
                    val isCurrent = screen.originalPage == state.currentPage
                    val isDragging = draggingId == screen.id

                    var showMoveMenu by remember { mutableStateOf(false) }

                    val cardModifier = Modifier
                        .animateItem(
                            placementSpec = if (isDragging) {
                                null
                            } else {
                                spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
                            },
                        )
                        .width(cardWidth)
                        .height(cardHeight)
                        .zIndex(if (isDragging) 10f else 1f)
                        // Long-press anywhere on the card to lift it; outside the lifted layer so its
                        // translation and scale don't skew the drag deltas.
                        .pointerInput(screen.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { startDrag(screen.id) },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragBy(dragAmount.x)
                                },
                                onDragEnd = { endDrag() },
                                onDragCancel = { endDrag() },
                            )
                        }
                        .graphicsLayer {
                            if (isDragging) {
                                translationX = dragOffsetX
                                scaleX = liftScale
                                scaleY = liftScale
                                shadowElevation = 16f
                            }
                        }
                        .clip(LauncherStyle.shape(14.dp))
                        .background(Palette.surface)
                        .border(
                            width = if (isCurrent) 2.dp else 1.dp,
                            color = if (isCurrent) Palette.accent else Palette.border,
                            shape = LauncherStyle.shape(14.dp),
                        )
                        .padding(10.dp)

                    Column(cardModifier) {
                        // Header: Screen number & Move dropdown
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BasicText(
                                    "Screen ${index + 1}",
                                    style = Mono.copy(
                                        fontSize = TextSize.Item,
                                        color = if (isCurrent) Palette.accent else Palette.foreground,
                                    ),
                                )
                                if (isCurrent) {
                                    Spacer(Modifier.width(4.dp))
                                    BasicText("★", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small))
                                }
                            }

                            // Dropdown: Move directly to position
                            Box {
                                Box(
                                    Modifier
                                        .clip(LauncherStyle.shape(6.dp))
                                        .background(Palette.background)
                                        .clickable { showMoveMenu = !showMoveMenu }
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                ) {
                                    BasicText("move ▾", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small))
                                }

                                if (showMoveMenu) {
                                    Popup(
                                        popupPositionProvider = DropdownPosition,
                                        onDismissRequest = { showMoveMenu = false },
                                        properties = PopupProperties(focusable = true),
                                    ) {
                                        Column(
                                            Modifier
                                                .width(IntrinsicSize.Max)
                                                .widthIn(min = 160.dp)
                                                .frame(2.dp, Palette.accent, Palette.background)
                                                .padding(4.dp),
                                        ) {
                                            for (targetPos in screens.indices) {
                                                val isSelected = targetPos == index
                                                Row(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            showMoveMenu = false
                                                            moveScreen(index, targetPos)
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    BasicText(if (isSelected) "● " else "  ", style = Mono.copy(color = Palette.accent))
                                                    BasicText(
                                                        "Screen ${targetPos + 1}",
                                                        style = Mono.copy(
                                                            color = if (isSelected) Palette.accent else Palette.foreground,
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Screen Thumbnail Preview (Tap to Jump & Save)
                        BoxWithConstraints(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(LauncherStyle.shape(8.dp))
                                .background(Palette.background)
                                .border(1.dp, Palette.border.copy(alpha = 0.5f), LauncherStyle.shape(8.dp))
                                .clickable {
                                    // Releasing a long-press lift also lands here as a tap.
                                    if (draggingId == null) saveAndClose(targetPageNumber = index + 1)
                                }
                                .padding(4.dp),
                        ) {
                            val availableW = constraints.maxWidth.toFloat()
                            val availableH = constraints.maxHeight.toFloat()
                            val totalCols = layout.columns
                            val totalRows = 10f
                            val colW = availableW / totalCols
                            val rowH = availableH / totalRows

                            if (screen.items.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    BasicText(
                                        "[ empty screen ]",
                                        style = Mono.copy(
                                            color = Palette.muted,
                                            fontSize = TextSize.Small,
                                            textAlign = TextAlign.Center,
                                        ),
                                    )
                                }
                            } else {
                                for (item in screen.items) {
                                    val cell = layout.cell(item.key) ?: Cell(0, 0, 1, 1)
                                    val itemH = if (item.resizable) cell.h else 1
                                    val left = cell.x * colW
                                    val top = cell.y * rowH
                                    val w = (cell.w * colW - 2f).coerceAtLeast(4f)
                                    val h = (itemH * rowH - 2f).coerceAtLeast(4f)

                                    val itemLabel = when (item) {
                                        is WidgetItem -> item.spec.name
                                        is Shortcut -> item.label
                                        is Folder -> item.apps.firstOrNull()?.label ?: "folder"
                                    }

                                    Box(
                                        Modifier
                                            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                                            .size(
                                                width = with(density) { w.toDp() },
                                                height = with(density) { h.toDp() },
                                            )
                                            .clip(LauncherStyle.shape(3.dp))
                                            .background(Palette.surface)
                                            .border(1.dp, Palette.border, LauncherStyle.shape(3.dp))
                                            .padding(horizontal = 2.dp, vertical = 1.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        BasicText(
                                            itemLabel,
                                            style = Mono.copy(
                                                fontSize = TextSize.Small,
                                                color = Palette.foreground,
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Controls: Move Left, Drag Handle, Move Right, Delete
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            // Move Left [ < ]
                            if (index > 0) {
                                Box(
                                    Modifier
                                        .clip(LauncherStyle.shape(6.dp))
                                        .background(Palette.background)
                                        .clickable {
                                            moveScreen(index, index - 1)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    BasicText("<", style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Item))
                                }
                            } else {
                                Spacer(Modifier.width(28.dp))
                            }

                            // Drag Handle (Horizontal Drag & Drop)
                            Box(
                                Modifier
                                    .clip(LauncherStyle.shape(6.dp))
                                    .background(if (isDragging) Palette.accent else Palette.background)
                                    .pointerInput(screen.id) {
                                        detectHorizontalDragGestures(
                                            onDragStart = { startDrag(screen.id) },
                                            onHorizontalDrag = { change, dragAmount ->
                                                change.consume()
                                                // The handle sits inside the scaled card, so its deltas
                                                // come in shrunk by the lift scale.
                                                dragBy(dragAmount * liftScale)
                                            },
                                            onDragEnd = { endDrag() },
                                            onDragCancel = { endDrag() },
                                        )
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                BasicText(
                                    "::: drag :::",
                                    style = Mono.copy(
                                        color = if (isDragging) Palette.background else Palette.muted,
                                        fontSize = TextSize.Small,
                                    ),
                                )
                            }

                            // Move Right [ > ] or Delete [ × ]
                            if (index < screens.lastIndex) {
                                Box(
                                    Modifier
                                        .clip(LauncherStyle.shape(6.dp))
                                        .background(Palette.background)
                                        .clickable {
                                            moveScreen(index, index + 1)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    BasicText(">", style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Item))
                                }
                            } else if (screen.items.isEmpty() && screens.size > 1) {
                                Box(
                                    Modifier
                                        .clip(LauncherStyle.shape(6.dp))
                                        .background(Palette.background)
                                        .clickable(enabled = draggingId == null) {
                                            screens.removeAt(index)
                                            hasChanges = true
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    BasicText("×", style = Mono.copy(color = Palette.red, fontSize = TextSize.Item))
                                }
                            } else {
                                Spacer(Modifier.width(28.dp))
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BracketAction("+ screen", Palette.accent) {
                    val nextP = (screens.maxOfOrNull { it.originalPage } ?: 0) + 1
                    screens.add(ScreenSnapshot("screen_$nextP", nextP, emptyList()))
                    hasChanges = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                Spacer(Modifier.weight(1f))
                BracketAction("cancel", Palette.muted) { state.screensOverviewOpen = false }
                BracketAction("save", if (hasChanges) Palette.green else Palette.accent) { saveAndClose() }
            }
        }
    }
}

private object DropdownPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ) = IntOffset(
        (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        (anchorBounds.bottom + 6).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
}
