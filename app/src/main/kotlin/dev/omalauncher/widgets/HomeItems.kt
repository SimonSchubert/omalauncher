package dev.omalauncher.widgets

import android.content.ComponentName
import android.content.Context
import dev.omalauncher.AndroidApp
import dev.omalauncher.AndroidShortcut
import dev.omalauncher.ShortcutRef
import androidx.compose.runtime.mutableStateListOf
import dev.omalauncher.linux.LinuxApp
import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.mapObjects
import org.json.JSONArray
import org.json.JSONObject

/** Anything on the home grid. [key] identifies its cell in [WidgetLayout]. */
sealed interface HomeItem {
    val key: String
    val page: Int
    val size: Pair<Int, Int>?
    val minSize: Pair<Int, Int>

    /** App shortcuts are fixed 1x1 icons; only widgets resize. */
    val resizable: Boolean get() = true

    /** A page (`face "…" { }`): it has no cell, and fills a home page of its own; see [WidgetLayout.owner]. */
    val isFace: Boolean get() = false
}

/** Equal while its spec is the same object, so pages that didn't change skip recomposition. */
data class WidgetItem(
    val spec: WidgetSpec,
) : HomeItem {
    override val key get() = spec.file.name
    override val page get() = spec.page
    override val size get() = spec.size
    override val minSize get() = spec.minSize
    override val resizable get() = !spec.isFace
    override val isFace get() = spec.isFace
}

/**
 * An app pinned from the drawer: an Android activity, a Linux .desktop entry, an owl app, or a shortcut an Android
 * app made (see [AndroidShortcut]). In [folder], it has no cell of its own.
 */
data class Shortcut(
    override val key: String,
    override val page: Int,
    val label: String,
    val component: ComponentName? = null,
    /** [component]'s or [androidShortcut]'s profile, as in [AndroidApp.profile]. */
    val profile: Long? = null,
    val linux: LinuxApp? = null,
    val androidShortcut: ShortcutRef? = null,
    /** An owl app's file name. */
    val owl: String? = null,
    val folder: String? = null,
) : HomeItem {
    override val size get() = 1 to 1
    override val minSize get() = 1 to 1
    override val resizable get() = false
}

/** Pinned apps grouped under one 1x1 tile, made by dropping an app on another, like stock Android. */
data class Folder(
    override val key: String,
    override val page: Int,
    val apps: List<Shortcut>,
    /** Shown under its tile and atop its panel; null for none, as folders start. */
    val name: String? = null,
) : HomeItem {
    override val size get() = 1 to 1
    override val minSize get() = 1 to 1
    override val resizable get() = false
}

/**
 * A widget one of the phone's apps offers (an app widget: a clock, the weather, a calendar), bound to [id] in the
 * launcher's widget host. Only apps in the launcher's own profile. [size] and [minSize] are in cells, worked out
 * from what the app asked for when it was added.
 */
data class AndroidWidget(
    val id: Int,
    override val page: Int,
    val provider: ComponentName,
    /** The widget's name in the picker, for when its app is gone. */
    val label: String,
    override val size: Pair<Int, Int>,
    override val minSize: Pair<Int, Int>,
) : HomeItem {
    override val key get() = keyOf(id)

    companion object {
        const val PREFIX = "appwidget:"

        fun keyOf(id: Int) = PREFIX + id
    }
}

/** A folder as saved; its apps are the shortcuts that name it. */
data class FolderEntry(
    val key: String,
    val page: Int,
    val name: String? = null,
)

/**
 * Pinned apps and their folders, saved launcher-side next to the grid layout, and in layout.toml by [HomeStore].
 * Folders are saved apart from the apps, so a launcher that predates them
 * still reads its apps (each back on the grid on its own).
 */
class Shortcuts(
    context: Context,
    private val layout: WidgetLayout,
) {
    private val prefs = context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)
    val items = mutableStateListOf<Shortcut>()
    private val folderEntries = mutableStateListOf<FolderEntry>()

    init {
        (Versioned.read(prefs.getString(ITEMS, null), VERSION) as? JSONArray)?.let { array ->
            items += array.mapObjects(::parse)
        }
        (Versioned.read(prefs.getString(FOLDERS, null), VERSION) as? JSONArray)?.let { array ->
            folderEntries += array.mapObjects { FolderEntry(it.getString("key"), it.optInt("page", 1), it.optString("name").ifEmpty { null }) }
        }
        // An app whose folder is gone goes back on the grid.
        val known = folderEntries.map { it.key }.toSet()
        for (i in items.indices) if (items[i].folder?.let { it !in known } == true) items[i] = items[i].copy(folder = null)
    }

    /** Apps and folders on the grid; apps in a folder show inside it instead. */
    fun onGrid(): List<HomeItem> =
        items.filter { it.folder == null } +
            folderEntries.map { f -> Folder(f.key, f.page, items.filter { it.folder == f.key }, f.name) }

    /**
     * Drops [app] on [target]: into it if it's a folder, otherwise both go into a new
     * folder in [target]'s cell. Returns the folder's key, or null if [target] isn't an app or folder.
     */
    fun group(
        app: Shortcut,
        target: HomeItem,
    ): String? {
        if (app.key == target.key) return null
        val folder =
            when (target) {
                is Folder -> target.key
                is Shortcut -> {
                    val key = "folder:" + System.currentTimeMillis().toString(36)
                    folderEntries += FolderEntry(key, target.page)
                    layout.cell(target.key)?.let { layout.set(key, it) }
                    layout.remove(target.key)
                    setFolder(target.key, key, target.page)
                    key
                }
                else -> return null
            }
        if (folder == app.folder) return folder
        val page = folderEntries.first { it.key == folder }.page
        layout.remove(app.key)
        setFolder(app.key, folder, page)
        tidy()
        save()
        return folder
    }

    /** Names folder [key]; a blank [name] leaves it unnamed. */
    fun rename(
        key: String,
        name: String,
    ) {
        val i = folderEntries.indexOfFirst { it.key == key }
        if (i < 0) return
        folderEntries[i] = folderEntries[i].copy(name = name.trim().ifEmpty { null })
        save()
    }

    /**
     * Moves an app within its folder to just before [before], or after the folder's last app if that's null.
     * A folder's order is its apps' order in [items].
     */
    fun moveBefore(
        key: String,
        before: String?,
    ) {
        val i = items.indexOfFirst { it.key == key }
        val folder = items.getOrNull(i)?.folder ?: return
        if (key == before) return
        val app = items.removeAt(i)
        val at = before?.let { b -> items.indexOfFirst { it.key == b && it.folder == folder } }?.takeIf { it >= 0 } ?: (items.indexOfLast { it.folder == folder } + 1)
        items.add(at, app)
        if (at != i) save()
    }

    private fun setFolder(
        key: String,
        folder: String?,
        page: Int,
    ) {
        val i = items.indexOfFirst { it.key == key }
        if (i < 0) return
        // Last in the folder, like stock launchers.
        val app = items.removeAt(i).copy(folder = folder, page = page)
        items += app
    }

    /**
     * A folder of fewer than two apps goes away, like on stock Android: the app
     * left takes its cell, unless it already has one of its own.
     */
    private fun tidy() {
        for (f in folderEntries.toList()) {
            val apps = items.filter { it.folder == f.key }
            if (apps.size >= 2) continue
            layout.save()
            apps.firstOrNull()?.let { app ->
                if (layout.cell(app.key) == null) layout.cell(f.key)?.let { layout.set(app.key, it) }
                setFolder(app.key, null, f.page)
            }
            folderEntries.remove(f)
            layout.remove(f.key)
        }
    }

    private fun parse(o: JSONObject): Shortcut {
        val linux =
            o.optJSONObject("linux")?.let {
                LinuxApp(
                    name = it.getString("name"),
                    exec = it.getString("exec"),
                    terminal = it.optBoolean("terminal"),
                    id = it.getString("id"),
                    wmClass = it.optString("wmClass").ifEmpty { null },
                    icon = it.optString("icon").ifEmpty { null },
                )
            }
        return Shortcut(
            key = o.getString("key"),
            page = o.optInt("page", 1),
            label = o.getString("label"),
            component = o.optString("component").ifEmpty { null }?.let(ComponentName::unflattenFromString),
            profile = if (o.has("profile")) o.getLong("profile") else null,
            linux = linux,
            androidShortcut = o.optJSONObject("shortcut")?.let { ShortcutRef(it.getString("package"), it.getString("id")) },
            owl = o.optString("owl").ifEmpty { null },
            folder = o.optString("folder").ifEmpty { null },
        )
    }

    fun addAndroid(
        app: AndroidApp,
        page: Int,
    ) = add(Shortcut("app:" + app.key, page, app.label, component = app.component, profile = app.profile))

    fun addLinux(
        app: LinuxApp,
        page: Int,
    ) = add(Shortcut("linux:" + app.id, page, app.name, linux = app))

    fun addAndroidShortcut(
        shortcut: AndroidShortcut,
        page: Int,
    ) = add(Shortcut(keyOf(shortcut), page, shortcut.label, profile = shortcut.profile, androidShortcut = shortcut.ref))

    fun addOwl(
        spec: WidgetSpec,
        page: Int,
    ) = add(Shortcut(keyOf(spec), page, spec.name, owl = spec.file.name))

    /** Takes an owl app off the home screen, cell and all, once its file is deleted. */
    fun removeOwl(spec: WidgetSpec) {
        val key = keyOf(spec)
        if (items.none { it.key == key }) return
        layout.remove(key)
        remove(key)
    }

    private fun keyOf(spec: WidgetSpec) = "owl:" + spec.file.name

    /** Takes [shortcut] off the home screen, cell and all, once it's removed from the phone. */
    fun removeAndroidShortcut(shortcut: AndroidShortcut) {
        val key = keyOf(shortcut)
        if (items.none { it.key == key }) return
        layout.remove(key)
        remove(key)
    }

    private fun keyOf(shortcut: AndroidShortcut) = "shortcut:" + shortcut.key

    /** Pinning an app that's already on the home screen moves it to [Shortcut.page], out of its folder. */
    private fun add(shortcut: Shortcut) {
        items.removeAll { it.key == shortcut.key }
        items += shortcut
        tidy()
        save()
    }

    /** Puts an app or folder on [page]; an app moved out of its folder lands on the grid on its own. */
    fun move(
        key: String,
        page: Int,
    ) {
        val f = folderEntries.indexOfFirst { it.key == key }
        if (f >= 0) {
            folderEntries[f] = folderEntries[f].copy(page = page)
            for (i in items.indices) if (items[i].folder == key) items[i] = items[i].copy(page = page)
        } else {
            val i = items.indexOfFirst { it.key == key }
            if (i < 0) return
            items[i] = items[i].copy(page = page, folder = null)
            tidy()
        }
        save()
    }

    /**
     * Home's apps and folders as layout.toml has them: [apps] in order, each folder's in its order. Apps and folders
     * no longer there lose their cells.
     */
    fun replace(
        apps: List<Shortcut>,
        folders: List<FolderEntry>,
    ) {
        val keep = apps.mapTo(HashSet()) { it.key } + folders.map { it.key }
        (items.map { it.key } + folderEntries.map { it.key }).filter { it !in keep }.forEach(layout::remove)
        items.clear()
        items += apps
        folderEntries.clear()
        folderEntries += folders
        tidy()
        save()
    }

    /** Takes an app, or a folder with all its apps, off the home screen. */
    fun remove(key: String) {
        if (folderEntries.removeAll { it.key == key }) {
            items.filter { it.folder == key }.forEach { layout.remove(it.key) }
            items.removeAll { it.folder == key }
        }
        items.removeAll { it.key == key }
        tidy()
        save()
    }

    private fun save() {
        val array = JSONArray()
        for (s in items) {
            val o =
                JSONObject()
                    .put("key", s.key)
                    .put("page", s.page)
                    .put("label", s.label)
                    .putOpt("folder", s.folder)
                    .putOpt("owl", s.owl)
            s.component?.let { o.put("component", it.flattenToString()) }
            s.profile?.let { o.put("profile", it) }
            s.androidShortcut?.let { o.put("shortcut", JSONObject().put("package", it.packageName).put("id", it.id)) }
            // A snapshot of the desktop entry, for when it's gone; while it exists, the launcher runs the current one.
            s.linux?.let {
                o.put(
                    "linux",
                    JSONObject()
                        .put("name", it.name)
                        .put("exec", it.exec)
                        .put("terminal", it.terminal)
                        .put("id", it.id)
                        .putOpt("wmClass", it.wmClass)
                        .putOpt("icon", it.icon),
                )
            }
            array.put(o)
        }
        val folders = JSONArray()
        for (f in folderEntries) folders.put(JSONObject().put("key", f.key).put("page", f.page).putOpt("name", f.name))
        prefs
            .edit()
            .putString(ITEMS, Versioned.write(VERSION, array))
            .putString(FOLDERS, Versioned.write(VERSION, folders))
            .apply()
        onSave()
    }

    /** Called after every save; [HomeStore] writes layout.toml then. */
    var onSave: () -> Unit = {}

    private companion object {
        const val ITEMS = "items"
        const val FOLDERS = "folders"
        const val VERSION = 1
    }
}

/**
 * The phone's apps' widgets on home, saved launcher-side next to the grid layout, and in layout.toml by [HomeStore].
 * Taking one off frees its id with [onDelete], so Android stops updating it.
 */
class AndroidWidgets(
    context: Context,
    private val layout: WidgetLayout,
) {
    // The file, keys and fields the deploy branch's Android widgets (ToS0/omalauncher) saved theirs in, with the same
    // host id and tile keys: a phone coming from it keeps its widgets, each in its cell. Theirs have no label.
    private val prefs = context.getSharedPreferences("app_widgets", Context.MODE_PRIVATE)
    val items = mutableStateListOf<AndroidWidget>()

    init {
        (Versioned.read(prefs.getString(ITEMS, null), VERSION) as? JSONArray)?.let { array ->
            items +=
                array.mapObjects { o ->
                    ComponentName.unflattenFromString(o.getString("provider"))?.let { provider ->
                        AndroidWidget(
                            id = o.getInt("id"),
                            page = o.optInt("page", 1),
                            provider = provider,
                            label = o.optString("label").ifEmpty { provider.shortClassName.substringAfterLast('.') },
                            size = o.optInt("w", 2) to o.optInt("h", 2),
                            minSize = o.optInt("minW", 1) to o.optInt("minH", 1),
                        )
                    }
                }
        }
    }

    /** Ids Android has bound for home; one it has that isn't here was left over. */
    val ids get() = items.map { it.id }.toSet()

    fun add(widget: AndroidWidget) {
        items.removeAll { it.id == widget.id }
        items += widget
        save()
    }

    fun move(
        key: String,
        page: Int,
    ) {
        val i = items.indexOfFirst { it.key == key }
        if (i < 0) return
        items[i] = items[i].copy(page = page)
        save()
    }

    /** Takes it off home, cell and all, and frees its id. */
    fun remove(key: String) {
        val widget = items.firstOrNull { it.key == key } ?: return
        items.remove(widget)
        layout.remove(key)
        onDelete(widget.id)
        save()
    }

    private fun save() {
        val array = JSONArray()
        for (w in items) {
            array.put(
                JSONObject()
                    .put("id", w.id)
                    .put("page", w.page)
                    .put("provider", w.provider.flattenToString())
                    .put("label", w.label)
                    .put("w", w.size.first)
                    .put("h", w.size.second)
                    .put("minW", w.minSize.first)
                    .put("minH", w.minSize.second),
            )
        }
        prefs.edit().putString(ITEMS, Versioned.write(VERSION, array)).apply()
        onSave()
    }

    /** Called after every save; [HomeStore] writes layout.toml then. */
    var onSave: () -> Unit = {}

    /** Frees a widget's id in the widget host; set by the engine. */
    var onDelete: (Int) -> Unit = {}

    private companion object {
        const val ITEMS = "items"
        const val VERSION = 1
    }
}
