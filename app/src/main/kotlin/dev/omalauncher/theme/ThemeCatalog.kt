package dev.omalauncher.theme

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.R
import dev.omalauncher.net.httpGet
import dev.omalauncher.upgrade.writeAtomically
import dev.omalauncher.widgets.Market
import dev.omalauncher.widgets.lang.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.Instant

private const val TAG = "theme-catalog"

/** The orders offered above the catalog grid. */
internal enum class ThemeSort { LISTED, NEWEST, RATING }

/** [themes] in [sort], with themes GitHub didn't answer about last. */
internal fun sortThemes(
    themes: List<CatalogTheme>,
    sort: ThemeSort,
): List<CatalogTheme> {
    if (sort == ThemeSort.LISTED) return themes
    fun value(theme: CatalogTheme) =
        when (sort) {
            ThemeSort.NEWEST -> theme.created
            ThemeSort.RATING -> theme.stars
            ThemeSort.LISTED -> null
        }
    return themes.sortedWith(compareBy<CatalogTheme> { value(it) == null }.thenByDescending { value(it) ?: 0 })
}

/** A theme on omarchy.org/themes, with what GitHub said about its repo; null where it didn't. */
class CatalogTheme(
    val title: String,
    val repo: ThemeRepo,
    /** Its screenshot on omarchy.org, 1200x675. */
    val image: String,
    /** When its GitHub repo was created, as the theme's upload date. */
    val created: Long? = null,
    /** GitHub stars, as the community's rating. */
    val stars: Long? = null,
    /** Its wallpapers' paths in the repo, in the order a theme shows them. */
    val wallpapers: List<String>? = null,
    /** About what installing it downloads: the size of its files. */
    val bytes: Long? = null,
    /** The file its colors come from, colors.toml or alacritty.toml; a guess of colors.toml when its files weren't read. */
    val colorsFile: String? = "colors.toml",
)

/** What a catalog theme's repo holds, for its preview page; null where GitHub didn't say. */
class CatalogDetails(
    /** Its palette, resolved like an installed theme's. */
    val colors: Map<String, String>?,
    /** Its wallpapers' paths in the repo, in the order a theme shows them. */
    val wallpapers: List<String>?,
    /** About what installing it downloads: the size of its files. */
    val bytes: Long?,
) {
    val isLight get() = colors?.get("mode") == "light"
}

/**
 * The community themes omarchy.org/themes lists, from the widget store's themes.json: its CI reads the page and asks
 * GitHub for each repo's stars, creation date and files every few hours, so one download has all of it. The last
 * copy that parsed is kept in [cacheDir] for offline use, with the screenshots.
 */
class ThemeCatalog(
    private val cacheDir: File,
) {
    var entries by mutableStateOf<List<CatalogTheme>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<ThemeError?>(null)
        private set

    private val cached get() = File(cacheDir, "theme-catalog.json")
    private val images get() = File(cacheDir, "theme-catalog")

    /** Shows the cached list right away, then the current one. */
    suspend fun refresh() {
        if (loading) return
        loading = true
        error = null
        try {
            if (entries.isEmpty()) entries = withContext(Dispatchers.IO) { readCached() }
            val json = withContext(Dispatchers.IO) { String(httpGet(CATALOG)) }
            val parsed = parse(json)
            if (parsed.isEmpty()) throw ThemeFailure(R.string.store_theme_page_changed)
            entries = parsed
            withContext(Dispatchers.IO) {
                writeAtomically(cached, json.toByteArray())
                // Launchers before themes.json kept their own copy of omarchy.org/themes.
                File(cacheDir, "theme-catalog.html").delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed", e)
            error = if (e is ThemeFailure) ThemeError(e.reason) else ThemeError(null, e.message ?: e.toString())
        } finally {
            loading = false
        }
    }

    private fun readCached() = runCatching { parse(cached.readText()) }.getOrDefault(emptyList())

    /** The entry for [repo], from the cached catalog when it wasn't opened since the launcher started. */
    fun entryFor(repo: ThemeRepo): CatalogTheme? =
        entries
            .ifEmpty { readCached() }
            .firstOrNull { it.repo.label.equals(repo.label, ignoreCase = true) }

    private val wallpapers get() = File(cacheDir, "theme-wallpapers")

    /** One of [theme]'s wallpapers, by its [path] in the repo, downloaded once; the cache is Android's to clear. */
    suspend fun wallpaper(
        theme: CatalogTheme,
        path: String,
    ): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val repo = theme.repo
                val ext = path.substringAfterLast('.').lowercase()
                check(ext in IMAGE_EXTENSIONS) { "not an image: $path" }
                val file = File(wallpapers, sha256Hex("${repo.label.lowercase()}/$path".toByteArray()).take(32) + ".$ext")
                if (!file.isFile) {
                    val url = "https://raw.githubusercontent.com/${repo.owner}/${repo.repo}/HEAD/" + path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                    writeAtomically(file, httpGet(url, readTimeout = 60_000))
                }
                file
            }.onFailure { Log.w(TAG, "no wallpaper $path for ${theme.title}", it) }.getOrNull()
        }

    /** [theme]'s screenshot, downloaded once. */
    suspend fun image(theme: CatalogTheme): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = theme.image.substringAfterLast('/')
                check(SAFE_FILE.matches(name)) { "odd image name $name" }
                val file = File(images, name)
                if (!file.isFile) writeAtomically(file, httpGet(theme.image))
                file
            }.onFailure { Log.w(TAG, "no image for ${theme.title}", it) }.getOrNull()
        }

    private val details = HashMap<String, CatalogDetails>()

    /** [theme]'s palette, from its colors file on GitHub, with its wallpapers and size from the catalog. Kept until the launcher restarts. */
    suspend fun details(theme: CatalogTheme): CatalogDetails =
        withContext(Dispatchers.IO) {
            val key = theme.repo.label.lowercase()
            synchronized(details) { details[key] }?.let { return@withContext it }
            val repo = theme.repo
            val colors =
                theme.colorsFile?.let { name ->
                    runCatching {
                        val text = String(httpGet("https://raw.githubusercontent.com/${repo.owner}/${repo.repo}/HEAD/$name"))
                        if (name == "colors.toml") ThemeColors.resolve(ThemeColors.parse(text)) else ThemeColors.fromAlacritty(text)?.let(ThemeColors::resolve)
                    }.onFailure { Log.w(TAG, "no colors for ${theme.title}", it) }.getOrNull()
                }
            val out = CatalogDetails(colors, theme.wallpapers, theme.bytes)
            // Only a full answer is kept, so a failed call is tried again next time.
            if (colors != null || theme.colorsFile == null) synchronized(details) { details[key] = out }
            out
        }

    companion object {
        const val PAGE = "https://omarchy.org/themes/"
        private const val CATALOG = Market.BASE + "themes.json"
        private const val SITE = "https://omarchy.org"
        private val SAFE_FILE = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]*$")
        private val COLORS_FILES = setOf("colors.toml", "alacritty.toml")

        /** themes.json (the widget store's tools/themes.py); an entry it can't read is left out, not the rest. */
        fun parse(json: String): List<CatalogTheme> {
            val themes = JSONObject(json).getJSONArray("themes")
            return (0 until themes.length())
                .mapNotNull { i -> runCatching { entry(themes.getJSONObject(i)) }.getOrNull() }
                .distinctBy { it.repo.label.lowercase() }
        }

        private fun entry(o: JSONObject): CatalogTheme? {
            val repo = ThemeRepo.parse(o.getString("repo")) ?: return null
            // Only omarchy.org's own screenshots.
            val image = o.getString("image").takeIf { it.startsWith("$SITE/") } ?: return null
            // "bytes" says its files were read; without it, its wallpapers and colors file are unknown.
            val read = o.has("bytes")
            val wallpapers =
                o.optJSONArray("wallpapers")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            return CatalogTheme(
                title = o.getString("title").trim(),
                repo = repo,
                image = image,
                created = o.optString("created").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                stars = if (o.has("stars")) o.getLong("stars") else null,
                wallpapers = if (read) wallpapers.orEmpty() else null,
                bytes = if (read) o.getLong("bytes") else null,
                colorsFile = if (read) o.optString("colors").takeIf { it in COLORS_FILES } else "colors.toml",
            )
        }
    }
}
