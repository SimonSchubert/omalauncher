package dev.omalauncher.widgets

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
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
}

/** Columns of the home grid. Saved with the layout, so changing it rescales saved cells rather than scrambling them. */
const val GRID_COLUMNS = 6

/**
 * Where each widget and pinned app sits on the home grid, keyed by file name
 * (or shortcut key), and the page a widget was moved to on the phone. Kept on
 * the launcher side so rearranging never rewrites the widget files agents own.
 */
class WidgetLayout(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("widget_grid", Context.MODE_PRIVATE)
    private val cells = mutableStateMapOf<String, Cell>()
    /** Read while compiling widgets, off the main thread. */
    private val pages = java.util.concurrent.ConcurrentHashMap<String, Int>()

    init {
        (Versioned.read(prefs.getString(LAYOUT, null), VERSION) as? JSONObject)?.let(::load)
        migrateLegacy(context)
    }

    private fun load(data: JSONObject) {
        val columns = data.optInt("columns", GRID_COLUMNS)
        data.optJSONObject("cells")?.mapEntries { _, c -> rescale(Cell(c.getInt("x"), c.getInt("y"), c.getInt("w"), c.getInt("h")), columns) }?.let(cells::putAll)
        data.optJSONObject("pages")?.let { p -> for (k in p.keys()) pages[k] = p.optInt(k, 1) }
    }

    /**
     * Launchers before versioning kept the cells under "cells" (6 columns) and
     * page overrides in their own preferences. Those are the user's arrangement:
     * they win over anything placed since, and are removed once carried over.
     */
    private fun migrateLegacy(context: Context) {
        val old = prefs.getString(LEGACY_CELLS, null)
        val oldPages = context.getSharedPreferences(LEGACY_PAGES, Context.MODE_PRIVATE)
        if (old == null && oldPages.all.isEmpty()) return
        old?.let { runCatching { load(JSONObject().put("columns", 6).put("cells", JSONObject(it))) } }
        for ((k, v) in oldPages.all) (v as? Int)?.let { pages[k] = it }
        save()
        prefs.edit().remove(LEGACY_CELLS).apply()
        oldPages.edit().clear().apply()
    }

    /** A cell saved on a grid of [columns], moved onto today's. */
    private fun rescale(
        c: Cell,
        columns: Int,
    ): Cell {
        if (columns == GRID_COLUMNS || columns <= 0) return c
        val x = c.x * GRID_COLUMNS / columns
        return c.copy(x = x, w = (c.w * GRID_COLUMNS / columns).coerceIn(1, GRID_COLUMNS - x))
    }

    /** The page [name] was moved to on the phone, if it was. */
    fun page(name: String) = pages[name]

    fun setPage(
        name: String,
        page: Int,
    ) {
        pages[name] = page
        save()
    }

    fun cell(name: String) = cells[name]

    /** Live update while dragging; [save] persists. An unchanged cell isn't written, so pointer moves within it don't recompose the page. */
    fun set(
        name: String,
        cell: Cell,
    ) {
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

    /** The grid last laid out on screen; every page shares it. */
    private var grid: Pair<Int, Int>? = null

    /**
     * Gives widgets that have no cell yet one at their declared size, in the
     * first free spot. Saved cells are never rewritten here: a temporarily
     * small page (keyboard, rotation) must not destroy the user's layout.
     *
     * Returns the items that don't fit at their size; they get no cell, so
     * the caller can move them to a page with room instead of squeezing them
     * in or stacking them on top of other tiles. That includes apps pinned to
     * a full page, such as page 1 of a fresh install.
     */
    fun place(
        items: List<HomeItem>,
        cols: Int,
        rows: Int,
    ): List<HomeItem> {
        grid = cols to rows
        var changed = false
        val homeless = ArrayList<HomeItem>()
        for (item in items) {
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
     */
    fun claim(
        item: HomeItem,
        others: List<HomeItem>,
    ): Boolean {
        val (cols, rows) = grid ?: return false
        place(others.filter { it.key != item.key }, cols, rows)
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

    /** Forgets everything about [name]: its cell and page. */
    fun remove(name: String) {
        cells.remove(name)
        pages.remove(name)
        save()
    }

    /** The home grid's cell width and height on screen and the gap between tiles, in pixels, as last laid out. */
    @Volatile var geometry: Triple<Float, Float, Int>? = null

    /** Whether the home grid was last laid out on its side (landscape): a tile's columns run down the screen. */
    @Volatile var turned = false

    /** A tile's size in pixels at [cells]; before the home grid was laid out, an estimate from the screen width. */
    fun tilePx(
        cells: Pair<Int, Int>,
        metrics: android.util.DisplayMetrics,
    ): Pair<Int, Int> {
        val (cellW, cellH, gap) = cellGeometry(metrics)
        val (across, down) = if (turned) cells.second to cells.first else cells
        return (across * cellW).toInt() - gap to (down * cellH).toInt() - gap
    }

    /** The fewest cells, at most [GRID_COLUMNS] across, whose tile is at least [px] (width, height) big; [tilePx] the other way round. */
    fun cellsFor(
        px: Pair<Int, Int>,
        metrics: android.util.DisplayMetrics,
    ): Pair<Int, Int> {
        val (cellW, cellH, gap) = cellGeometry(metrics)
        val across = kotlin.math.ceil((px.first + gap) / cellW).toInt().coerceAtLeast(1)
        val down = kotlin.math.ceil((px.second + gap) / cellH).toInt().coerceAtLeast(1)
        val (w, h) = if (turned) down to across else across to down
        return w.coerceAtMost(GRID_COLUMNS) to h
    }

    /** [geometry]; before the home grid was laid out, an estimate from the screen width. */
    private fun cellGeometry(metrics: android.util.DisplayMetrics) =
        geometry ?: run {
            val gap = Math.round(6 * metrics.density)
            val cell = (metrics.widthPixels - gap) / GRID_COLUMNS.toFloat()
            Triple(cell, cell, gap)
        }

    fun save() {
        val json = JSONObject()
        for ((name, c) in cells) json.put(name, JSONObject().put("x", c.x).put("y", c.y).put("w", c.w).put("h", c.h))
        val data =
            JSONObject()
                .put("columns", GRID_COLUMNS)
                .put("cells", json)
                .put("pages", JSONObject(pages as Map<*, *>))
        prefs.edit().putString(LAYOUT, Versioned.write(VERSION, data)).apply()
    }

    private companion object {
        const val LAYOUT = "layout"
        const val VERSION = 1
        const val LEGACY_CELLS = "cells"
        const val PORTRAIT_ROWS = "portrait_rows_"
        const val LEGACY_PAGES = "widget_pages"
    }
}
