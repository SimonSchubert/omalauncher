package dev.omalauncher.widgets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
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
 * GitHub Pages, widgets in index.json, faces in faces.json and apps in apps.json. Installs
 * are checked against the catalog's SHA-256, land on the current page (a face left of page
 * 1, an app in the drawer), and remember which version and commands were approved, so an
 * update that changes them asks again.
 */
class Market(
    private val engine: WidgetEngine,
    val kind: OwlKind,
) {
    companion object {
        /** The marketplace repo: widgets are submitted there as pull requests. */
        const val REPO = "SimonSchubert/omarchy-widgets"

        /** Where its CI publishes index.json, faces.json, apps.json and the reviewed files. */
        const val BASE = "https://simonschubert.github.io/omarchy-widgets/"

        private const val VERSION = 1

        /** Decoded previews kept per store: a 1212x540 one takes 2.6 MB, so about a dozen. */
        private const val PREVIEW_BYTES = 32L shl 20

        /** Settings checks for updates with a catalog at most this old; the store always reloads it. */
        private const val STALE_MS = 60 * 60 * 1000L
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

    /** When the catalog last loaded; settings reloads it only when it's older than [STALE_MS]. */
    var refreshedAt = 0L
        private set
    val busy = mutableStateMapOf<String, String>()
    val installed = mutableStateMapOf<String, Installed>()
    private val previews = StoreImages(PREVIEW_BYTES)
    private val icons = HashMap<Pair<String, Int>, ImageBitmap?>()
    private val catalog = if (kind == OwlKind.Widget) "index.json" else "${kind.word}s.json"

    /** Each kind keeps its own records: an app and a widget may share an id. */
    private val record get() = File(engine.dir, if (kind == OwlKind.Widget) ".market.json" else ".market-${kind.word}s.json")

    suspend fun refresh() {
        if (loading) return
        loading = true
        error = null
        try {
            entries = withContext(Dispatchers.IO) { Catalog.parse(String(httpGet(BASE + catalog)), "${kind.word}s") }
            refreshedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            // A catalog the store doesn't publish yet (faces.json, before the first face) is an empty one.
            if (e.message?.startsWith("HTTP 404") == true) {
                entries = emptyList()
                refreshedAt = System.currentTimeMillis()
                return
            }
            error = "couldn't load the store: ${e.message ?: e}"
        } finally {
            loading = false
        }
    }

    /** Reloads the catalog unless it loaded recently; a failed load is tried again next time. */
    suspend fun refreshIfStale() {
        if (System.currentTimeMillis() - refreshedAt >= STALE_MS) refresh()
    }

    fun fileFor(e: CatalogEntry) = (if (kind == OwlKind.Widget) "market-" else "market-${kind.word}-") + "${e.author}-${e.id.substringAfter('/')}.owl"

    /** Installed, and its file is still there (removing the tile moves the file away). */
    fun installedVersion(e: CatalogEntry): Installed? = installed[e.id]?.takeIf { File(engine.dir, it.file).exists() }

    /** A newer version this launcher can run. */
    fun hasUpdate(e: CatalogEntry) = e.supported && installedVersion(e)?.let { Catalog.compareVersions(e.version, it.version) > 0 } == true

    /** Installed ones with an update, from the last loaded catalog. */
    val updates get() = entries.filter { hasUpdate(it) }

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
                // An update keeps its page; a new install goes where the user is. Apps go in the drawer, on no page, and a face
                // left of page 1, to the empty one waiting there.
                if (installedVersion(e) == null) {
                    if (kind == OwlKind.Widget) engine.setPage(name, page)
                    if (kind == OwlKind.Face) engine.setPage(name, engine.newFacePage())
                }
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
        previews.decoded(path)?.let { return it }
        return withContext(Dispatchers.IO) {
            bytes(path)
                ?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
                ?.also { previews.decoded(path, it) }
        }
    }

    /**
     * [e]'s preview for a grid card, no bigger than the card draws it: at most [width] px wide and [height] px high, and
     * for a whole screen (an app or a face) only its top, [top] of its width high. The full image is a phone screen of up
     * to 10 MB decoded; a dozen of those filled the cache, so scrolling decoded and uploaded them again and again.
     */
    suspend fun thumbnail(
        e: CatalogEntry,
        theme: String?,
        width: Int,
        height: Int,
        top: Float?,
    ): ImageBitmap? {
        val path = e.previewFor(theme) ?: return null
        val key = "$path#$width:$height:$top"
        previews.decoded(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            bytes(path)
                ?.let { runCatching { decodeThumbnail(it, width, height, top)?.asImageBitmap() }.getOrNull() }
                ?.also { previews.decoded(key, it) }
        }
    }

    /** The fetched file at [path], fetched once; null when it can't be. */
    private fun bytes(path: String): ByteArray? =
        if (previews.wasFetched(path)) {
            previews.fetched(path)
        } else {
            runCatching { httpGet(BASE + path) }.getOrNull().also { previews.fetched(path, it) }
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

    private var loaded = false

    /**
     * Reads the install records once; call off the main thread before the store opens. Settings
     * calls it too: opened on its own, after the process died, the engine hasn't started.
     */
    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
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

/** See [Market.thumbnail]: decodes only the part that shows, subsampled, then scales it to fit; never up. */
private fun decodeThumbnail(
    bytes: ByteArray,
    width: Int,
    height: Int,
    top: Float?,
): Bitmap? {
    val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
    try {
        val w = decoder.width
        val h = top?.let { minOf(decoder.height, (w * it).toInt().coerceAtLeast(1)) } ?: decoder.height
        val scale = minOf(1f, width.toFloat() / w, if (top == null) height.toFloat() / h else 1f)
        val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (w / (opts.inSampleSize * 2) >= w * scale) opts.inSampleSize *= 2
        val region = decoder.decodeRegion(Rect(0, 0, w, h), opts) ?: return null
        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        if (region.width == tw && region.height == th) return region
        return Bitmap.createScaledBitmap(region, tw, th, true).also { if (it !== region) region.recycle() }
    } finally {
        decoder.recycle()
    }
}

/**
 * The store's previews and screens: as fetched (tens of KB each) for as long as the store is around, decoded (megabytes
 * each) only the most recently drawn, up to [maxBytes]. Keeping every decoded one took 460 MB once the widgets tab was
 * scrolled through.
 */
private class StoreImages(
    private val maxBytes: Long,
) {
    /** By path; null when it couldn't be fetched, which isn't tried again. */
    private val fetched = HashMap<String, ByteArray?>()

    /** The least recently drawn first. */
    private val decoded = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)
    private var decodedBytes = 0L

    private val ImageBitmap.bytes get() = width.toLong() * height * 4

    @Synchronized fun wasFetched(path: String) = path in fetched

    @Synchronized fun fetched(path: String) = fetched[path]

    @Synchronized fun fetched(
        path: String,
        bytes: ByteArray?,
    ) {
        fetched[path] = bytes
    }

    @Synchronized fun decoded(path: String) = decoded[path]

    @Synchronized fun decoded(
        path: String,
        image: ImageBitmap,
    ) {
        decoded.put(path, image)?.let { decodedBytes -= it.bytes }
        decodedBytes += image.bytes
        val eldest = decoded.values.iterator()
        // The one just drawn stays, however big.
        while (decodedBytes > maxBytes && decoded.size > 1) {
            decodedBytes -= eldest.next().bytes
            eldest.remove()
        }
    }
}
