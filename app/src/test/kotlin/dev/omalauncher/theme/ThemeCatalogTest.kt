package dev.omalauncher.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ThemeCatalogTest {
    /** Entries as omarchy.org/themes has them, plus ones it shouldn't take. */
    private val html =
        """
        <ul><li><a href="https://github.com/OldJobobo/omarchy-batman-theme" class="group block"><img src="/assets/themes/batman.webp" alt="Batman theme screenshot" width="1200" height="675" loading="lazy" class="img-outlined"/><span class="mt-2.5 block font-mono">Batman</span></a></li><li><a href="https://github.com/somerocketeer/omarchy-bauhaus-theme" class="group block"><img src="/assets/themes/bauhaus.webp" alt="Bauhaus theme screenshot"/><span class="mt-2.5">Rock &amp; Roll</span></a></li><li><a href="https://github.com/a/omarchy-x-theme" class="group"><img src="https://evil.example/x.webp"/><span>Elsewhere</span></a></li><li><a href="https://github.com/omacom/omarchy" class="nav"><span>GitHub</span></a></li></ul>
        """.trimIndent()

    @Test fun readsTheThemesPage() {
        val themes = ThemeCatalog.parse(html)
        assertEquals(listOf("Batman", "Rock & Roll"), themes.map { it.title })
        assertEquals("batman", themes[0].repo.themeName)
        assertEquals("https://omarchy.org/assets/themes/batman.webp", themes[0].image)
        assertEquals("github.com/somerocketeer/omarchy-bauhaus-theme", themes[1].repo.label)
    }

    @Test fun readsARepoTree() {
        val json =
            """
            {"sha":"x","tree":[
              {"path":"README.md","type":"blob","size":358},
              {"path":"backgrounds","type":"tree"},
              {"path":"backgrounds/1-ash.png","type":"blob","size":2000000},
              {"path":"backgrounds/2-dusk.JPG","type":"blob","size":1000000},
              {"path":"backgrounds/notes.txt","type":"blob","size":10},
              {"path":"backgrounds/old/3.png","type":"blob","size":500},
              {"path":"alacritty.toml","type":"blob","size":700},
              {"path":"colors.toml","type":"blob","size":600}
            ],"truncated":false}
            """.trimIndent()
        val files = RepoFiles.parse(json)
        assertEquals(listOf("backgrounds/1-ash.png", "backgrounds/2-dusk.JPG"), files.wallpapers)
        assertEquals(358L + 2000000 + 1000000 + 10 + 500 + 700 + 600, files.bytes)
        assertEquals("colors.toml", files.colorsFile)
        assertEquals("alacritty.toml", RepoFiles(mapOf("alacritty.toml" to 1L)).colorsFile)
        assertEquals(null, RepoFiles(mapOf("README.md" to 1L)).colorsFile)
    }

    @Test fun buildsGitHubQueriesWithinTheApiLimit() {
        val repos =
            ThemeCatalog.parse(html).map { it.repo } +
                (1..20).map { ThemeRepo.parse("https://github.com/o/omarchy-theme-$it-theme")!! }
        val queries = repoQueries(repos)
        assertTrue(queries.size > 1)
        assertTrue(queries.all { it.length <= REPO_QUERY_LIMIT })
        assertEquals(repos.map { "repo:${it.owner}/${it.repo}" }.toSet(), queries.flatMap { it.split(' ') }.toSet())
    }

    @Test fun readsGitHubRepoStats() {
        val json =
            """
            {"items":[
              {"full_name":"OldJobobo/omarchy-batman-theme","created_at":"2026-01-02T03:04:05Z","stargazers_count":7},
              {"full_name":"somerocketeer/omarchy-bauhaus-theme","created_at":"nope","stargazers_count":2}
            ]}
            """.trimIndent()
        val stats = parseRepoStats(json)
        assertEquals(Instant.parse("2026-01-02T03:04:05Z").toEpochMilli(), stats["github.com/oldjobobo/omarchy-batman-theme"]?.created)
        assertEquals(7L, stats["github.com/oldjobobo/omarchy-batman-theme"]?.stars)
        assertEquals(null, stats["github.com/somerocketeer/omarchy-bauhaus-theme"]?.created)
        assertEquals(2L, stats["github.com/somerocketeer/omarchy-bauhaus-theme"]?.stars)
    }

    @Test fun sortsThemesByTheChosenNumber() {
        val themes = ThemeCatalog.parse(html)
        val stats =
            mapOf(
                themes[0].repo.label.lowercase() to CatalogStats(created = 100L, stars = 9L),
                themes[1].repo.label.lowercase() to CatalogStats(created = 200L, stars = 2L),
            )
        fun titles(sort: ThemeSort) = sortThemes(themes, sort, stats).map { it.title }
        assertEquals(listOf("Batman", "Rock & Roll"), titles(ThemeSort.LISTED))
        assertEquals(listOf("Rock & Roll", "Batman"), titles(ThemeSort.NEWEST))
        assertEquals(listOf("Batman", "Rock & Roll"), titles(ThemeSort.RATING))
        // A theme GitHub didn't answer about goes last even when the other's number is low.
        val partial = mapOf(themes[0].repo.label.lowercase() to CatalogStats(stars = 1L))
        assertEquals(listOf("Batman", "Rock & Roll"), sortThemes(themes, ThemeSort.RATING, partial).map { it.title })
    }
}
