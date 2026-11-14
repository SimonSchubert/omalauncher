package dev.omalauncher.apps

import dev.omalauncher.theme.Toml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IconOverridesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun roundTripsPerPackOverrides() {
        val store = IconOverrides(tmp.root)
        store.set("com.pack.a", "com.app/com.app.Main", "icon_a")
        store.set("com.pack.b", "com.app/com.app.Main", "icon_b")
        assertEquals("icon_a", store.get("com.pack.a", "com.app/com.app.Main"))
        assertEquals("icon_b", store.get("com.pack.b", "com.app/com.app.Main"))
        val again = IconOverrides(tmp.root)
        assertEquals("icon_a", again.get("com.pack.a", "com.app/com.app.Main"))
        assertNull(again.get("com.pack.a", "missing/x"))
    }

    @Test fun packIsolation() {
        val store = IconOverrides(tmp.root)
        store.set("com.pack.a", "com.app/act", "one")
        assertNull(store.get("com.pack.other", "com.app/act"))
        assertEquals(mapOf("com.app/act" to "one"), store.forPack("com.pack.a"))
    }

    @Test fun parsesSuggestionRows() {
        val text =
            """
            phone = "dialer"

            [[override]]
            component = "com.app/com.app.Main"
            drawable = "suggested"

            [[override]]
            component = "a/b"
            drawable = "  "
            """.trimIndent()
        assertEquals(
            mapOf("com.app/com.app.Main" to "suggested", "phone" to "dialer"),
            IconOverrides.parseSuggestionMap(Toml.parse(text)),
        )
    }

    @Test fun removingClearsEntry() {
        val store = IconOverrides(tmp.root)
        store.set("com.pack", "a/b", "d")
        store.set("com.pack", "a/b", null)
        assertNull(store.get("com.pack", "a/b"))
        assertEquals(emptyMap<String, String>(), store.forPack("com.pack"))
    }

    @Test fun blankClearsAndBumpsRevision() {
        val store = IconOverrides(tmp.root)
        assertEquals(0, store.revision)
        store.set("com.pack", "a/b", "d")
        assertEquals(1, store.revision)
        store.set("com.pack", "a/b", "d")
        assertEquals(1, store.revision)
        store.set("com.pack", "a/b", " ")
        assertNull(store.get("com.pack", "a/b"))
        assertEquals(2, store.revision)
        store.set("com.pack", "a/b", "")
        assertEquals(2, store.revision)
    }

    @Test fun skipsBlankDrawablesInTheFile() {
        File(tmp.root, IconOverrides.FILE).writeText(
            """
            [[override]]
            pack = "p"
            component = "a/b"
            drawable = "  "

            [[override]]
            pack = "p"
            component = "c/d"
            drawable = "ok"
            """.trimIndent(),
        )
        val store = IconOverrides(tmp.root)
        assertNull(store.get("p", "a/b"))
        assertEquals("ok", store.get("p", "c/d"))
    }

    @Test fun dropSuggestionRewritesTheFile() {
        val file = File(tmp.root, IconOverrides.SUGGESTIONS)
        file.writeText(
            """
            [[override]]
            component = "a/b"
            drawable = "one"

            [[override]]
            component = "c/d"
            drawable = "two"
            """.trimIndent(),
        )
        val store = IconOverrides(tmp.root)
        store.dropSuggestion("a/b")
        assertEquals(mapOf("c/d" to "two"), store.readSuggestions())
        store.dropSuggestion("c/d")
        assertEquals(emptyMap<String, String>(), store.readSuggestions())
        assertFalse(file.exists())
    }

    @Test fun partitionsOverriddenCoveredAndMissing() {
        // "d" is both overridden and covered: the override wins, so it isn't also covered or missing.
        val apps = listOf("a", "b", "c", "d")
        val groups =
            partitionIconApps(
                apps,
                hasOverride = { it == "a" || it == "d" },
                covers = { it == "b" || it == "d" },
            )
        assertEquals(listOf("a", "d"), groups.overridden)
        assertEquals(listOf("b"), groups.covered)
        assertEquals(listOf("c"), groups.missing)
    }

    @Test fun suggestBriefSkipsCoveredAndOverridden() {
        val apps =
            listOf(
                "Phone" to "phone/Main",
                "Mail" to "mail/Main",
                "Maps" to "maps/Main",
                "Clock" to "clock/Main",
            )
        // Clock is covered and overridden; both keep it out of the brief. Order of the rest is kept.
        assertEquals(
            listOf("Phone" to "phone/Main"),
            suggestBriefRows(apps, overridden = setOf("mail/Main", "clock/Main"), covered = setOf("maps/Main", "clock/Main")),
        )
    }
}
