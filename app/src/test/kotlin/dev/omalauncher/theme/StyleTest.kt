package dev.omalauncher.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StyleTest {
    @Test fun parsesSectionsAndValues() {
        val t =
            Toml.parse(
                """
                # a comment
                version = 1
                theme = "nord" # trailing
                [home]
                gaps-in = 4.5
                tile-borders = false
                literal = 'literal \n'
                [status-bar]
                background = "#1a1b26"
                navigation-bar.opacity = 1
                """.trimIndent(),
            )
        assertEquals(1.0, t["version"])
        assertEquals("nord", t["theme"])
        assertEquals(4.5, t["home.gaps-in"])
        assertEquals(false, t["home.tile-borders"])
        assertEquals("literal \\n", t["home.literal"])
        assertEquals("#1a1b26", t["status-bar.background"])
        assertEquals(1.0, t["status-bar.navigation-bar.opacity"])
    }

    @Test fun reportsTheLineItCantRead() {
        for ((text, line) in listOf("a = 1\nb = nord" to 2, "[home\n" to 1, "a = [1, 2]" to 1, "a = 1\na = 2" to 2, "a = \"open" to 1, "\"my key\" = 1" to 1, "[\"a.b\"]" to 1)) {
            try {
                Toml.parse(text)
                fail("parsed: $text")
            } catch (e: Toml.Error) {
                assertEquals(text, line, e.line)
            }
        }
    }

    @Test fun writesWhatItReads() {
        val t = linkedMapOf<String, Any>("version" to 1.0, "theme" to "a \"b\"", "widgets.gaps-in" to 6.0, "corners.scale" to 0.5, "home.recent-apps" to false)
        val text = Toml.write(t)
        assertEquals(t, Toml.parse(text))
        assertTrue(text, "gaps-in = 6\n" in text)
        // A section's keys stay together, though they weren't in the map.
        assertEquals(1, Regex("\\[home]").findAll(text).count())
    }

    /** What the launcher writes, it must read back: anything else would lose the whole file. */
    @Test fun writesControlCharactersReadably() {
        val t = linkedMapOf<String, Any>("terminal.font" to "a\rb\u0001c\td\ne\\f\u007f")
        assertEquals(t, Toml.parse(Toml.write(t)))
        assertEquals("\u00e9", Toml.parse("a = \"\\u00e9\"")["a"])
    }

    @Test fun keepsUnknownKeysReadably() {
        val table = Toml.parse("version = 1\nfuture-thing = 1\n[future]\nnested.key = \"x\"\n")
        val text = Toml.write(StyleFile.encode(table, Style()))
        assertEquals(table, Toml.parse(text))
    }

    @Test fun defaultsFileReadsAsTheDefaults() {
        val decoded = StyleFile.decode(Toml.parse(StyleFile.defaultsText()))
        assertEquals(emptyList<String>(), decoded.problems)
        assertEquals(Style(), decoded.style)
    }

    @Test fun badValuesKeepThePreviousOne() {
        val previous = Style().with(Style.GAPS_IN, 10.0)
        val t = linkedMapOf<String, Any>("widgets.gaps-in" to 99.0, "corners.scale" to "round", "status-bar.background" to "acent", "home.tiles" to true)
        val decoded = StyleFile.decode(t, previous)
        assertEquals(10.0, decoded.style[Style.GAPS_IN], 0.0)
        assertEquals(0.0, decoded.style[Style.CORNER_SCALE], 0.0)
        assertEquals(ColorSpec.THEME, decoded.style[Style.STATUS_BAR.background])
        assertEquals(4, decoded.problems.size)
        assertTrue(decoded.problems[0], decoded.problems[0].startsWith("widgets.gaps-in: takes a number from 0 to 24; keeping 10"))
        assertTrue(decoded.problems.last().startsWith("home.tiles: not a setting"))
    }

    @Test fun outOfPresetValuesAreFine() {
        val t = linkedMapOf<String, Any>("corners.scale" to 0.75, "terminal.text" to 13.0, "navigation-bar.border" to "none", "status-bar.background" to "Accent")
        val decoded = StyleFile.decode(t)
        assertEquals(emptyList<String>(), decoded.problems)
        assertEquals(0.75, decoded.style[Style.CORNER_SCALE], 0.0)
        assertEquals("accent", decoded.style[Style.STATUS_BAR.background])
        assertEquals(ColorSpec.NONE, decoded.style[Style.NAVIGATION_BAR.border])
    }

    @Test fun eachBarHasItsOwnBorderWidth() {
        val decoded = StyleFile.decode(mapOf("status-bar.border-width" to 0.0, "navigation-bar.border-width" to 9.0))
        assertEquals(0.0, decoded.style[Style.STATUS_BAR.borderWidth], 0.0)
        assertEquals(2.0, decoded.style[Style.NAVIGATION_BAR.borderWidth], 0.0)
        assertEquals(listOf("navigation-bar.border-width"), decoded.problems.map { it.substringBefore(':') })
    }

    @Test fun gapsAndBordersReadApart() {
        val t = linkedMapOf<String, Any>("widgets.gaps-in" to 3.0, "widgets.gaps-out" to 20.0, "widgets.border-focused" to "#ff0000", "widgets.border-width" to 0.0)
        val decoded = StyleFile.decode(t)
        assertEquals(3.0, decoded.style[Style.GAPS_IN], 0.0)
        assertEquals(20.0, decoded.style[Style.GAPS_OUT], 0.0)
        assertEquals("#ff0000", decoded.style[Style.TILE_BORDER_FOCUSED])
        assertEquals(0.0, decoded.style[Style.TILE_BORDER_WIDTH], 0.0)
        assertEquals(emptyList<String>(), decoded.problems)
        // No borders is a width of 0, not a color.
        assertEquals(2, StyleFile.decode(mapOf("widgets.border" to "none", "widgets.border-focused" to "none")).problems.size)
    }

    @Test fun columnsAreAWholeNumberFromFourToSix() {
        assertEquals(6, Style()[Style.COLUMNS])
        val five = Toml.parse("[home]\ncolumns = 5\n")
        assertEquals(5, StyleFile.decode(five).style[Style.COLUMNS])
        assertEquals("version = 1\n\n[home]\ncolumns = 5\n", Toml.write(StyleFile.encode(five, Style().with(Style.COLUMNS, 5)), ""))
        for (bad in listOf(5.5, 3.0, 7.0)) {
            val decoded = StyleFile.decode(mapOf("home.columns" to bad), Style().with(Style.COLUMNS, 5))
            assertEquals(5, decoded.style[Style.COLUMNS])
            assertEquals(listOf("home.columns: takes a whole number from 4 to 6; keeping 5"), decoded.problems)
        }
    }

    @Test fun absentKeysAreDefaultsNotThePreviousValues() {
        val previous = Style().with(Style.GAPS_IN, 10.0)
        assertEquals(6.0, StyleFile.decode(emptyMap(), previous).style[Style.GAPS_IN], 0.0)
    }

    @Test fun encodesOnlyWhatDiffersAndKeepsUnknownKeys() {
        val table = linkedMapOf<String, Any>("version" to 1.0, "future.thing" to "x", "widgets.gaps-in" to 8.0)
        val style = Style().with(Style.GAPS_IN, 6.0).with(Style.TILE_BORDER_WIDTH, 0.0).with(Style.THEME, "nord")
        val out = StyleFile.encode(table, style)
        assertEquals(linkedMapOf<String, Any>("version" to 1.0, "future.thing" to "x", "theme" to "nord", "widgets.border-width" to 0.0), out)
    }

    @Test fun noChangeNoDifference() {
        val a = Style().with(Style.GAPS_IN, 6.0)
        assertEquals(Style(), a)
        assertEquals(setOf("widgets.gaps-in"), Style().with(Style.GAPS_IN, 7.0).changed(Style()))
        assertFalse(Style().with(Style.THEME, "nord") == Style())
    }

    @Test fun newerFilesAreReadAsTheyAre() {
        val t = linkedMapOf<String, Any>("version" to 9.0)
        StyleFile.migrate(t)
        assertEquals(9.0, t["version"])
        val none = linkedMapOf<String, Any>()
        StyleFile.migrate(none)
        assertEquals(StyleFile.VERSION.toDouble(), none["version"])
    }

    @Test fun versionsBelowOneReadAsTheFirst() {
        for (v in listOf(0.0, -3.0)) {
            val t = linkedMapOf<String, Any>("version" to v)
            StyleFile.migrate(t)
            assertEquals(StyleFile.VERSION.toDouble(), t["version"])
        }
    }

    /** Every version but the current one needs a step to the next. */
    @Test fun aMigrationPerVersion() {
        assertEquals(StyleFile.VERSION - 1, StyleFile.MIGRATIONS.size)
    }
}
