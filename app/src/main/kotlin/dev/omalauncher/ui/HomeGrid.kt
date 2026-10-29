package dev.omalauncher.ui

import androidx.compose.runtime.CompositionLocalProvider
import dev.omalauncher.widgets.render.LocalHomeCovered
import dev.omalauncher.widgets.render.LocalLongPressClaim
import dev.omalauncher.widgets.render.LongPressClaim
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.foundation.pager.PagerState
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.zIndex
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.isFacePage
import dev.omalauncher.widgets.Folder
import dev.omalauncher.widgets.Shortcut
import dev.omalauncher.widgets.HomeItem
import dev.omalauncher.widgets.WidgetLayout
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.omalauncher.R
import androidx.compose.ui.unit.toOffset
import kotlinx.coroutines.launch

/**
 * Pixel geometry of the home grid: cells separated by Omarchy-style gaps, as many rows of
 * square-ish cells as fit, but never fewer than the [used] rows tiles were placed on. A layout
 * made on a tall screen keeps its arrangement on a squarer one (a foldable's inner screen) with
 * shorter cells, rather than tiles stacking. [turned] (landscape) lays portrait's grid on its
 * side: the same cells, [rows] of them across and [cols] down, so tiles keep their place and
 * shape. Everything else here works in those portrait cells.
 *
 * [per] pages stand side by side, each [width] wide (two on a foldable's inner screen); a page's
 * cells are in its own coordinates, [local] and [screen] convert.
 *
 * [gap] is between tiles; [pad] is around them on each side of a page: the gap to the screen's
 * edge, or the gap between tiles on the side the strip and the bar follow.
 */
@Immutable
private class Grid(
    val width: Int,
    height: Int,
    val gap: Int,
    val pad: GridPad,
    val cols: Int,
    val turned: Boolean = false,
    portraitRows: Int? = null,
    used: Int = 0,
    val per: Int = 1,
) {
    val height = height

    /** The room the cells share across and down: a cell is a tile and the gap after it, so the last gap is the pad's. */
    private val across = width - pad.left - pad.right + gap
    private val down = height - pad.top - pad.bottom + gap

    val rows = maxOf(if (turned) portraitRows ?: squareRows(down, across, cols) else squareRows(across, down, cols), used)

    /** A screen cell's width and height, the gap after it included. */
    val cellW = across / (if (turned) rows else cols).toFloat()
    val cellH = down / (if (turned) cols else rows).toFloat()

    // Rounded, not truncated: rows * cellH can come out a hair under the grid's height, which would leave the
    // bottom row a pixel short of the strip below.
    fun rect(c: Cell) =
        if (turned) {
            rect(c.y, c.x, c.bottom, c.right)
        } else {
            rect(c.x, c.y, c.right, c.bottom)
        }

    /** Where [item] shows at [c]: a face fills its page edge to edge, without the gaps around tiles. */
    fun rect(
        item: HomeItem,
        c: Cell,
    ) = if (item.isFace) IntRect(0, 0, width, height) else rect(c)

    /** Screen cells [x0] to [x1] across and [y0] to [y1] down, less the gap after the last. */
    private fun rect(
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) = IntRect(
        (pad.left + x0 * cellW).roundToInt(),
        (pad.top + y0 * cellH).roundToInt(),
        (pad.left + x1 * cellW - gap).roundToInt(),
        (pad.top + y1 * cellH - gap).roundToInt(),
    )

    /** A saved cell as shown on this grid; only affects display, the saved cell stays as it is. */
    fun fit(
        c: Cell,
        item: HomeItem,
    ): Cell {
        val w = if (item.resizable) c.w.coerceIn(1, cols) else 1
        val h = if (item.resizable) c.h.coerceIn(1, rows) else 1
        return Cell(c.x.coerceIn(0, cols - w), c.y.coerceIn(0, rows - h), w, h)
    }

    /** Nearest column line to a point on screen. */
    fun colLine(p: Offset) = (if (turned) line(p.y, pad.top, cellH) else line(p.x, pad.left, cellW)).coerceIn(0, cols)

    fun rowLine(p: Offset) = (if (turned) line(p.x, pad.left, cellW) else line(p.y, pad.top, cellH)).coerceIn(0, rows)

    /** Lines run down the middle of the gaps between cells. */
    private fun line(
        px: Float,
        start: Int,
        cell: Float,
    ) = ((px - start + gap / 2f) / cell).roundToInt()

    /** The tile edges under the screen edges a press grabbed. */
    fun edges(e: Edges) = if (turned) Edges(left = e.top, top = e.left, right = e.bottom, bottom = e.right) else e

    /** Which of the pages on screen, left to right, a point is over. */
    fun side(p: Offset) = (p.x / width).toInt().coerceIn(0, per - 1)

    /** A point on screen in the coordinates of the page at [side]. */
    fun local(
        p: Offset,
        side: Int,
    ) = p - Offset((side * width).toFloat(), 0f)

    /** A rect on the page at [side] in screen coordinates. */
    fun screen(
        r: IntRect,
        side: Int,
    ) = r.translate(side * width, 0)
}

/**
 * How many rows of square-ish cells fit [long] beside [cols] of them across [short]. A window narrower than
 * the gaps around it leaves no room across: one row then, and never more than [MAX_CELL_ASPECT] times [cols],
 * or a sliver would make millions of them.
 */
private fun squareRows(
    short: Int,
    long: Int,
    cols: Int,
) = if (short <= 0) 1 else (long / (short / cols.toFloat())).toInt().coerceIn(1, MAX_CELL_ASPECT * cols)

/** Cells four times as tall as wide: far past any phone's shape (20:9 makes 13 rows of 6 columns). */
private const val MAX_CELL_ASPECT = 4

/** [items], one page's, with the cells they show at on [g]: a tile its saved cell, the face that owns the page every cell. */
private fun WidgetLayout.placed(
    items: List<HomeItem>,
    g: Grid,
): List<Pair<HomeItem, Cell>> {
    val owner = owner(items)?.key
    return items.mapNotNull { item -> if (item.key == owner) item to Cell(0, 0, g.cols, g.rows) else cell(item.key)?.let { item to g.fit(it, item) } }
}

/** Room around a page's tiles, in pixels; see [Grid]. */
@Immutable
internal data class GridPad(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal fun IntRect.has(p: Offset) = p.x >= left && p.x < right && p.y >= top && p.y < bottom

/** Which edges of the selected tile a press grabs; a corner grabs two. */
private data class Edges(
    val left: Boolean,
    val top: Boolean,
    val right: Boolean,
    val bottom: Boolean,
) {
    val any get() = left || top || right || bottom
}

/**
 * Edges within [grab] of the frame, inside or just outside it (the handles sit on the border).
 * Side edges reach [sideGrab] inward: at the screen edge there's no outside to grab, and the
 * system back gesture claims the outermost strip.
 */
private fun edgesAt(
    rect: IntRect,
    p: Offset,
    grab: Int,
    sideGrab: Int,
): Edges {
    val inY = p.y > rect.top - grab && p.y < rect.bottom + grab
    val inX = p.x > rect.left - grab && p.x < rect.right + grab
    // Small tiles keep a movable middle: the grab zone reaches at most a third into the tile.
    val gx = minOf(sideGrab, rect.width / 3)
    val gy = minOf(grab, rect.height / 3)
    return Edges(
        left = inY && p.x > rect.left - grab && p.x < rect.left + gx,
        top = inX && p.y > rect.top - grab && p.y < rect.top + gy,
        right = inY && p.x < rect.right + grab && p.x > rect.right - gx,
        bottom = inX && p.y < rect.bottom + grab && p.y > rect.bottom - gy,
    )
}

/** A tile a finger is carrying, possibly across pages. [grab] is where the finger holds it, from its top-left corner. */
internal class Carry(
    val item: HomeItem,
    val from: Int,
    val cell: Cell,
    val grab: Offset,
)

/** Arranging state shared by the pages, since a carried tile outlives the page it came from. */
@Stable
internal class Arrange {
    var carry by mutableStateOf<Carry?>(null)

    /** The finger, in page coordinates. */
    var finger by mutableStateOf(Offset.Zero)

    /** The page the finger is over, where [preview] and [swapWith] apply. */
    var page by mutableStateOf(0)
    var preview by mutableStateOf<Cell?>(null)
    var swapWith by mutableStateOf<String?>(null)

    /** Dropping now puts the carried app into a folder with [swapWith], rather than swapping. */
    var grouping by mutableStateOf(false)

    /** The open folder's key; its panel covers the grid until a tap outside, Back, or an app lifted out of it. */
    var folder by mutableStateOf<String?>(null)

    /** The open folder's name is being typed, in its header. */
    var naming by mutableStateOf(false)

    /** Where the selected tile's action bar is, in page coordinates; taps there go to the bar, not the editor. */
    var bar by mutableStateOf<IntRect?>(null)

    /** A finger is holding a tile (lifted, dragging or resizing); the action bar waits for release. */
    var holding by mutableStateOf(false)

    fun drop() {
        carry = null
        preview = null
        swapWith = null
        grouping = false
    }
}

/**
 * The home pages, edited like a stock Android home screen: long-press a tile to
 * lift it and drag it right away, or release to select it. A selected widget
 * shows resize handles; drag them (or the frame) to resize, drag the tile to
 * move it, tap outside or press Back to finish. Hold a carried tile against a
 * side edge to turn the page; the last page is always an empty one. Drop an app
 * on another app to put both in a folder, or on a folder to add it; tap a folder
 * to open it, tap its name to type a new one, and long-press an app in it to drag it
 * to another place in it, or back out.
 *
 * Gestures live here, around the pager, rather than on each page: a drag that
 * turns the page has to survive leaving the page it started on. [room] at the
 * bottom has no tiles but takes swipes and long-presses like empty space; what's
 * drawn over it (the recent apps strip) can take them first. [turned] lays
 * portrait's grid on its side, for landscape.
 *
 * The pager counts [slots], the pages each of its positions shows (see slotsOf). With [per] of 2 (an
 * unfolded foldable) a slot shows two tile pages side by side, so every tile keeps the size it has folded,
 * or one [full] page (a face's) across the whole width.
 */
@Composable
internal fun HomeGrid(
    pager: PagerState,
    /** What tiles keep clear of on every side, in pixels: the system bars, and the bar or the side rail. */
    keepClear: GridPad,
    slots: List<List<Int>>,
    /** A face's page, or the empty one waiting for a face: alone in its slot, it takes the whole width. */
    full: (Int) -> Boolean,
    itemsOn: (Int) -> List<HomeItem>,
    layout: WidgetLayout,
    arrange: Arrange,
    selected: String?,
    onSelect: (String?) -> Unit,
    render: @Composable (HomeItem, Modifier, Boolean) -> Unit,
    actions: @Composable (HomeItem) -> Unit,
    onLongPressEmpty: () -> Unit,
    /** What a page left of the first ([isFacePage]) shows while no face is there; it gets the page's number. */
    feed: @Composable (Int) -> Unit,
    /** What an empty tile page shows while no tile is carried. */
    invite: @Composable () -> Unit,
    onNoRoom: (HomeItem) -> Unit,
    onMove: (HomeItem, Int) -> Unit,
    onGroup: (Shortcut, HomeItem) -> Unit,
    /** Moves an app within its folder to just before another of its apps, or with null to the end. */
    onReorder: (Shortcut, String?) -> Unit,
    /** Names a folder as its name is typed; blank for none. */
    onRenameFolder: (Folder, String) -> Unit,
    modifier: Modifier = Modifier,
    room: Dp = 0.dp,
    turned: Boolean = false,
    /** Where the strip and the bar stand beside the grid; null for below it. */
    side: HomeSide? = null,
    per: Int = 1,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val claims = remember { LongPressClaim() }
    // Each tile's composition, by its key, moves to wherever the tile is drawn: lifted, it goes above the pager and back
    // keeping its state, rather than being made anew each way (a terminal tile its view).
    val latestRender by rememberUpdatedState(render)
    val tiles = remember { HashMap<String, MovableTile>() }
    val tile: @Composable (HomeItem, Modifier, Boolean) -> Unit =
        remember {
            { item, modifier, selected ->
                // A tile whose page the pager set aside is made anew: moved, it brought its deactivated nodes along and
                // crashed measuring or updating them (Play, 0.12.1).
                val held = tiles[item.key]?.takeUnless { it.setAside }
                val t = held ?: MovableTile { t, i, m, s -> latestRender(i, m.then(TileAttached(t)), s) }.also { tiles[item.key] = it }
                t.content(item, modifier, selected)
            }
        }
    // Gone from home, their compositions go too.
    val latestSlots by rememberUpdatedState(slots)
    val latestItemsOn by rememberUpdatedState(itemsOn)
    LaunchedEffect(tiles) {
        snapshotFlow { latestSlots.flatten().flatMap(latestItemsOn).flatMapTo(HashSet()) { listOf(it.key) + (it as? Folder)?.apps?.map { a -> a.key }.orEmpty() } }
            .collect { tiles.keys.retainAll(it) }
    }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val gap = with(density) { HomeGap.roundToPx() }
        val outer = with(density) { EdgeGap.roundToPx() }
        // The screen's edge on every side but the one the strip and the bar follow, which gets the gap between tiles.
        // The grid stops [room] short of the bottom; the pages and their gestures go on below it, and a face
        // over all of it.
        val pad =
            GridPad(
                left = keepClear.left + if (side == HomeSide.Left) gap else outer,
                top = keepClear.top + outer,
                right = keepClear.right + if (side == HomeSide.Right) gap else outer,
                bottom = keepClear.bottom + with(density) { room.roundToPx() } + if (side == null) gap else outer,
            )
        val height = constraints.maxHeight
        val width = constraints.maxWidth / per
        // Landscape turns portrait's grid on its side; the window's size (short side first, so both orientations
        // share it) remembers how many rows portrait had.
        val window = LocalWindowInfo.current.containerSize
        val windowSize = "${minOf(window.width, window.height)}x${maxOf(window.width, window.height)}"
        // Rows the tiles on every page reach down to (apps show as 1x1); the pages share one grid.
        // Derived, so moving or resizing a tile recomposes only its page, unless it changes how far down they reach.
        val used by remember(slots, itemsOn, layout) {
            derivedStateOf { slots.flatten().flatMap(itemsOn).maxOfOrNull { item -> layout.cell(item.key)?.let { if (item.resizable) it.bottom else it.y + 1 } ?: 0 } ?: 0 }
        }
        val cols = layout.columns
        val grid =
            remember(width, height, gap, pad, cols, turned, windowSize, used, per) {
                // Not seen in portrait yet: square cells across.
                Grid(width, height, gap, pad, cols, turned, if (turned) layout.portraitRows(windowSize) else null, used, per)
            }
        // A face's slot, unfolded: one page the whole width, on the same rows, since placing a page sets the grid faces get.
        val wide =
            remember(grid, constraints.maxWidth) {
                if (per == 1) grid else Grid(constraints.maxWidth, height, gap, pad, cols, turned, if (turned) layout.portraitRows(windowSize) else null, grid.rows)
            }
        if (!turned) SideEffect { layout.setPortraitRows(windowSize, grid.rows) }
        // Previews draw tiles at the size they have here.
        SideEffect {
            layout.geometry = Triple(grid.cellW, grid.cellH, gap)
            layout.pageSize = wide.width to wide.height
            layout.turned = turned
        }
        val grab = with(density) { 20.dp.roundToPx() }
        val sideGrab = with(density) { 36.dp.roundToPx() }
        val edge = with(density) { 28.dp.roundToPx() }
        val current by rememberUpdatedState(Triple(slots, grid to wide, selected))

        fun placedOn(
            page: Int,
            g: Grid,
        ) = layout.placed(itemsOn(page), g)

        /** The pages a pager slot shows, left to right. */
        fun pagesAt(
            slot: Int,
            slots: List<List<Int>>,
        ) = slots.getOrElse(slot) { listOf(1) }

        /** The grid [shown]'s pages are laid out on: the whole width for a face's slot, unfolded. */
        fun gridFor(
            shown: List<Int>,
            grids: Pair<Grid, Grid>,
        ) = if (shown.size == 1 && full(shown[0])) grids.second else grids.first

        /** Which of [shown] holds [key], or -1. */
        fun sideOf(
            key: String?,
            shown: List<Int>,
        ) = if (key == null) -1 else shown.indexOfFirst { page -> itemsOn(page).any { it.key == key } }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    /**
                     * Follows the finger, carrying [item] from [cell] on page [from]; returns whether it moved.
                     * [origin] is where the tile is on screen: its cell, or for an app lifted out of a folder,
                     * its place in the open folder. Such an app sorts the folder (laid out by [folder]) while the
                     * finger stays over its panel; resting outside it closes the folder and carries the app on.
                     */
                    suspend fun AwaitPointerEventScope.moveDrag(
                        downId: PointerId,
                        start: Offset,
                        item: HomeItem,
                        cell: Cell,
                        from: Int,
                        origin: IntRect,
                        folder: FolderGeometry? = null,
                    ): Boolean {
                        val inFolder = (item as? Shortcut)?.folder != null
                        val hold = start - Offset(origin.left.toFloat(), origin.top.toFloat())
                        var moved = false
                        var position = start
                        // When the finger reached a side edge; the page turns after it rests there a moment.
                        var atEdgeSince = 0L
                        // Sorting the open folder: the place the finger is over and since when, and since when it's off the panel.
                        var sorting = folder
                        var aim = -1
                        var aimSince = 0L
                        var outSince = 0L
                        while (true) {
                            val waiting = atEdgeSince > 0 || aimSince > 0 || outSince > 0
                            val event = withTimeoutOrNull(if (waiting) 50L else Long.MAX_VALUE) { awaitPointerEvent(PointerEventPass.Initial) }
                            if (event != null) {
                                val c = event.changes.firstOrNull { it.id == downId } ?: break
                                c.consume()
                                if (!c.pressed) break
                                position = c.position
                            }
                            if (!moved && (position - start).getDistance() > viewConfiguration.touchSlop) {
                                moved = true
                                arrange.carry = Carry(item, from, cell, hold)
                            }
                            if (!moved) continue
                            arrange.finger = position
                            val now = SystemClock.uptimeMillis()
                            val fg = sorting
                            if (fg != null) {
                                if (fg.panel.has(position)) {
                                    outSince = 0L
                                    val apps = (itemsOn(from).firstOrNull { it.key == (item as Shortcut).folder } as? Folder)?.apps.orEmpty()
                                    val to = apps.indices.minByOrNull { (fg.app(it).center.toOffset() - position).getDistanceSquared() } ?: continue
                                    when {
                                        to == apps.indexOfFirst { it.key == item.key } -> aimSince = 0L
                                        to != aim || aimSince == 0L -> aimSince = now
                                        // Like Android, the other apps make way once the finger rests over a place a moment.
                                        now - aimSince > REORDER_MS -> {
                                            onReorder(item as Shortcut, apps.filter { it.key != item.key }.getOrNull(to)?.key)
                                            aimSince = 0L
                                        }
                                    }
                                    aim = to
                                    continue
                                }
                                aimSince = 0L
                                if (outSince == 0L) outSince = now
                                if (now - outSince <= FOLDER_EXIT_MS) continue
                                // Like Android, the folder closes once the app rests outside it, and the app goes on to the grid.
                                arrange.folder = null
                                sorting = null
                                outSince = 0L
                            }
                            val edgeSide = if (position.x < edge) -1 else if (position.x > size.width - edge) 1 else 0
                            when {
                                edgeSide == 0 -> atEdgeSince = 0L
                                atEdgeSince == 0L -> atEdgeSince = now
                                now - atEdgeSince > PAGE_TURN_MS && !pager.isScrollInProgress -> {
                                    val next = pager.currentPage + edgeSide
                                    if (next in 0 until pager.pageCount) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        scope.launch { pager.animateScrollToPage(next) }
                                    }
                                    // Turning again takes a longer rest, so the page just reached can be looked at.
                                    atEdgeSince = now + PAGE_TURN_MS
                                }
                            }
                            val (slots, grids) = current
                            // Side by side, the finger picks the page; the drop preview works in its coordinates. Over the
                            // empty half beside a lone tile page nothing lands.
                            val over = pagesAt(pager.targetPage, slots)
                            val g = gridFor(over, grids)
                            val side = g.side(position)
                            val at = g.local(position, side)
                            val page = over.getOrNull(side)
                            if (page == null) {
                                arrange.page = from
                                arrange.preview = null
                                arrange.swapWith = null
                                arrange.grouping = false
                                continue
                            }
                            arrange.page = page
                            val others = placedOn(page, g).filter { it.first.key != item.key }
                            val under = others.lastOrNull { (o, r) -> g.rect(o, r).has(at) }
                            // Apps group with apps and folders; anything else swaps, except an app from a folder, which has no cell to
                            // trade, and a face, whose page isn't a cell either: one lands only on an empty page, and nothing on its.
                            val groups = item is Shortcut && (under?.first is Shortcut || under?.first is Folder)
                            val target = under?.takeIf { groups || (!inFolder && !item.isFace && !it.first.isFace) }
                            // Left of page 1 only faces go.
                            val onlyFaces = isFacePage(page) && !item.isFace
                            arrange.grouping = groups
                            arrange.swapWith = target?.first?.key
                            arrange.preview =
                                if (onlyFaces) {
                                    null
                                } else if (under != null) {
                                    target?.second
                                } else {
                                    val x = g.colLine(at - hold).coerceIn(0, (g.cols - cell.w).coerceAtLeast(0))
                                    val y = g.rowLine(at - hold).coerceIn(0, (g.rows - cell.h).coerceAtLeast(0))
                                    Cell(x, y, cell.w, cell.h).takeIf { n -> others.none { it.second.overlaps(n) } }
                                }
                        }
                        val page = arrange.page
                        val target = arrange.swapWith?.let { key -> itemsOn(page).firstOrNull { it.key == key } }
                        val drop = arrange.preview
                        val grouping = arrange.grouping
                        arrange.drop()
                        when {
                            target != null && grouping && moved -> onGroup(item as Shortcut, target)
                            // Swapping trades places and sizes, like swapping windows in Hyprland; across pages it trades pages too.
                            target != null ->
                                layout.cell(target.key)?.let { other ->
                                    layout.set(target.key, cell)
                                    layout.set(item.key, other)
                                    if (page != from) {
                                        onMove(target, from)
                                        onMove(item, page)
                                    }
                                }
                            drop != null && moved -> {
                                if (!item.isFace) layout.set(item.key, drop)
                                if (page != from || inFolder) onMove(item, page)
                            }
                        }
                        layout.save()
                        return moved
                    }

                    /** Waits out a long press without taking the touch, so a tap still reaches the tile; false if it ended or moved first. */
                    suspend fun AwaitPointerEventScope.awaitLongPress(
                        downId: PointerId,
                        start: Offset,
                    ) = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == downId } ?: return@withTimeoutOrNull
                            if (!c.pressed || (c.position - start).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull
                        }
                    } == null

                    /** Takes [downId]'s moves until it lifts, giving [onMove] each position while pressed; where it lifted, or null if it went away. */
                    suspend fun AwaitPointerEventScope.trackUntilUp(
                        downId: PointerId,
                        onMove: (Offset) -> Unit = {},
                    ): Offset? {
                        while (true) {
                            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == downId } ?: return null
                            c.consume()
                            if (!c.pressed) return c.position
                            onMove(c.position)
                        }
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val start = down.position
                        val (slots, grids, sel) = current
                        val shown = pagesAt(pager.currentPage, slots)
                        val g = gridFor(shown, grids)
                        val side = g.side(start)
                        // The empty half beside a lone tile page is empty space.
                        val page = shown.getOrElse(side) { shown[0] }
                        val at = g.local(start, side)
                        val placed = if (side < shown.size) placedOn(page, g) else emptyList()

                        // An open folder takes every touch: a tap on an app opens it (the tile gets the tap),
                        // a long-press lifts it to sort it in or drag it out, or let go where it was shows its bar, a tap on its name types a new one,
                        // a tap outside closes the folder. Its panel is centered on the whole screen, even with two pages showing.
                        val folderPage = shown.getOrNull(sideOf(arrange.folder, shown))
                        val open = folderPage?.let { p -> itemsOn(p).firstOrNull { it.key == arrange.folder } as? Folder }
                        if (open == null) arrange.folder = null
                        if (open != null && folderPage != null) {
                            val fg = FolderGeometry(size.width, g.height, g.cellW, g.cellH, g.gap, open.apps.size, this)
                            val hit = open.apps.indices.firstOrNull { fg.app(it).has(start) }
                            // With one of its apps selected, as on home: the bar's taps are its buttons', the app drags at once, and any other touch puts it down.
                            if (open.apps.any { it.key == sel }) {
                                if (arrange.bar?.has(start) == true) return@awaitEachGesture
                                down.consume()
                                if (hit != null && open.apps[hit].key == sel) {
                                    arrange.holding = true
                                    val moved =
                                        try {
                                            moveDrag(down.id, start, open.apps[hit], Cell(0, 0, 1, 1), folderPage, fg.app(hit), fg)
                                        } finally {
                                            arrange.holding = false
                                        }
                                    if (moved) onSelect(null)
                                } else {
                                    onSelect(null)
                                    trackUntilUp(down.id)
                                }
                                return@awaitEachGesture
                            }
                            if (hit == null) {
                                down.consume()
                                if (!fg.panel.has(start)) arrange.folder = null
                                trackUntilUp(down.id)?.let { up -> arrange.naming = fg.header.has(start) && fg.header.has(up) }
                                return@awaitEachGesture
                            }
                            if (!awaitLongPress(down.id, start)) return@awaitEachGesture
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            arrange.holding = true
                            val moved =
                                try {
                                    moveDrag(down.id, start, open.apps[hit], Cell(0, 0, 1, 1), folderPage, fg.app(hit), fg)
                                } finally {
                                    arrange.holding = false
                                }
                            if (!moved) onSelect(open.apps[hit].key)
                            return@awaitEachGesture
                        }

                        // Edit mode: every touch belongs to the editor, nothing reaches the tiles. The selected
                        // tile may be on the other page of a pair; the editor works in its page's coordinates.
                        val selSide = sideOf(sel, shown)
                        val selectedItem = shown.getOrNull(selSide)?.let { p -> placedOn(p, g).firstOrNull { it.first.key == sel } }
                        val selAt = g.local(start, selSide.coerceAtLeast(0))
                        // Except the action bar: leave its taps unconsumed so its buttons get them.
                        if (selectedItem != null && arrange.bar?.has(selAt) == true) return@awaitEachGesture
                        if (selectedItem != null) {
                            down.consume()
                            val (item, cell) = selectedItem
                            arrange.holding = true
                            try {
                                val rect = g.rect(item, cell)
                                val edges = if (item.resizable) g.edges(edgesAt(rect, selAt, grab, sideGrab)) else Edges(false, false, false, false)
                                when {
                                    edges.any -> {
                                        val others = placed.filter { it.first.key != item.key }.map { it.second }
                                        trackUntilUp(down.id) { position ->
                                            val minW = item.minSize.first.coerceAtMost(g.cols)
                                            val minH = item.minSize.second
                                            val p = g.local(position, selSide)
                                            val x0 = if (edges.left) g.colLine(p).coerceAtMost(cell.right - minW) else cell.x
                                            val x1 = if (edges.right) g.colLine(p).coerceAtLeast(cell.x + minW) else cell.right
                                            val y0 = if (edges.top) g.rowLine(p).coerceAtMost(cell.bottom - minH) else cell.y
                                            val y1 = if (edges.bottom) g.rowLine(p).coerceAtLeast(cell.y + minH) else cell.bottom
                                            val next = Cell(x0, y0, x1 - x0, y1 - y0)
                                            // Growing into another tile is refused; the frame just stops there.
                                            if (others.none { it.overlaps(next) }) layout.set(item.key, next)
                                        }
                                        layout.save()
                                    }
                                    rect.has(selAt) -> moveDrag(down.id, start, item, cell, shown[selSide], g.screen(rect, selSide))
                                    else -> {
                                        // Tap outside finishes editing; a drag outside does nothing.
                                        onSelect(null)
                                        trackUntilUp(down.id)
                                    }
                                }
                            } finally {
                                arrange.holding = false
                            }
                            return@awaitEachGesture
                        }

                        val hit = placed.lastOrNull { (i, c) -> g.rect(i, c).has(at) }
                        // A face's long presses are its own nodes' (onLongPress), which it's not lifted by (it's
                        // arranged in settings › pages). One that none of them takes opens the menu, as empty space does:
                        // they take it as the press times out, so give them a moment to say so.
                        if (hit?.first?.isFace == true) {
                            if (!awaitLongPress(down.id, start)) return@awaitEachGesture
                            val held = SystemClock.uptimeMillis()
                            withTimeoutOrNull(CLAIM_MS) { while (true) awaitPointerEvent(PointerEventPass.Initial) }
                            if (claims.at < held - viewConfiguration.longPressTimeoutMillis) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongPressEmpty()
                            }
                            return@awaitEachGesture
                        }
                        if (!awaitLongPress(down.id, start)) return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (hit == null) {
                            onLongPressEmpty()
                            return@awaitEachGesture
                        }
                        // Lifted: select it, and let the same finger drag it without letting go.
                        onSelect(hit.first.key)
                        arrange.holding = true
                        val moved =
                            try {
                                moveDrag(down.id, start, hit.first, hit.second, page, g.screen(g.rect(hit.first, hit.second), side))
                            } finally {
                                arrange.holding = false
                            }
                        // Like Android, a drag drops and finishes; a plain long-press stays in edit mode.
                        if (moved) onSelect(null)
                    }
                },
        ) {
            // A face's nodes say here when they take a long press, so the menu doesn't open for it too.
            CompositionLocalProvider(LocalLongPressClaim provides claims) {
                // The pages either side are made ahead, so a swipe doesn't start by composing the one it brings in. Their
                // animated widgets wait until some of the page shows.
                val homeCovered = LocalHomeCovered.current
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { slots.getOrNull(it)?.first() ?: -it }) { slot ->
                    val shown = pagesAt(slot, slots)
                    val g = gridFor(shown, grid to wide)
                    val covered = remember(homeCovered, pager, slot) { { homeCovered() || pager.layoutInfo.visiblePagesInfo.none { it.index == slot } } }
                    CompositionLocalProvider(LocalHomeCovered provides covered) {
                        // Keys stay on the page showing: the first arrow on home went to the first page made, one of these.
                        Row(Modifier.fillMaxSize().focusProperties { onEnter = { if (slot != pager.currentPage) cancelFocusChange() } }.focusGroup()) {
                            for (page in shown) {
                                Box(Modifier.width(with(density) { g.width.toDp() }).fillMaxHeight()) {
                                    TiledPage(page, itemsOn(page), layout, g, arrange, selected, tile, actions, onNoRoom, feed, invite)
                                }
                            }
                        }
                    }
                }
            }

            // The open folder, over the page it's on.
            val openFolder = arrange.folder?.let { key -> pagesAt(pager.currentPage, slots).firstNotNullOfOrNull { p -> itemsOn(p).firstOrNull { it.key == key } as? Folder } }
            if (openFolder != null) {
                val fg = FolderGeometry(width * per, grid.height, grid.cellW, grid.cellH, gap, openFolder.apps.size, density)
                FolderPanel(
                    openFolder,
                    fg,
                    tile,
                    arrange.carry?.item?.key,
                    selected = selected.takeIf { !arrange.holding },
                    actions = actions,
                    onBar = { arrange.bar = it },
                    naming = arrange.naming,
                    onName = { onRenameFolder(openFolder, it) },
                    onNamed = { arrange.naming = false },
                )
            }

            // The carried tile floats above the pager, so it stays put while pages turn, and above an open folder it's sorting.
            arrange.carry?.let { carry ->
                val r = grid.rect(carry.item, carry.cell)
                androidx.compose.runtime.key(carry.item.key) {
                    tile(
                        carry.item,
                        Modifier
                            // The finger is read while placing, so following it doesn't recompose the pages.
                            .offset {
                                val at = arrange.finger - carry.grab
                                IntOffset(at.x.roundToInt(), at.y.roundToInt())
                            }
                            .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                            .zIndex(5f)
                            .graphicsLayer {
                                scaleX = 0.96f
                                scaleY = 0.96f
                                alpha = 0.85f
                            },
                        true,
                    )
                }
            }
        }
    }
}

/**
 * Where a carried tile would land on [page]. Its own scope: the preview follows the finger from cell to
 * cell, and reading it in the page would recompose every tile on it each time.
 */
@Composable
private fun DropPreview(
    page: Int,
    grid: Grid,
    arrange: Arrange,
) {
    if (arrange.page != page) return
    val cell = arrange.preview ?: return
    val density = LocalDensity.current
    val r = grid.rect(cell)
    Box(
        Modifier
            .offset { IntOffset(r.left, r.top) }
            .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
            // Grouping fills more, so it reads as "into" rather than "in place of".
            .frame(2.dp, Palette.accent, Palette.accent.copy(alpha = if (arrange.grouping) 0.35f else 0.12f)),
    )
}

/** How long after a long press times out a face's node may still take it before home opens its menu. */
private const val CLAIM_MS = 100L

/** How long a carried tile rests against a side edge before the page turns. */
private const val PAGE_TURN_MS = 600L

/** How long an app dragged in its open folder rests over another place before the apps make way; Launcher3's REORDER_DELAY. */
private const val REORDER_MS = 250L

/** How long it rests outside the folder's panel before the folder closes; Launcher3's ON_EXIT_CLOSE_DELAY. */
private const val FOLDER_EXIT_MS = 400L

/** One home page: its tiles, and while arranging the grid guides, drop preview and selection frame. */
@Composable
private fun TiledPage(
    page: Int,
    items: List<HomeItem>,
    layout: WidgetLayout,
    grid: Grid,
    arrange: Arrange,
    selected: String?,
    render: @Composable (HomeItem, Modifier, Boolean) -> Unit,
    actions: @Composable (HomeItem) -> Unit,
    onNoRoom: (HomeItem) -> Unit,
    feed: @Composable (Int) -> Unit,
    invite: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val carried = arrange.carry?.item?.key
    LaunchedEffect(items.map { it.key }, grid.cols, grid.rows) {
        layout.place(items, grid.cols, grid.rows).forEach(onNoRoom)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageWidth = constraints.maxWidth
        // A face selected on its page has no cells to arrange.
        val faceSelected = items.any { it.key == selected && it.isFace }
        if ((selected != null && !faceSelected) || (carried != null && arrange.folder == null)) {
            // Grid guides while arranging; not while sorting an open folder, which covers the grid.
            Canvas(Modifier.fillMaxSize()) {
                for (x in 0 until grid.cols) {
                    for (y in 0 until grid.rows) {
                        val r = grid.rect(Cell(x, y, 1, 1))
                        drawRect(
                            Palette.border.copy(alpha = 0.35f),
                            topLeft = Offset(r.left.toFloat(), r.top.toFloat()),
                            size = androidx.compose.ui.geometry.Size(r.width.toFloat(), r.height.toFloat()),
                            style = Stroke(1.dp.toPx()),
                        )
                    }
                }
            }
        }
        if (isFacePage(page) && carried == null && items.isEmpty()) {
            feed(page)
        } else if (items.none { it.key != carried } && (carried != null || LauncherStyle.emptyPageRight || page == 1)) {
            // An empty page is the one after the last, or unfolded the blank half beside an odd last page, but for page 1
            // of an empty home. With home.empty-page-right off, that half stays blank rather than inviting a new page.
            if (carried == null && !isFacePage(page)) {
                invite()
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BasicText(
                        stringResource(if (isFacePage(page) && arrange.carry?.item?.isFace != true) R.string.home_drop_face_only else R.string.home_drop_here),
                        Modifier.background(Palette.background.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 6.dp),
                        style = Mono,
                    )
                }
            }
        }
        if (carried != null) DropPreview(page, grid, arrange)
        val placed = layout.placed(items, grid)
        for ((item, cell) in placed) {
            // The carried tile is drawn above the pager instead.
            if (item.key == carried) continue
            val rect = grid.rect(item, cell)
            androidx.compose.runtime.key(item.key) {
                render(
                    item,
                    Modifier
                        .offset { IntOffset(rect.left, rect.top) }
                        .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() }),
                    selected == item.key || arrange.swapWith == item.key,
                )
            }
        }
        // Selection frame: handles on each edge for widgets, plus the action bar.
        val (sel, selCell) = placed.firstOrNull { it.first.key == selected } ?: (null to null)
        if (sel != null && selCell != null && carried == null) {
            val r = grid.rect(sel, selCell)
            val handle = with(density) { 14.dp.roundToPx() }
            if (sel.resizable) {
                Canvas(Modifier.fillMaxSize().zIndex(2f)) {
                    drawRect(
                        Palette.accent,
                        topLeft = Offset(r.left.toFloat(), r.top.toFloat()),
                        size = androidx.compose.ui.geometry.Size(r.width.toFloat(), r.height.toFloat()),
                        style = Stroke(3.dp.toPx()),
                    )
                    for (p in listOf(
                        Offset(r.center.x.toFloat(), r.top.toFloat()),
                        Offset(r.center.x.toFloat(), r.bottom.toFloat()),
                        Offset(r.left.toFloat(), r.center.y.toFloat()),
                        Offset(r.right.toFloat(), r.center.y.toFloat()),
                    )) {
                        drawCircle(Palette.background, handle / 2f + 2.dp.toPx(), p)
                        drawCircle(Palette.accent, handle / 2f, p)
                    }
                }
                // Keep the back gesture off the side handles, so a tile against the screen edge can be resized.
                // Android honours at most 200dp of exclusion per edge.
                val zoneW = with(density) { 48.dp.roundToPx() }
                val zoneH = minOf(r.height, with(density) { 160.dp.roundToPx() })
                for (x in listOf(r.left, r.right)) {
                    Box(
                        Modifier
                            .offset { IntOffset(x - zoneW / 2, r.center.y - zoneH / 2) }
                            .size(with(density) { zoneW.toDp() }, with(density) { zoneH.toDp() })
                            .systemGestureExclusion(),
                    )
                }
            }
            if (!arrange.holding) {
                // Above the tile, below the status bar, or below the tile when there's no room for it there: as tall as it turned out, since an
                // app's shortcuts stack above its bar. Lined up with the tile's left edge, pulled in by however wide
                // the bar turned out when that would run past the grid's edge, which keeps the gap tiles keep from the
                // screen: a lone "remove" for an app at the edge stays next to it. A tile with room on neither side (one
                // filling the page) gets it inside, centred below the top handle, where it covers no handle's grab zone;
                // below the grid it would sit under the strip and take the bottom handle's touches.
                val barGap = with(density) { 8.dp.roundToPx() }
                val inset = with(density) { 28.dp.roundToPx() }
                fun at(size: IntSize): IntOffset {
                    val above = r.top - size.height - barGap
                    val below = r.bottom + barGap
                    return when {
                        above >= grid.pad.top -> IntOffset(r.left.coerceAtMost(pageWidth - grid.pad.right - size.width).coerceAtLeast(grid.pad.left), above)
                        below + size.height <= grid.height - grid.pad.bottom + grid.gap -> IntOffset(r.left.coerceAtMost(pageWidth - grid.pad.right - size.width).coerceAtLeast(grid.pad.left), below)
                        else -> IntOffset(r.center.x - size.width / 2, r.top + inset)
                    }
                }
                DisposableEffect(Unit) { onDispose { arrange.bar = null } }
                Box(
                    Modifier
                        .zIndex(3f)
                        .layout { measurable, constraints ->
                            val bar = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                            layout(bar.width, bar.height) { bar.place(at(IntSize(bar.width, bar.height))) }
                        }
                        .onSizeChanged { arrange.bar = IntRect(at(it), it) },
                ) { actions(sel) }
            }
        }
    }
}

/**
 * A home tile's composition, which moves to wherever the tile is drawn. The pager keeps pages it scrolled away from for
 * reuse with their nodes deactivated, which detaches the tile's modifier node: such a tile is [setAside].
 */
private class MovableTile(
    render: @Composable (MovableTile, HomeItem, Modifier, Boolean) -> Unit,
) {
    val content = movableContentOf { item: HomeItem, modifier: Modifier, selected: Boolean -> render(this, item, modifier, selected) }

    /** The node [TileAttached] put on the tile; null until it's first drawn. */
    var node: Modifier.Node? = null

    val setAside get() = node?.isAttached == false
}

private class TileAttached(
    private val tile: MovableTile,
) : ModifierNodeElement<Modifier.Node>() {
    override fun create() = object : Modifier.Node() {}.also { tile.node = it }

    override fun update(node: Modifier.Node) {
        tile.node = node
    }

    override fun equals(other: Any?) = other is TileAttached && other.tile === tile

    override fun hashCode() = System.identityHashCode(tile)
}
