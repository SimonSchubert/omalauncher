package dev.omalauncher.widgets


/** One tile in layout.toml, at [cell] or, without one, in the first free spot. */
sealed interface Tile {
    val cell: Cell?
}

/** A widget, by its file name in widgets/. */
data class WidgetTile(
    val file: String,
    override val cell: Cell? = null,
) : Tile

/** A pinned app, by its key in the file; see [HomeStore]. */
data class AppTile(
    val app: String,
    override val cell: Cell? = null,
) : Tile

/** A folder of apps, in order; a null [name] is an unnamed one, written "". */
data class FolderTile(
    val name: String?,
    val apps: List<String>,
    override val cell: Cell? = null,
) : Tile

/** A home page in layout.toml: a face, filling it, or tiles. */
sealed interface PageEntry

data class FacePage(
    val file: String,
) : PageEntry

data class TilePage(
    val tiles: List<Tile>,
) : PageEntry

/** Home as layout.toml says: its pages left to right, and the columns their cells are on. */
data class HomeLayout(
    val columns: Int,
    val pages: List<PageEntry>,
)

/**
 * layout.toml: where home's tiles are and its pages' order, as a file the launcher writes as you rearrange home,
 * and agents and the user edit. Pages are `[[page]]` tables in order; see [numbers] for the page numbers they get.
 * Keys it doesn't know at the top are kept, as in style.toml.
 */
object HomeFile {
    const val VERSION = 1
    private const val VERSION_KEY = "version"
    private const val COLUMNS = "columns"
    private const val PAGE = "page"

    /** Columns a file may say its cells are on: more than [dev.omalauncher.theme.Style.COLUMNS] takes, for a newer launcher's. */
    private val COLUMN_RANGE = 1..20

    val HEADER =
        """
        |# Home's pages, left to right: a face, or tiles on a grid of `columns` columns (home.columns in
        |# ../style/style.toml sets the grid). Moving things on the phone rewrites this file, and home
        |# follows edits to it. A tile is { widget = "file.owl" }, { app = "…" } or { folder = "name", apps = [...] },
        |# at x, y (cells from the top left) and w, h wide and tall; without x and y it takes the first free spot.
        |# A widget file left out stays where it is, and a new one goes by its own page:, order: and size:;
        |# an app left out comes off home. An app is a package, "pkg/activity" (with "@profile" for a work
        |# profile's), "linux:<desktop id>" or "owl:<file>".
        |# What couldn't be read is in errors.txt next to this file.
        |
        """.trimMargin()

    data class Decoded(
        val layout: HomeLayout,
        val problems: List<String>,
    )

    /**
     * Page numbers for [pages] in order, as [WidgetEngine.arrangePages] numbers them: the first page of tiles is
     * page 1, where home opens, the ones before it 0, -1, ... and the rest 2, 3, ....
     */
    fun numbers(pages: List<PageEntry>): List<Int> {
        val first = pages.indexOfFirst { it is TilePage }.let { if (it < 0) pages.size else it }
        return pages.indices.map { it - first + 1 }
    }

    fun encode(
        table: Map<String, Any>,
        layout: HomeLayout,
    ): LinkedHashMap<String, Any> {
        val out = LinkedHashMap<String, Any>()
        out[VERSION_KEY] = VERSION.toDouble()
        out[COLUMNS] = layout.columns.toDouble()
        for ((k, v) in table) if (k !in listOf(VERSION_KEY, COLUMNS, PAGE)) out[k] = v
        out[PAGE] =
            layout.pages.map { page ->
                when (page) {
                    is FacePage -> linkedMapOf<String, Any>("face" to page.file)
                    is TilePage -> linkedMapOf<String, Any>("tiles" to page.tiles.map(::encodeTile))
                }
            }
        return out
    }

    private fun encodeTile(tile: Tile): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        when (tile) {
            is WidgetTile -> out["widget"] = tile.file
            is AppTile -> out["app"] = tile.app
            is FolderTile -> out["folder"] = tile.name.orEmpty()
        }
        tile.cell?.let { c ->
            out["x"] = c.x.toDouble()
            out["y"] = c.y.toDouble()
            // Apps and folders are always one cell.
            if (tile is WidgetTile) {
                out["w"] = c.w.toDouble()
                out["h"] = c.h.toDouble()
            }
        }
        if (tile is FolderTile) out["apps"] = tile.apps
        return out
    }

    /** The file's [table] as a layout; [columns] is the grid's when the file doesn't say. */
    fun decode(
        table: Map<String, Any>,
        columns: Int,
    ): Decoded {
        val problems = ArrayList<String>()
        val cols =
            when (val v = table[COLUMNS]) {
                null -> columns
                else -> whole(v)?.takeIf { it in COLUMN_RANGE } ?: columns.also { problems += "$COLUMNS: takes a whole number of columns; reading the cells as on $columns" }
            }
        val raw =
            when (val v = table[PAGE]) {
                null -> emptyList()
                is List<*> -> v
                else -> emptyList<Any>().also { problems += "$PAGE: write each page as [[page]]" }
            }
        val pages = ArrayList<PageEntry>()
        raw.forEachIndexed { i, p ->
            val where = "page ${i + 1}"
            if (p !is Map<*, *>) {
                problems += "$where: write each page as [[page]]"
                return@forEachIndexed
            }
            for (k in p.keys) if (k != "face" && k != "tiles") problems += "$where: $k isn't a page's; a page has a face or tiles"
            val face = p["face"]
            if (face != null) {
                if (face is String && face.isNotBlank()) pages += FacePage(face) else problems += "$where: face takes a widget file name in quotes"
            }
            val tiles = p["tiles"]
            if (tiles != null) {
                // A face fills its page: tiles written with it go on a page of their own, right after.
                if (face != null) problems += "$where: a face fills its page; its tiles went on the next"
                if (tiles !is List<*>) {
                    problems += "$where: tiles takes a list, [ { widget = \"…\" }, … ]"
                } else {
                    val decoded = tiles.mapIndexedNotNull { j, t -> tile(t, "$where, tile ${j + 1}", problems) }
                    if (decoded.isNotEmpty()) pages += TilePage(decoded)
                }
            }
        }
        return Decoded(HomeLayout(cols, pages), problems)
    }

    private fun tile(
        t: Any?,
        where: String,
        problems: MutableList<String>,
    ): Tile? {
        if (t !is Map<*, *>) {
            problems += "$where: write a tile as { widget = \"…\" }, { app = \"…\" } or { folder = \"…\", apps = [...] }"
            return null
        }
        val kinds = listOf("widget", "app", "folder").filter { it in t }
        if (kinds.size != 1) {
            problems += "$where: a tile is one of widget, app or folder"
            return null
        }
        val kind = kinds[0]
        val known = setOf(kind, "x", "y", "w", "h") + if (kind == "folder") setOf("apps") else emptySet()
        for (k in t.keys) if (k !in known) problems += "$where: $k isn't a $kind tile's"
        val name = t[kind] as? String
        if (name == null || (kind != "folder" && name.isBlank())) {
            problems += "$where: $kind takes a name in quotes"
            return null
        }
        val cell = cell(t, kind == "widget", where, problems)
        return when (kind) {
            "widget" -> WidgetTile(name, cell)
            "app" -> AppTile(name, cell)
            else -> {
                val apps = (t["apps"] as? List<*>)?.filterIsInstance<String>()?.filter { it.isNotBlank() }.orEmpty()
                if (t["apps"] !is List<*> || apps.size != (t["apps"] as List<*>).size) problems += "$where: apps takes a list of apps in quotes"
                FolderTile(name.trim().ifEmpty { null }, apps, cell)
            }
        }
    }

    private fun cell(
        t: Map<*, *>,
        sized: Boolean,
        where: String,
        problems: MutableList<String>,
    ): Cell? {
        fun get(
            key: String,
            min: Int,
        ): Int? {
            val v = t[key] ?: return null
            return whole(v)?.takeIf { it >= min } ?: null.also { problems += "$where: $key takes a whole number from $min" }
        }
        val x = get("x", 0)
        val y = get("y", 0)
        val w = if (sized) get("w", 1) else null
        val h = if (sized) get("h", 1) else null
        if (x == null || y == null) {
            if ("x" in t || "y" in t || w != null || h != null) problems += "$where: x and y place it, both or neither; it took the first free spot"
            return null
        }
        if ((w == null) != (h == null)) problems += "$where: w and h size it, both or neither"
        // Without a size, it keeps its own; see HomeStore.
        return Cell(x, y, if (w != null && h != null) w else 0, if (w != null && h != null) h else 0)
    }

    private fun whole(v: Any) = (v as? Double)?.takeIf { it == Math.rint(it) && kotlin.math.abs(it) < 1e6 }?.toInt()
}
