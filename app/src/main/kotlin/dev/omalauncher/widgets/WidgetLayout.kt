package dev.omalauncher.widgets

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.mapEntries
import org.json.JSONObject

/** A widget's place on its page's grid, in cells. */
data class Cell(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
) {
    val right get() = x + w
    val bottom get() = y + h

    fun overlaps(o: Cell) = x < o.right && o.x < right && y < o.bottom && o.y < bottom

    /**
     * This cell, placed on a grid of [from] columns, moved onto one of [to]: its left edge and width scale down
     * to whole cells, and it stays at least [minWidth] wide, or the whole width, by moving left.
     */
    fun rescaled(
        from: Int,
        to: Int,
        minWidth: Int = 1,
    ): Cell {
        if (from == to) return this
        val x = x * to / from
        val w = maxOf((w * to / from).coerceIn(1, to - x), minWidth.coerceAtMost(to))
        return copy(x = x.coerceAtMost(to - w), w = w)
    }
}

/**
 * The page left of the first, like a stock launcher's feed; pages further left count down from it. Each holds one face
 * ([HomeItem.isFace]) and no tiles, and an empty one always waits left of the leftmost.
 */
const val FACE_PAGE = 0

/** Left of page 1: a face's place, never a tile's. */
fun isFacePage(page: Int) = page <= FACE_PAGE

/** The home grid's columns before they could be set: a saved layout that doesn't say has this many. */
const val CLASSIC_COLUMNS = 6

/**
 * A screen home has a layout of its own: the phone's, or a foldable's cover screen, where a layout made
 * for the inner screen would squeeze every tile into a third of its height. [key] names it in saved
 * data and layout.toml's `[cover]` section; [MAIN] is the top of the file, as before there were two.
 */
enum class Screen(
    val key: String,
) {
    MAIN("main"),
    COVER("cover"),
}

/**
 * Where each widget and pinned app sits on the home grid, keyed by file name
 * (or shortcut key), and the page a widget was moved to on the phone; one set
 * per [Screen], of which [screen] is the one home shows and every call here
 * reads and writes unless it says otherwise. Kept apart
 * from the widget files, so rearranging never rewrites the files agents own:
 * [HomeStore] writes it all to layout.toml, which agents may edit instead.
 */
class WidgetLayout(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("widget_grid", Context.MODE_PRIVATE)
    private val cellsOf = Screen.entries.associateWith { mutableStateMapOf<String, Cell>() }
    /** Read while compiling widgets, off the main thread. */
    private val pagesOf = Screen.entries.associateWith { java.util.concurrent.ConcurrentHashMap<String, Int>() }

    /** The screen home is on; see [show]. State, so what lists home's items by page follows it. */
    var screen: Screen by mutableStateOf(Screen.MAIN)
        private set

    /**
     * Counts page changes. The pages aren't snapshot state (they're read while compiling, off the main thread), so what
     * derives an app's page on another screen than the main one from them reads this to follow them; see [Shortcuts.onGrid].
     */
    var pageStamp by mutableIntStateOf(0)
        private set

    private val cells get() = cellsOf.getValue(screen)
    private val pages get() = pagesOf.getValue(screen)

    /** Whether [screen] has a layout of its own yet: a cell or a page chosen for it. Without one, home lays its tiles out afresh there. */
    fun has(screen: Screen) = cellsOf.getValue(screen).isNotEmpty() || pagesOf.getValue(screen).isNotEmpty()

    /**
     * Home moves to [screen]: its cells and pages are the ones read and written from here on. On a screen without a
     * layout of its own yet, every tile is on the page it has on the main screen (see [chosenPage]) and has no cell: its
     * grid lays them out afresh at their own size, and what doesn't fit moves on to the next page there.
     */
    fun show(screen: Screen) {
        if (screen == this.screen) return
        this.screen = screen
        pageStamp++
    }

    /** Forgets [screen]'s layout; shown, it starts over from the main screen's pages as in [show]. */
    fun reset(screen: Screen) {
        cellsOf.getValue(screen).clear()
        pagesOf.getValue(screen).clear()
        pageStamp++
        save()
    }

    /** Columns of the home grid the cells are on; saved with them, so [regrid] can move them onto another. */
    var columns by mutableIntStateOf(CLASSIC_COLUMNS)
        private set

    init {
        (Versioned.read(prefs.getString(LAYOUT, null), VERSION) as? JSONObject)?.let { data ->
            columns = columnsOf(data)
            load(data)
        }
    }

    private fun columnsOf(data: JSONObject) = data.optInt("columns", CLASSIC_COLUMNS).takeIf { it > 0 } ?: CLASSIC_COLUMNS

    private fun load(data: JSONObject) {
        val saved = columnsOf(data)
        // The main screen's are "cells" and "pages", as saved before there were two screens; the others' carry the screen's key.
        for (screen in Screen.entries) {
            val suffix = if (screen == Screen.MAIN) "" else "_${screen.key}"
            data.optJSONObject("cells$suffix")?.mapEntries { _, c -> Cell(c.getInt("x"), c.getInt("y"), c.getInt("w"), c.getInt("h")).rescaled(saved, columns) }?.let(cellsOf.getValue(screen)::putAll)
            data.optJSONObject("pages$suffix")?.let { p -> for (k in p.keys()) pagesOf.getValue(screen)[k] = p.optInt(k, 1) }
        }
    }

    /**
     * Moves every cell onto a grid of [to] columns (see [Cell.rescaled]); a widget stays as wide as its
     * [HomeItem.minSize] allows. A tile that lands on one before it on its page in [tiles] (each page's, in the
     * order they keep their spots) loses its cell, so its page places it afresh in its first free spot, or moves
     * it to a page with room. The other screens' cells move onto the new grid too; their pages aren't laid out now, so
     * which of them then overlap is settled when that screen shows.
     */
    fun regrid(
        to: Int,
        tiles: List<List<HomeItem>>,
    ) {
        if (to == columns) return
        val minWidth = tiles.flatten().associate { it.key to it.minSize.first }
        for (screenCells in cellsOf.values) for ((key, c) in screenCells.toMap()) screenCells[key] = c.rescaled(columns, to, minWidth[key] ?: 1)
        columns = to
        for (items in tiles) {
            val kept = ArrayList<Cell>()
            for (item in items) {
                val c = cells[item.key]?.let { if (item.resizable) it else it.copy(w = 1, h = 1) } ?: continue
                if (kept.any { it.overlaps(c) }) cells.remove(item.key) else kept += c
            }
        }
        save()
    }

    /** The page [name] was moved to on the phone, if it was; on [screen], the one shown unless said. */
    fun page(
        name: String,
        screen: Screen = this.screen,
    ): Int? = pagesOf.getValue(screen)[name]

    /**
     * The page [name] is on on [screen] by choice: the one chosen for it there, or, on another screen than the main
     * one, the one chosen for it on the main screen, so a screen without a layout of its own starts from the main one's
     * pages; null when neither was, and it goes by its file.
     */
    fun chosenPage(
        name: String,
        screen: Screen = this.screen,
    ): Int? = pagesOf.getValue(screen)[name] ?: if (screen == Screen.MAIN) null else pagesOf.getValue(Screen.MAIN)[name]

    fun setPage(
        name: String,
        page: Int,
        screen: Screen = this.screen,
    ) {
        pagesOf.getValue(screen)[name] = page
        pageStamp++
        save()
    }

    fun cell(
        name: String,
        screen: Screen = this.screen,
    ) = cellsOf.getValue(screen)[name]

    /** Live update while dragging; [save] persists. An unchanged cell isn't written, so pointer moves within it don't recompose the page. */
    fun set(
        name: String,
        cell: Cell,
        screen: Screen = this.screen,
    ) {
        val cells = cellsOf.getValue(screen)
        if (cells[name] != cell) cells[name] = cell
    }

    /**
     * Rows the home grid had in portrait on a window of [size] (its short and long side, so both
     * orientations share it). Landscape turns that grid on its side, so every tile keeps its cell.
     */
    fun portraitRows(size: String) = prefs.getInt(PORTRAIT_ROWS + size, 0).takeIf { it > 0 }

    fun setPortraitRows(
        size: String,
        rows: Int,
    ) {
        if (portraitRows(size) != rows) prefs.edit().putInt(PORTRAIT_ROWS + size, rows).apply()
    }

    /** The grid last laid out on screen, columns and rows; every page shares it. */
    var grid: Pair<Int, Int>? = null
        private set

    /**
     * The face ([HomeItem.isFace]) that [items], one page's, show: the first of them, while no tile
     * there has a cell. A face keeps no cell, so the tiles that were there first keep the page, and
     * a widget that becomes a face (or back) never overlaps them or comes back as a tile the page's size.
     * One placed on the phone comes before one there by its file's `page:` alone, so a new face file
     * (`page: 0` by default) doesn't push the face already on that page away.
     */
    fun owner(items: List<HomeItem>): HomeItem? {
        val face = items.filter { it.isFace }.sortedBy { !pages.containsKey(it.key) }.firstOrNull() ?: return null
        return face.takeIf { items.none { !it.isFace && cells[it.key] != null } }
    }

    /**
     * Gives widgets that have no cell yet one at their declared size, in the
     * first free spot. Saved cells are never rewritten here: a temporarily
     * small page (keyboard, rotation) must not destroy the user's layout.
     *
     * Returns the items that don't fit at their size; they get no cell, so
     * the caller can move them to a page with room instead of squeezing them
     * in or stacking them on top of other tiles. That includes apps pinned to
     * a full page, such as page 1 of a fresh install, and on a face's page everything but it: the
     * tiles had no cell there yet. A face that finds tiles on its page is homeless itself.
     */
    fun place(
        items: List<HomeItem>,
        cols: Int,
        rows: Int,
    ): List<HomeItem> {
        grid = cols to rows
        val owner = owner(items)
        if (owner != null) return items.filter { it.key != owner.key }
        var changed = false
        val homeless = ArrayList<HomeItem>()
        for (item in items) {
            if (item.isFace) {
                homeless += item
                continue
            }
            if (cells[item.key] != null) continue
            val spot = freeSpot(item, items, cols, rows)
            if (spot == null) {
                homeless += item
                continue
            }
            cells[item.key] = spot
            changed = true
        }
        if (changed) save()
        return homeless
    }

    /**
     * Gives [item] a free spot at its size among [others]; false when there's none, or no page was laid out yet.
     * Those of [others] without a cell yet (their page hasn't been laid out, or is being laid out this very frame,
     * with two pages showing) get theirs first: the page's own tiles keep their spots, the newcomer takes what's left.
     * A face claims only an empty page, and takes no cell; nothing claims a spot beside one.
     */
    fun claim(
        item: HomeItem,
        others: List<HomeItem>,
    ): Boolean {
        val (cols, rows) = grid ?: return false
        val rest = others.filter { it.key != item.key }
        if (item.isFace) return rest.isEmpty()
        if (rest.any { it.isFace }) return false
        place(rest, cols, rows)
        cells[item.key] = freeSpot(item, others, cols, rows) ?: return false
        save()
        return true
    }

    /** First spot where [item] fits at its size beside the placed [items]; null when the page is full. */
    private fun freeSpot(
        item: HomeItem,
        items: List<HomeItem>,
        cols: Int,
        rows: Int,
    ): Cell? {
        // Shortcuts always show as 1x1, whatever an old saved cell says.
        val others = items.mapNotNull { other -> if (other.key == item.key) null else cells[other.key]?.let { if (other.resizable) it else it.copy(w = 1, h = 1) } }
        val (pw, ph) = item.size ?: (cols / 2 to 3)
        val w = pw.coerceIn(1, cols)
        val h = ph.coerceIn(1, rows)
        for (y in 0..rows - h) for (x in 0..cols - w) {
            val c = Cell(x, y, w, h)
            if (others.none { it.overlaps(c) }) return c
        }
        return null
    }

    /** Takes [name]'s cell, so its page places it afresh in its first free spot. */
    fun clearCell(
        name: String,
        screen: Screen = this.screen,
    ) {
        cellsOf.getValue(screen).remove(name)
    }

    /** Forgets everything about [name] on every screen: its cells and pages. */
    fun remove(name: String) {
        for (c in cellsOf.values) c.remove(name)
        for (p in pagesOf.values) p.remove(name)
        pageStamp++
        save()
    }

    /** The home grid's cell width and height on screen and the gap between tiles, in pixels, as last laid out. */
    @Volatile var geometry: Triple<Float, Float, Int>? = null

    /** A home page's width and height in pixels, as last laid out: what a face fills. */
    @Volatile var pageSize: Pair<Int, Int>? = null

    /** Whether the home grid was last laid out on its side (landscape): a tile's columns run down the screen. */
    @Volatile var turned = false

    /** A tile's size in pixels at [cells]; before the home grid was laid out, an estimate from the screen width. */
    fun tilePx(
        cells: Pair<Int, Int>,
        metrics: android.util.DisplayMetrics,
    ): Pair<Int, Int> {
        val (cellW, cellH, gap) =
            geometry ?: run {
                val gap = Math.round(6 * metrics.density)
                val cell = (metrics.widthPixels - gap) / columns.toFloat()
                Triple(cell, cell, gap)
            }
        val (across, down) = if (turned) cells.second to cells.first else cells
        return (across * cellW).toInt() - gap to (down * cellH).toInt() - gap
    }

    /** A face's size in pixels; before home was laid out, an estimate: the screen less the status bar, strip and bar. */
    fun pagePx(metrics: android.util.DisplayMetrics) = pageSize ?: (metrics.widthPixels to (metrics.heightPixels * 0.8f).toInt())

    fun save() {
        val data = JSONObject().put("columns", columns)
        for (screen in Screen.entries) {
            val suffix = if (screen == Screen.MAIN) "" else "_${screen.key}"
            val json = JSONObject()
            for ((name, c) in cellsOf.getValue(screen)) json.put(name, JSONObject().put("x", c.x).put("y", c.y).put("w", c.w).put("h", c.h))
            data.put("cells$suffix", json).put("pages$suffix", JSONObject(pagesOf.getValue(screen) as Map<*, *>))
        }
        prefs.edit().putString(LAYOUT, Versioned.write(VERSION, data)).apply()
        onSave()
    }

    /** Called after every save; [HomeStore] writes layout.toml then. */
    var onSave: () -> Unit = {}

    private companion object {
        const val LAYOUT = "layout"
        const val VERSION = 1
        const val PORTRAIT_ROWS = "portrait_rows_"
    }
}
