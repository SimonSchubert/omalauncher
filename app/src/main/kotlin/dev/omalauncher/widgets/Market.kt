package dev.omalauncher.widgets

import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.omalauncher.httpGet
import dev.omalauncher.ui.decodeImageIcon
import dev.omalauncher.ui.decodeSvgIcon
import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.mapEntries
import dev.omalauncher.upgrade.writeAtomically
import dev.omalauncher.widgets.lang.OwlKind
import dev.omalauncher.widgets.market.Catalog
import dev.omalauncher.widgets.market.CatalogEntry
import dev.omalauncher.widgets.market.Review
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The marketplace for one [kind]: a reviewed catalog published from a GitHub repo to
 * GitHub Pages, widgets in index.json and apps in apps.json. Installs are checked against
 * the catalog's SHA-256, land on the current page (an app in the drawer), and remember
 * which version and commands were approved, so an update that changes them asks again.
 */
class Market(
    private val engine: WidgetEngine,
    val kind: OwlKind,
) {
    companion object {
        /** The marketplace repo: widgets are submitted there as pull requests. */
        const val REPO = "SimonSchubert/omarchy-widgets"

        /** Where its CI publishes index.json, apps.json and the reviewed files. */
        const val BASE = "https://simonschubert.github.io/omarchy-widgets/"

        private const val VERSION = 1
    }

    /** What the user installed and approved, per catalog id. */
    data class Installed(
        val id: String,
        val file: String,
        val version: String,
        val commands: List<String>,
        val requires: Map<String, List<String>>,
    )

    var entries by mutableStateOf<List<CatalogEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val busy = mutableStateMapOf<String, String>()
    val installed = mutableStateMapOf<String, Installed>()
    private val previews = HashMap<String, ImageBitmap?>()
    private val icons = HashMap<Pair<String, Int>, ImageBitmap?>()
    private val catalog = if (kind == OwlKind.App) "apps.json" else "index.json"

    /** Each kind keeps its own records: an app and a widget may share an id. */
    private val record get() = File(engine.dir, if (kind == OwlKind.App) ".market-apps.json" else ".market.json")

    suspend fun refresh() {
        if (loading) return
        loading = true
        error = null
        try {
            entries = withContext(Dispatchers.IO) { Catalog.parse(String(httpGet(BASE + catalog)), if (kind == OwlKind.App) "apps" else "widgets") }
        } catch (e: Exception) {
            error = "couldn't load the store: ${e.message ?: e}"
        } finally {
            loading = false
        }
    }

    fun fileFor(e: CatalogEntry) = (if (kind == OwlKind.App) "market-app-" else "market-") + "${e.author}-${e.id.substringAfter('/')}.owl"

    /** Installed, and its file is still there (removing the tile moves the file away). */
    fun installedVersion(e: CatalogEntry): Installed? = installed[e.id]?.takeIf { File(engine.dir, it.file).exists() }

    fun hasUpdate(e: CatalogEntry) = installedVersion(e)?.let { Catalog.compareVersions(e.version, it.version) > 0 } == true

    /** An update runs different commands or needs other packages than what the user approved. */
    fun changedSinceApproval(e: CatalogEntry) = installedVersion(e)?.let { it.commands != e.commands || it.requires != e.requires } == true

    /** Downloads, verifies and installs [e] on [page]; its packages install right after. Returns an error, or null. */
    suspend fun install(
        e: CatalogEntry,
        page: Int,
    ): String? {
        busy[e.id] = "installing…"
        try {
            val bytes = withContext(Dispatchers.IO) { httpGet(BASE + e.path) }
            if (Review.sha256(bytes) != e.sha256) return "the download doesn't match the reviewed version; try again later"
            val name = installedVersion(e)?.file ?: fileFor(e)
            withContext(Dispatchers.IO) {
                // An update keeps its page; a new install goes where the user is. Apps go in the drawer, on no page.
                if (kind == OwlKind.Widget && installedVersion(e) == null) engine.setPage(name, page)
                engine.installDependenciesWhenLoaded(name)
                File(engine.dir, name).writeBytes(bytes)
            }
            installed[e.id] = Installed(e.id, name, e.version, e.commands, e.requires)
            save()
            return null
        } catch (x: Exception) {
            return "install failed: ${x.message ?: x}"
        } finally {
            busy.remove(e.id)
        }
    }

    /** [e]'s preview drawn in [theme] when the store has one, else its default preview. */
    suspend fun preview(
        e: CatalogEntry,
        theme: String?,
    ): ImageBitmap? = e.previewFor(theme)?.let { image(it) }

    /** A preview or screen from the store, by its path in the catalog; null when it can't be loaded. */
    suspend fun image(path: String): ImageBitmap? {
        synchronized(previews) { if (path in previews) return previews[path] }
        val img =
            withContext(Dispatchers.IO) {
                runCatching { httpGet(BASE + path).let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }.getOrNull()
            }
        synchronized(previews) { previews[path] = img }
        return img
    }

    /**
     * [e]'s icon at [size] px square, as the drawer draws it: an SVG with `currentColor` in [accent], an image as it is;
     * null when it has none.
     */
    suspend fun icon(
        e: CatalogEntry,
        size: Int,
        accent: Int,
    ): ImageBitmap? {
        val key = (e.icon ?: e.iconFile ?: return null) to (if (e.icon != null) accent else 0)
        synchronized(icons) { if (key in icons) return icons[key] }
        val img =
            withContext(Dispatchers.IO) {
                runCatching {
                    if (e.icon != null) decodeSvgIcon(e.icon!!, size, accent) else decodeImageIcon(httpGet(BASE + e.iconFile), size)
                }.getOrNull()?.asImageBitmap()
            }
        synchronized(icons) { icons[key] = img }
        return img
    }

    /** Reads the install records; call off the main thread before the store opens. */
    fun load() {
        val data = Versioned.read(record.takeIf { it.exists() }?.readText(), VERSION) as? JSONObject ?: return
        installed.putAll(
            data.mapEntries { id, f ->
                Installed(
                    id = id,
                    file = f.getString("file"),
                    version = f.getString("version"),
                    commands = f.optJSONArray("commands")?.strings().orEmpty(),
                    requires = f.optJSONObject("requires")?.let { r -> r.keys().asSequence().associateWith { r.optJSONArray(it)?.strings().orEmpty() } }.orEmpty(),
                )
            },
        )
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun save() {
        val data = JSONObject()
        for (i in installed.values) {
            data.put(
                i.id,
                JSONObject()
                    .put("file", i.file)
                    .put("version", i.version)
                    .put("commands", JSONArray(i.commands))
                    .put("requires", JSONObject(i.requires.mapValues { JSONArray(it.value) } as Map<*, *>)),
            )
        }
        runCatching { writeAtomically(record, Versioned.write(VERSION, data).toByteArray()) }
    }
}
