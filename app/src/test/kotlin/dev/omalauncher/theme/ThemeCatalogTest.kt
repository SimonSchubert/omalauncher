package dev.omalauncher.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ThemeCatalogTest {
    /** Entries as the widget store's themes.json has them, plus ones the launcher shouldn't take. */
    private val json =
        """
        {"version":1,"generated":"2026-11-28T12:00:00Z","source":"https://omarchy.org/themes/","themes":[
          {"title":"Batman","repo":"https://github.com/OldJobobo/omarchy-batman-theme","image":"https://omarchy.org/assets/themes/batman.webp",
           "created":"2026-01-02T03:04:05Z","stars":7,"wallpapers":["backgrounds/1-ash.png","backgrounds/2-dusk.JPG"],"bytes":3000000,
           "colors":"colors.toml","head":"abc","later":{"a":1}},
          {"title":"Rock & Roll","repo":"https://github.com/somerocketeer/omarchy-bauhaus-theme","image":"https://omarchy.org/assets/themes/bauhaus.webp",
           "created":"nope","stars":2,"wallpapers":[],"bytes":10},
          {"title":"Unread","repo":"https://github.com/a/omarchy-unread-theme","image":"https://omarchy.org/assets/themes/unread.webp"},
          {"title":"Elsewhere","repo":"https://github.com/a/omarchy-x-theme","image":"https://evil.example/x.webp"},
          {"title":"Not a repo","repo":"https://gitlab.com/a/omarchy-y-theme","image":"https://omarchy.org/assets/themes/y.webp"},
          {"title":"No image","repo":"https://github.com/a/omarchy-z-theme"},
          {"title":"Batman again","repo":"https://github.com/oldjobobo/omarchy-batman-theme","image":"https://omarchy.org/assets/themes/b.webp"}
        ]}
        """.trimIndent()

    @Test fun readsTheCatalog() {
        val themes = ThemeCatalog.parse(json)
        assertEquals(listOf("Batman", "Rock & Roll", "Unread"), themes.map { it.title })
        val (batman, bauhaus, unread) = themes
        assertEquals("batman", batman.repo.themeName)
        assertEquals("https://omarchy.org/assets/themes/batman.webp", batman.image)
        assertEquals(Instant.parse("2026-01-02T03:04:05Z").toEpochMilli(), batman.created)
        assertEquals(7L, batman.stars)
        assertEquals(listOf("backgrounds/1-ash.png", "backgrounds/2-dusk.JPG"), batman.wallpapers)
        assertEquals(3000000L, batman.bytes)
        assertEquals("colors.toml", batman.colorsFile)
        // Read, without a colors file.
        assertEquals(null, bauhaus.created)
        assertEquals(emptyList<String>(), bauhaus.wallpapers)
        assertEquals(null, bauhaus.colorsFile)
        // Its files weren't read: unknown, and colors.toml is the likely one.
        assertEquals(null, unread.stars)
        assertEquals(null, unread.wallpapers)
        assertEquals(null, unread.bytes)
        assertEquals("colors.toml", unread.colorsFile)
    }

    @Test fun sortsThemesByTheChosenNumber() {
        val themes = ThemeCatalog.parse(json)
        fun titles(sort: ThemeSort) = sortThemes(themes, sort).map { it.title }
        assertEquals(listOf("Batman", "Rock & Roll", "Unread"), titles(ThemeSort.LISTED))
        // Themes GitHub didn't answer about go last, even when the others' numbers are low.
        assertEquals(listOf("Batman", "Rock & Roll", "Unread"), titles(ThemeSort.NEWEST))
        assertEquals(listOf("Batman", "Rock & Roll", "Unread"), titles(ThemeSort.RATING))
        val newer = ThemeCatalog.parse(json.replace("\"created\":\"nope\"", "\"created\":\"2026-02-01T00:00:00Z\""))
        assertEquals(listOf("Rock & Roll", "Batman", "Unread"), sortThemes(newer, ThemeSort.NEWEST).map { it.title })
    }

    @Test fun readsWhatTheStoreGenerated() {
        // A copy of what tools/themes.py wrote, cut down: the two sides must agree on the format.
        val generated =
            """{"version":1,"generated":"2026-11-28T13:26:00Z","source":"https://omarchy.org/themes/","themes":[{"title":"851","repo":"https://github.com/pedroresende/omarchy-851-theme","image":"https://omarchy.org/assets/themes/851.webp","created":"2026-09-05T08:50:08Z","stars":1,"wallpapers":["backgrounds/851.jpg"],"bytes":2474480,"colors":"colors.toml","head":"42bfb153eb275436657afee921b6a005b431a339"},{"title":"Gruvu","repo":"https://github.com/ankur311sudo/gruvu","image":"https://omarchy.org/assets/themes/gruvu.webp"}]}"""
        val themes = ThemeCatalog.parse(generated)
        assertEquals(listOf("851", "Gruvu"), themes.map { it.title })
        assertEquals(listOf("backgrounds/851.jpg"), themes[0].wallpapers)
    }
}
