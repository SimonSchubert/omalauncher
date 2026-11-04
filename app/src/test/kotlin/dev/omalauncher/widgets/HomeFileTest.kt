package dev.omalauncher.widgets

import dev.omalauncher.theme.Toml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFileTest {
    private val layout =
        HomeLayout(
            6,
            listOf(
                FacePage("terminal.owl"),
                TilePage(
                    listOf(
                        WidgetTile("50-memory.owl", Cell(0, 0, 3, 4)),
                        AppTile("org.mozilla.firefox", Cell(3, 0, 1, 1)),
                        FolderTile("Work", listOf("com.slack", "linux:foot"), Cell(4, 0, 1, 1)),
                        FolderTile(null, listOf("a", "b"), Cell(5, 0, 1, 1)),
                    ),
                ),
                TilePage(listOf(WidgetTile("60-owl.owl", Cell(0, 0, 6, 3)))),
            ),
        )

    @Test fun readsWhatItWrites() {
        val text = Toml.write(HomeFile.encode(emptyMap(), layout), HomeFile.HEADER)
        val decoded = HomeFile.decode(Toml.parse(text), 6)
        assertEquals(emptyList<String>(), decoded.problems)
        assertEquals(layout.copy(pages = layout.pages.map { p -> if (p is TilePage) TilePage(p.tiles.map(::unsized)) else p }), decoded.layout)
    }

    /** Apps and folders are one cell, so the file doesn't say their size. */
    private fun unsized(t: Tile) =
        when (t) {
            is WidgetTile -> t
            is AppTile -> t.copy(cell = t.cell?.copy(w = 0, h = 0))
            is FolderTile -> t.copy(cell = t.cell?.copy(w = 0, h = 0))
        }

    @Test fun keepsKeysItDoesntKnow() {
        val table = Toml.parse("version = 1\nfuture = \"x\"\n[[page]]\nface = \"a.owl\"\n")
        assertEquals("x", HomeFile.encode(table, layout)["future"])
    }

    /** The cover screen's pages are a `[cover]` section after the main pages, and read back as that screen's layout. */
    @Test fun writesAndReadsACoverSection() {
        val cover = HomeLayout(4, listOf(TilePage(listOf(WidgetTile("50-memory.owl", Cell(0, 0, 4, 3)), AppTile("org.mozilla.firefox", Cell(0, 3, 1, 1)))), TilePage(listOf(WidgetTile("60-owl.owl", null)))))
        val text = Toml.write(HomeFile.encode(emptyMap(), layout, mapOf(Screen.COVER to cover)), HomeFile.HEADER)
        // The section comes after every main page, as one piece: its columns, then its pages. (The header names `[[cover.page]]` too.)
        val mainPages = text.lastIndexOf("[[page]]")
        val section = text.indexOf("[cover]\ncolumns = 4\n")
        assertTrue(text, section > mainPages && text.indexOf("[[cover.page]]", section) > section)
        val decoded = HomeFile.decode(Toml.parse(text), 6)
        assertEquals(emptyList<String>(), decoded.problems)
        assertEquals(setOf(Screen.MAIN, Screen.COVER), decoded.screens.keys)
        assertEquals(decoded.layout, decoded.screens[Screen.MAIN])
        assertEquals(cover.copy(pages = cover.pages.map { p -> if (p is TilePage) TilePage(p.tiles.map(::unsized)) else p }), decoded.screens[Screen.COVER])
    }

    /** The unfolded screen's pages are an `[unfolded]` section beside the cover's, each on its own grid; a section without `columns` is on that screen's. */
    @Test fun writesAndReadsAnUnfoldedSectionOnItsOwnGrid() {
        val unfolded = HomeLayout(12, listOf(TilePage(listOf(WidgetTile("50-memory.owl", Cell(0, 0, 6, 4)), WidgetTile("60-owl.owl", Cell(6, 0, 6, 3))))))
        val cover = HomeLayout(6, listOf(TilePage(listOf(WidgetTile("60-owl.owl", Cell(0, 0, 6, 3))))))
        val text = Toml.write(HomeFile.encode(emptyMap(), layout, mapOf(Screen.COVER to cover, Screen.UNFOLDED to unfolded)), HomeFile.HEADER)
        assertTrue(text, text.contains("[unfolded]\ncolumns = 12\n") && text.contains("[[unfolded.page]]"))
        val decoded = HomeFile.decode(Toml.parse(text), 6)
        assertEquals(emptyList<String>(), decoded.problems)
        assertEquals(unfolded, decoded.screens[Screen.UNFOLDED])
        assertEquals(cover, decoded.screens[Screen.COVER])
        val bare = HomeFile.decode(Toml.parse("[[page]]\nface = \"a.owl\"\n[[unfolded.page]]\ntiles = [ { widget = \"b.owl\", x = 6, y = 0, w = 6, h = 2 } ]\n"), 6) { if (it == Screen.UNFOLDED) 12 else 6 }
        assertEquals(12, bare.screens.getValue(Screen.UNFOLDED).columns)
        assertEquals(6, bare.layout.columns)
    }

    /** Without the section the cover screen has no layout in the file; a bad one is a problem named after its section. */
    @Test fun aFileWithoutACoverSectionHasNoCoverLayout() {
        val decoded = HomeFile.decode(Toml.parse("[[page]]\nface = \"a.owl\"\n"), 6)
        assertEquals(setOf(Screen.MAIN), decoded.screens.keys)
        val bad = HomeFile.decode(Toml.parse("[[page]]\nface = \"a.owl\"\n[cover]\ncolumns = 0\n[[cover.page]]\nother = 1\n"), 6)
        assertEquals(setOf(Screen.MAIN, Screen.COVER), bad.screens.keys)
        assertEquals(6, bad.screens.getValue(Screen.COVER).columns)
        val problems = bad.problems.joinToString("\n")
        assertTrue(problems, "cover.columns:" in problems && "cover.page 1: other isn't a page's" in problems)
    }

    @Test fun numbersPagesFromTheFirstOfTiles() {
        assertEquals(listOf(-1, 0, 1, 2, 3), HomeFile.numbers(listOf(FacePage("a"), FacePage("b"), TilePage(emptyList()), FacePage("c"), TilePage(emptyList()))))
        assertEquals(listOf(1), HomeFile.numbers(listOf(TilePage(emptyList()))))
        assertEquals(listOf(-1, 0), HomeFile.numbers(listOf(FacePage("a"), FacePage("b"))))
    }

    @Test fun tilesWithoutAPlaceTakeTheFirstFreeSpot() {
        val decoded = HomeFile.decode(Toml.parse("[[page]]\ntiles = [ { widget = \"a.owl\" }, { app = \"b\", x = 2, y = 1 } ]\n"), 6)
        assertEquals(emptyList<String>(), decoded.problems)
        assertEquals(TilePage(listOf(WidgetTile("a.owl", null), AppTile("b", Cell(2, 1, 0, 0)))), decoded.layout.pages.single())
    }

    @Test fun saysWhatItCouldntRead() {
        val text =
            """
            columns = 2.5
            [[page]]
            face = "a.owl"
            tiles = [ { widget = "b.owl", x = 1 }, { app = "c", widget = "d" }, { app = "" }, { widget = "e.owl", x = 0, y = 0, w = 0, h = 2 }, "f" ]
            other = 1
            """.trimIndent()
        val decoded = HomeFile.decode(Toml.parse(text), 6)
        assertEquals(6, decoded.layout.columns)
        // The face, then its tiles on a page of their own; a bad size keeps the tile where it says, at its own.
        assertEquals(listOf(FacePage("a.owl"), TilePage(listOf(WidgetTile("b.owl", null), WidgetTile("e.owl", Cell(0, 0, 0, 0))))), decoded.layout.pages)
        val problems = decoded.problems.joinToString("\n")
        for (part in listOf("columns:", "other isn't a page's", "its tiles went on the next", "tile 1: x and y", "tile 2: a tile is one of", "tile 3: app takes", "tile 4: w takes", "tile 5: write a tile")) {
            assertTrue(problems, part in problems)
        }
    }
}
