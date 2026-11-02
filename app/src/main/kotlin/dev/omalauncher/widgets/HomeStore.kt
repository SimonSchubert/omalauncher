package dev.omalauncher.widgets

import android.content.Context
import dev.omalauncher.LauncherData
import dev.omalauncher.Runtime
import dev.omalauncher.widgets.lang.sha256Hex
import dev.omalauncher.theme.Toml
import dev.omalauncher.upgrade.SyncedFile
import java.io.File

/**
 * Home's layout as layout.toml ([HomeFile]), which Linux sees as ~/.config/omalauncher/home/layout.toml: every
 * change on the phone writes it, and an agent or the user editing it moves home. [WidgetLayout] and [Shortcuts] stay
 * what home draws from, and keep their own saved copies for a launcher before this file; the file wins when they
 * differ. Nothing is written or read before the widgets first load, as until then widget pages aren't known. Main thread.
 */
class HomeStore(
    context: Context,
    private val engine: WidgetEngine,
    private val runtime: Runtime,
) {
    private val dir = LauncherData.homeDir(context)
    private val layout get() = engine.layout
    private val shortcuts get() = engine.shortcuts

    /** The file's keys; the ones this launcher doesn't know are written back as they were. */
    private var table: Map<String, Any> = emptyMap()

    private val synced =
        SyncedFile(File(dir, FILE), File(dir, ERRORS), "home's layout") {
            table = HomeFile.encode(table, snapshot())
            Toml.write(table, HomeFile.HEADER)
        }

    /** What errors.txt says, for home to show. */
    val errors get() = synced.errors

    /** Holds the hash of the text last written, to tell at the start whether anyone edited the file since; see [loaded]. */
    private val prefs = LauncherData.prefs(context)

    init {
        synced.onWritten = { text -> prefs.edit().putString(WRITTEN, hash(text)).apply() }
    }

    private var ready = false

    /** Taking in the file: the moves it makes are the file's, not changes to write back. */
    private var applying = false

    /** Home changed on the phone; the file follows a moment later. */
    fun changed() {
        if (ready && !applying) synced.save()
    }

    /** Called once the widgets have loaded: takes in the file, or writes it from what the launcher had saved before it. */
    fun loaded() {
        if (ready) return
        val text = synced.open()
        ready = true
        when {
            text == null -> synced.create()
            // Just as the launcher last wrote it: no one edited it, so it says nothing the saved layout doesn't, which
            // may be newer (the app stopped before a save went out, or a launcher before this file ran in between).
            // The saved layout stays, and the file follows it.
            hash(text) == prefs.getString(WRITTEN, null) -> {
                table = runCatching { Toml.parse(text) }.getOrDefault(emptyMap())
                synced.setProblems(emptyList())
                synced.save()
            }
            else -> read(text, atStart = true)
        }
        synced.start { read(it, atStart = false) }
    }

    /** Takes in the file's [text]; a file that doesn't parse moves nothing and isn't saved over. */
    private fun read(
        text: String,
        atStart: Boolean,
    ) {
        // Deleted, or emptied: written again from home as it is.
        if (text.isBlank()) {
            synced.readable = true
            synced.setProblems(emptyList())
            table = emptyMap()
            synced.save()
            return
        }
        val parsed =
            try {
                Toml.parse(text)
            } catch (e: Toml.Error) {
                synced.readable = false
                synced.setProblems(listOf("$FILE ${e.message}; nothing in it was applied, and moving things on home won't save it until it's fixed"))
                return
            }
        synced.readable = true
        table = parsed
        // Says nothing about pages, as a file of only `version`: nothing to move, and apps left out mustn't all come off.
        if ("page" !in parsed) {
            synced.setProblems(listOf("$FILE: no [[page]]; home stays as it is"))
            return
        }
        val decoded = HomeFile.decode(parsed, layout.columns)
        val problems = ArrayList(decoded.problems)
        val gone = apply(decoded.layout, problems)
        // At the start, a widget deleted while the launcher was off isn't a mistake in the file: it's left out, quietly.
        if (atStart && gone) problems.removeAll { it.endsWith(MISSING) }
        synced.setProblems(problems)
        // Cells on another grid than home's were moved onto it: the file says so, rather than which grid it was.
        if ((atStart && gone) || decoded.layout.columns != layout.columns) synced.save()
    }

    /** Home as the file has it now, from what home draws. */
    private fun snapshot(): HomeLayout {
        val pages = ArrayList<PageEntry>()
        for (page in engine.pages) {
            val items = engine.itemsOn(page)
            // A face sharing its page with tiles is on its way to a page of its own; it goes before them.
            for (item in items) if (item is WidgetItem && item.isFace) pages += FacePage(item.key)
            val tiles =
                items
                    .filterNot { it.isFace }
                    .sortedWith(compareBy({ layout.cell(it.key)?.y ?: Int.MAX_VALUE }, { layout.cell(it.key)?.x ?: Int.MAX_VALUE }))
                    .map { item ->
                        val cell = layout.cell(item.key)
                        when (item) {
                            is WidgetItem -> WidgetTile(item.key, cell)
                            is Shortcut -> AppTile(fileKey(item.key), cell)
                            is Folder -> FolderTile(item.name, item.apps.map { fileKey(it.key) }, cell)
                        }
                    }
            if (tiles.isNotEmpty()) pages += TilePage(tiles)
        }
        return HomeLayout(layout.columns, pages)
    }

    /**
     * Puts home as [file] says, noting what it couldn't in [problems]. Widgets the file leaves out stay where they are;
     * apps it leaves out come off home. Returns whether it named widgets that aren't there.
     */
    private fun apply(
        file: HomeLayout,
        problems: MutableList<String>,
    ): Boolean {
        val numbers = HomeFile.numbers(file.pages)
        val specs = engine.specs.filter(engine::onHome).associateBy { it.file.name }
        val cols = layout.columns
        var gone = false
        val placed = HashSet<String>()
        val widgets = ArrayList<Pair<WidgetSpec, Int>>()
        val apps = ArrayList<Shortcut>()
        val folders = ArrayList<FolderEntry>()
        val cells = HashMap<String, Cell?>()
        val known = shortcuts.items.associateBy { it.key }

        /** Whether [name] goes on [page]. */
        fun widget(
            name: String,
            page: Int,
            face: Boolean,
            where: String,
        ): Boolean {
            val spec = specs[name]
            when {
                spec == null -> {
                    gone = true
                    problems += "$where: $name$MISSING"
                }
                spec.isFace != face -> problems += if (face) "$where: $name isn't a face; write it as a tile" else "$where: $name is a face; write it as a page, face = \"$name\""
                !placed.add(name) -> problems += "$where: $name is on home already; left out here"
                else -> {
                    widgets += spec to page
                    return true
                }
            }
            return false
        }

        fun app(
            name: String,
            page: Int,
            folder: String?,
            where: String,
        ): Shortcut? {
            val key = shortcutKey(name)
            val app = key?.let { known[it] ?: newShortcut(it) }
            return when {
                app == null -> null.also { problems += "$where: $name isn't an app on this phone; see the drawer" }
                !placed.add(app.key) -> null.also { problems += "$where: $name is on home already; left out here" }
                else -> app.copy(page = page, folder = folder).also { apps += it }
            }
        }

        file.pages.forEachIndexed { i, entry ->
            val page = numbers[i]
            val where = "page ${i + 1}"
            when (entry) {
                is FacePage -> widget(entry.file, page, face = true, where)
                is TilePage -> {
                    val taken = ArrayList<Cell>()
                    entry.tiles.forEachIndexed { j, tile ->
                        val at = "$where, tile ${j + 1}"
                        val key: String
                        val size: Pair<Int, Int>
                        when (tile) {
                            is WidgetTile -> {
                                if (!widget(tile.file, page, face = false, at)) return@forEachIndexed
                                key = tile.file
                                size = layout.cell(key)?.let { it.w to it.h } ?: specs[key]?.size ?: (cols / 2 to 3)
                            }
                            is AppTile -> {
                                key = app(tile.app, page, null, at)?.key ?: return@forEachIndexed
                                size = 1 to 1
                            }
                            is FolderTile -> {
                                // Same folder as before when it holds the same first app, so it keeps its key.
                                val first = tile.apps.firstNotNullOfOrNull(::shortcutKey)
                                val folder = first?.let { known[it] }?.folder ?: ("folder:" + System.currentTimeMillis().toString(36) + "-$i-$j")
                                val members = tile.apps.mapNotNull { app(it, page, folder, at) }
                                if (members.isEmpty()) return@forEachIndexed
                                folders += FolderEntry(folder, page, tile.name)
                                key = folder
                                size = 1 to 1
                            }
                        }
                        if (key in cells) return@forEachIndexed
                        val c = tile.cell?.let { c -> rescaled(c, size, file.columns, cols, tile is WidgetTile) }
                        cells[key] =
                            when {
                                c == null -> null
                                c.x + c.w > cols -> null.also { problems += "$at: off the grid's $cols columns; it took the first free spot" }
                                taken.any { it.overlaps(c) } -> null.also { problems += "$at: overlaps a tile before it; it took the first free spot" }
                                else -> c.also { taken += it }
                            }
                    }
                }
            }
        }

        applying = true
        try {
            for ((spec, page) in widgets) if (spec.page != page || layout.page(spec.file.name) != page) engine.moveToPage(WidgetItem(spec), page)
            shortcuts.replace(apps, folders)
            for ((key, cell) in cells) if (cell == null) layout.clearCell(key) else layout.set(key, cell)
            layout.save()
        } finally {
            applying = false
        }
        return gone
    }

    /** [c] from the file's grid of [from] columns on this one's [to]; a tile without a size in the file keeps [size]. */
    private fun rescaled(
        c: Cell,
        size: Pair<Int, Int>,
        from: Int,
        to: Int,
        resizable: Boolean,
    ): Cell {
        val sized = if (!resizable) c.copy(w = 1, h = 1) else if (c.w <= 0 || c.h <= 0) c.copy(w = size.first, h = size.second) else c
        return sized.rescaled(from, to)
    }

    /**
     * A shortcut's key as the file writes it: an Android app in the launcher's own profile by its package alone when
     * that's its only one in the drawer, otherwise by its activity (and `@profile`); the others with their prefix.
     */
    private fun fileKey(key: String): String {
        if (!key.startsWith(APP)) return key
        val app = key.removePrefix(APP)
        if ('@' in app) return app
        val pkg = app.substringBefore('/')
        val drawer = runtime.androidApps.apps.filter { it.profile == null && it.component.packageName == pkg }
        return if (drawer.size == 1 && drawer[0].key == app) pkg else app
    }

    /** The shortcut key the file's [name] stands for, or null when there's no such app. */
    private fun shortcutKey(name: String): String? {
        val n = name.trim()
        if (PREFIXES.any { n.startsWith(it) }) return n
        if ('/' in n) return APP + n
        runtime.androidApps.apps.firstOrNull { it.profile == null && it.component.packageName == n }?.let { return APP + it.key }
        return shortcuts.items.firstOrNull { it.key.startsWith(APP) && it.profile == null && it.component?.packageName == n }?.key
    }

    /** A shortcut for an app the file pins that home didn't have yet; null when it isn't on the phone. */
    private fun newShortcut(key: String): Shortcut? =
        when {
            key.startsWith(APP) -> runtime.androidApps.apps.firstOrNull { APP + it.key == key }?.let { Shortcut(key, 1, it.label, component = it.component, profile = it.profile) }
            key.startsWith(LINUX) -> runtime.linuxApps.apps.firstOrNull { LINUX + it.id == key }?.let { Shortcut(key, 1, it.name, linux = it) }
            key.startsWith(OWL) -> engine.specs.app(key.removePrefix(OWL))?.let { Shortcut(key, 1, it.name, owl = it.file.name) }
            key.startsWith(SHORTCUT) ->
                runtime.androidApps.shortcuts.firstOrNull { SHORTCUT + it.key == key }?.let {
                    Shortcut(key, 1, it.label, profile = it.profile, androidShortcut = it.ref)
                }
            else -> null
        }

    companion object {
        const val FILE = "layout.toml"
        const val ERRORS = "errors.txt"

        /** Where Linux sees [FILE], relative to the guest home. */
        const val GUEST_DIR = ".config/omalauncher/home"

        private const val WRITTEN = "home_layout_written"
        private const val MISSING = " isn't in widgets/; left out"
        private const val APP = "app:"
        private const val LINUX = "linux:"
        private const val OWL = "owl:"
        private const val SHORTCUT = "shortcut:"
        private val PREFIXES = listOf(APP, LINUX, OWL, SHORTCUT)
    }

    private fun hash(text: String) = sha256Hex(text.toByteArray())
}
