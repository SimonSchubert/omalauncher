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
}

/** Equal while its spec is the same object, so pages that didn't change skip recomposition. */
data class WidgetItem(
    val spec: WidgetSpec,
) : HomeItem {
    override val key get() = spec.file.name
    override val page get() = spec.page
    override val size get() = spec.size
    override val minSize get() = spec.minSize
}

/**
 * An app pinned from the drawer: an Android activity, a Linux .desktop entry, or a shortcut an Android app made
 * (see [AndroidShortcut]). In [folder], it has no cell of its own.
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

/** A folder as saved; its apps are the shortcuts that name it. */
private data class FolderEntry(
    val key: String,
    val page: Int,
    val name: String? = null,
)

/**
 * Pinned apps and their folders, saved launcher-side next to the grid layout.
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
        // Version 0 (before versioning) is the same array, unwrapped.
        (Versioned.read(prefs.getString(ITEMS, null), VERSION) { _, data -> data } as? JSONArray)?.let { array ->
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
    }

    private companion object {
        const val ITEMS = "items"
        const val FOLDERS = "folders"
        const val VERSION = 1
    }
}
