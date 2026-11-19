package dev.omalauncher.theme

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.R
import dev.omalauncher.net.httpGet
import dev.omalauncher.upgrade.writeAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

private const val TAG = "theme-catalog"

/** A theme on omarchy.org/themes. */
class CatalogTheme(
    val title: String,
    val repo: ThemeRepo,
    /** Its screenshot on omarchy.org, 1200x675. */
    val image: String,
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

/** A repo's files, from GitHub's tree of it: path to size. */
internal class RepoFiles(
    val sizes: Map<String, Long>,
) {
    /** The images in backgrounds/, which installing it brings along, by name like Omarchy orders them. */
    val wallpapers get() =
        sizes.keys
            .filter { it.startsWith("backgrounds/") && '/' !in it.removePrefix("backgrounds/") && it.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
            .sorted()

    val bytes get() = sizes.values.sum()

    /** The file its colors come from, as an installed theme reads them: colors.toml before alacritty.toml. */
    val colorsFile get() = listOf("colors.toml", "alacritty.toml").firstOrNull { it in sizes }

    companion object {
        /** GitHub's `git/trees/HEAD?recursive=1` answer; only files count. */
        fun parse(json: String): RepoFiles {
            val tree = JSONObject(json).getJSONArray("tree")
            val sizes = LinkedHashMap<String, Long>()
            for (i in 0 until tree.length()) {
                val e = tree.getJSONObject(i)
                if (e.optString("type") == "blob") sizes[e.getString("path")] = e.optLong("size")
            }
            return RepoFiles(sizes)
        }
    }
}

/**
 * The community themes omarchy.org/themes lists. The site has no feed, so
 * this reads the page itself; the last copy that parsed is kept in [cacheDir]
 * for offline use, with the screenshots.
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

    private val page get() = File(cacheDir, "theme-catalog.html")
    private val images get() = File(cacheDir, "theme-catalog")

    /** Shows the cached list right away, then the page's current one. */
    suspend fun refresh() {
        if (loading) return
        loading = true
        error = null
        try {
            if (entries.isEmpty()) entries = withContext(Dispatchers.IO) { runCatching { parse(page.readText()) }.getOrDefault(emptyList()) }
            val html = withContext(Dispatchers.IO) { String(httpGet(PAGE)) }
            val parsed = parse(html)
            // A redesign of the page would parse to nothing; keep what we had and say so.
            if (parsed.isEmpty()) throw ThemeFailure(R.string.store_theme_page_changed)
            entries = parsed
            withContext(Dispatchers.IO) { writeAtomically(page, html.toByteArray()) }
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed", e)
            error = if (e is ThemeFailure) ThemeError(e.reason) else ThemeError(null, e.message ?: e.toString())
        } finally {
            loading = false
        }
    }

    /** The entry for [repo], from the cached page when the catalog wasn't opened since the launcher started. */
    fun entryFor(repo: ThemeRepo): CatalogTheme? =
        entries
            .ifEmpty { runCatching { parse(page.readText()) }.getOrDefault(emptyList()) }
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
                val digest = MessageDigest.getInstance("SHA-256").digest("${repo.label.lowercase()}/$path".toByteArray())
                val file = File(wallpapers, digest.joinToString("") { "%02x".format(it) }.take(32) + ".$ext")
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

    /**
     * [theme]'s palette, wallpaper count and size, from GitHub: one API call for the repo's files (60 an hour without
     * a login, so only on the preview page) and its colors file. Kept until the launcher restarts.
     */
    suspend fun details(theme: CatalogTheme): CatalogDetails =
        withContext(Dispatchers.IO) {
            val key = theme.repo.label.lowercase()
            synchronized(details) { details[key] }?.let { return@withContext it }
            val repo = theme.repo
            val files =
                runCatching { RepoFiles.parse(String(httpGet("https://api.github.com/repos/${repo.owner}/${repo.repo}/git/trees/HEAD?recursive=1"))) }
                    .onFailure { Log.w(TAG, "no file list for ${theme.title}", it) }
                    .getOrNull()
            // Without the list, colors.toml is the likely one.
            val colorsFile = if (files != null) files.colorsFile else "colors.toml"
            val colors =
                colorsFile?.let { name ->
                    runCatching {
                        val text = String(httpGet("https://raw.githubusercontent.com/${repo.owner}/${repo.repo}/HEAD/$name"))
                        if (name == "colors.toml") ThemeColors.resolve(ThemeColors.parse(text)) else ThemeColors.fromAlacritty(text)?.let(ThemeColors::resolve)
                    }.onFailure { Log.w(TAG, "no colors for ${theme.title}", it) }.getOrNull()
                }
            val out = CatalogDetails(colors, files?.wallpapers, files?.bytes)
            // Only a full answer is kept, so a failed call is tried again next time.
            if (files != null && (colors != null || colorsFile == null)) synchronized(details) { details[key] = out }
            out
        }

    companion object {
        const val PAGE = "https://omarchy.org/themes/"
        private const val SITE = "https://omarchy.org"
        private val SAFE_FILE = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]*$")

        /** An entry: `<li><a href="repo"><img src="/assets/themes/x.webp" …/><span …>Title</span></a></li>`. */
        private val ENTRY = Regex("""<li><a href="(https://github\.com/[^"]+)"[^>]*><img src="([^"]+)"[^>]*/?><span[^>]*>([^<]+)</span></a></li>""")

        fun parse(html: String): List<CatalogTheme> =
            ENTRY
                .findAll(html)
                .mapNotNull { m ->
                    val (href, src, title) = m.destructured
                    val repo = ThemeRepo.parse(href) ?: return@mapNotNull null
                    val image = if (src.startsWith("/") && !src.startsWith("//")) SITE + src else return@mapNotNull null
                    CatalogTheme(unescape(title.trim()), repo, image)
                }.distinctBy { it.repo.label.lowercase() }
                .toList()

        private fun unescape(s: String) =
            s
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
    }
}
