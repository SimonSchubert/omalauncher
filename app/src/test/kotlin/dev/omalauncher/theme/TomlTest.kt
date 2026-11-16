package dev.omalauncher.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TomlTest {
    @Test fun readsArraysInlineTablesAndArraysOfTables() {
        val t =
            Toml.parse(
                """
                version = 1
                [[page]]
                face = "terminal.owl"

                [[page]]  # tiles
                tiles = [
                  { widget = "50-memory.owl", x = 0, y = 0, w = 3, h = 4 },
                  # a comment between values
                  { folder = "Work", apps = ["a", 'b'] },
                ]
                """.trimIndent(),
            )
        assertEquals(1.0, t["version"])
        val pages = t["page"] as List<*>
        assertEquals(mapOf("face" to "terminal.owl"), pages[0])
        val tiles = (pages[1] as Map<*, *>)["tiles"] as List<*>
        assertEquals(mapOf("widget" to "50-memory.owl", "x" to 0.0, "y" to 0.0, "w" to 3.0, "h" to 4.0), tiles[0])
        assertEquals(mapOf("folder" to "Work", "apps" to listOf("a", "b")), tiles[1])
    }

    @Test fun writesWhatItReads() {
        val t =
            linkedMapOf<String, Any>(
                "version" to 1.0,
                "home.columns" to 6.0,
                "page" to
                    listOf(
                        linkedMapOf<String, Any>("face" to "a.owl"),
                        linkedMapOf<String, Any>("tiles" to listOf(linkedMapOf<String, Any>("app" to "x/.Y", "x" to 1.0, "y" to 2.0), linkedMapOf<String, Any>("folder" to "", "apps" to listOf("a", "b")))),
                    ),
                "short" to listOf(1.0, 2.0),
            )
        val text = Toml.write(t)
        assertEquals(t, Toml.parse(text))
        assertEquals(2, Regex("\\[\\[page]]").findAll(text).count())
        assertEquals(true, "short = [1, 2]\n" in text)
        assertEquals(true, "tiles = [\n  { app = \"x/.Y\", x = 1, y = 2 },\n" in text)
    }

    @Test fun reportsTheLineItCantRead() {
        val bad =
            listOf(
                "a = [\n1,\n2" to 3,
                "a = { b = 1,\nc = 2 }" to 1,
                "a = [1 2]" to 1,
                "[[page]]\n[page]" to 2,
                "[[page]]\nx = 1\n[[page.tiles]]" to 3,
                "page = 1\n[[page]]" to 2,
                "a = { b = 1, b = 2 }" to 1,
            )
        for ((text, line) in bad) {
            try {
                Toml.parse(text)
                fail("parsed: $text")
            } catch (e: Toml.Error) {
                assertEquals(text, line, e.line)
            }
        }
    }

    @Test fun keysAfterAnArrayOfTablesGoInItsLastTable() {
        val t = Toml.parse("[[page]]\na = 1\n[[page]]\na = 2\nb.c = 3\n")
        assertEquals(listOf(mapOf("a" to 1.0), mapOf("a" to 2.0, "b.c" to 3.0)), t["page"])
    }

    @Test fun readsASectionsArrayOfTables() {
        val t =
            Toml.parse(
                """
                version = 1
                [[page]]
                face = "a.owl"

                [cover]
                columns = 4
                [[cover.page]]
                tiles = [ { widget = "b.owl", x = 0, y = 0, w = 2, h = 2 } ]
                [[cover.page]]
                face = "c.owl"
                """.trimIndent(),
            )
        assertEquals(listOf("version", "page", "cover.columns", "cover.page"), t.keys.toList())
        assertEquals(4.0, t["cover.columns"])
        val pages = t["cover.page"] as List<*>
        assertEquals(2, pages.size)
        assertEquals(listOf(mapOf("widget" to "b.owl", "x" to 0.0, "y" to 0.0, "w" to 2.0, "h" to 2.0)), (pages[0] as Map<*, *>)["tiles"])
        assertEquals(mapOf("face" to "c.owl"), pages[1])
        // The section's keys may come after its tables too, and don't fall into the last table.
        val after = Toml.parse("[[cover.page]]\nface = \"c.owl\"\n[cover]\ncolumns = 4\n")
        assertEquals(mapOf("cover.page" to listOf(mapOf("face" to "c.owl")), "cover.columns" to 4.0), after)
    }

    @Test fun writesASectionsArrayOfTablesAfterItsKeys() {
        val t =
            linkedMapOf<String, Any>(
                "version" to 1.0,
                "columns" to 6.0,
                "page" to listOf(linkedMapOf<String, Any>("face" to "a.owl")),
                "cover.columns" to 4.0,
                "cover.page" to listOf(linkedMapOf<String, Any>("tiles" to listOf(linkedMapOf<String, Any>("widget" to "b.owl", "x" to 0.0, "y" to 0.0, "w" to 2.0, "h" to 2.0)))),
            )
        val text = Toml.write(t)
        assertEquals(t, Toml.parse(text))
        assertEquals("version = 1\ncolumns = 6\n\n[[page]]\nface = \"a.owl\"\n\n[cover]\ncolumns = 4\n\n[[cover.page]]\ntiles = [\n  { widget = \"b.owl\", x = 0, y = 0, w = 2, h = 2 },\n]\n", text)
        // Without keys of its own, the section is only its tables.
        assertEquals("[[cover.page]]\nface = \"c.owl\"\n", Toml.write(mapOf("cover.page" to listOf(mapOf("face" to "c.owl")))))
    }

    @Test fun refusesTablesUnderTablesAndDeeperNesting() {
        val bad =
            listOf(
                "[[page]]\nx = 1\n[[page.tiles]]" to 3,
                "[[cover.page]]\nx = 1\n[cover.page]" to 3,
                "[[cover.page]]\nx = 1\n[cover]\npage = 2" to 4,
                "[cover]\ncolumns = 4\n[[cover]]" to 3,
                "[[a.b.c]]" to 1,
            )
        for ((text, line) in bad) {
            try {
                Toml.parse(text)
                fail("parsed: $text")
            } catch (e: Toml.Error) {
                assertEquals(text, line, e.line)
            }
        }
    }
}
