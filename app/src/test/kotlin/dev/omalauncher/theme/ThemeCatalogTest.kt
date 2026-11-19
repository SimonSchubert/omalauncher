package dev.omalauncher.theme

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
