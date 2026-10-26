package dev.omadroid.theme

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
}
