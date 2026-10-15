package dev.omalauncher.theme

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.httpGet
import dev.omalauncher.upgrade.writeAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "theme-catalog"

/** A theme on omarchy.org/themes. */
class CatalogTheme(
    val title: String,
    val repo: ThemeRepo,
    /** Its screenshot on omarchy.org, 1200x675. */
    val image: String,
)

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
    var error by mutableStateOf<String?>(null)
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
            check(parsed.isNotEmpty()) { "the page changed and can't be read" }
            entries = parsed
            withContext(Dispatchers.IO) { writeAtomically(page, html.toByteArray()) }
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed", e)
            error = "couldn't load omarchy.org/themes: ${e.message ?: e}"
        } finally {
            loading = false
        }
    }

    /** The entry for [repo], from the cached page when the catalog wasn't opened since the launcher started. */
    fun entryFor(repo: ThemeRepo): CatalogTheme? =
        entries
            .ifEmpty { runCatching { parse(page.readText()) }.getOrDefault(emptyList()) }
            .firstOrNull { it.repo.label.equals(repo.label, ignoreCase = true) }

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
