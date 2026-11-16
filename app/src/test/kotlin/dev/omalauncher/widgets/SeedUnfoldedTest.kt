package dev.omalauncher.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedUnfoldedTest {
    /** A tile on main page [page] at [cell], at least [minWidth] wide. */
    private data class Placed(
        val key: String,
        val page: Int,
        val cell: Cell?,
        val minWidth: Int = 1,
    )

    private fun seed(
        main: List<Placed>,
        mainColumns: Int = 6,
        columns: Int = 12,
    ) = seedUnfolded(main.map { Triple(it.key, it.page, it.cell) }, mainColumns, columns) { key -> main.first { it.key == key }.minWidth }

    @Test fun mainPagesPairUpSideBySide() {
        val seeded =
            seed(
                listOf(
                    Placed("a", 1, Cell(0, 0, 6, 2)),
                    Placed("b", 2, Cell(1, 3, 2, 2)),
                    Placed("c", 3, Cell(0, 0, 3, 3)),
                    Placed("d", 4, Cell(5, 0, 1, 1)),
                ),
            )
        assertEquals(1 to Cell(0, 0, 6, 2), seeded["a"])
        assertEquals(1 to Cell(7, 3, 2, 2), seeded["b"])
        assertEquals(2 to Cell(0, 0, 3, 3), seeded["c"])
        assertEquals(2 to Cell(11, 0, 1, 1), seeded["d"])
    }

    @Test fun anOddLastPageHasAnEmptyRightHalf() {
        assertEquals(3 to Cell(0, 0, 2, 2), seed(listOf(Placed("e", 5, Cell(0, 0, 2, 2))))["e"])
    }

    @Test fun aTileNotLaidOutYetKeepsOnlyItsPage() {
        assertEquals(2 to null, seed(listOf(Placed("f", 4, null)))["f"])
    }

    @Test fun aFaceKeepsItsPageAndHasNoCell() {
        assertEquals(0 to null, seed(listOf(Placed("face", 0, null)))["face"])
        assertEquals(-1 to null, seed(listOf(Placed("left", -1, Cell(0, 0, 6, 6))))["left"])
    }

    @Test fun sixColumnsOntoTwelveIsTheShiftAlone() {
        val main = listOf(Placed("a", 1, Cell(2, 1, 3, 2)), Placed("b", 2, Cell(0, 0, 6, 4)), Placed("c", 2, Cell(4, 5, 2, 1)))
        val seeded = seed(main)
        assertEquals(1 to Cell(2, 1, 3, 2), seeded["a"])
        assertEquals(1 to Cell(6, 0, 6, 4), seeded["b"])
        assertEquals(1 to Cell(10, 5, 2, 1), seeded["c"])
    }

    @Test fun nineColumnsRescaleOntoTwelveAndAnOverlapIsPlacedAfresh() {
        val main =
            listOf(
                Placed("a", 1, Cell(0, 0, 9, 2)),
                Placed("b", 1, Cell(0, 2, 4, 2), minWidth = 3),
                // Side by side on the main pages, they land on the same unfolded column.
                Placed("c", 2, Cell(0, 0, 1, 1)),
                Placed("d", 2, Cell(1, 0, 1, 1)),
            )
        val seeded = seed(main, mainColumns = 9)
        // Eighteen columns onto twelve: two thirds.
        assertEquals(1 to Cell(0, 0, 6, 2), seeded["a"])
        assertEquals(1 to Cell(0, 2, 3, 2), seeded["b"])
        assertEquals(1 to Cell(6, 0, 1, 1), seeded["c"])
        assertEquals(1 to null, seeded["d"])
    }

    @Test fun fourColumnsStayOnTheTwelveColumnGrid() {
        val main = (1..4).flatMap { page -> (0 until 4).map { x -> Placed("p$page-$x", page, Cell(x, 0, 1, 1)) } } + Placed("wide", 2, Cell(0, 1, 4, 3))
        val seeded = seed(main, mainColumns = 4)
        for ((_, place) in seeded) {
            val c = place.second ?: continue
            assertTrue("$c", c.x >= 0 && c.right <= 12 && c.w >= 1)
        }
        assertEquals(1 to Cell(6, 1, 6, 3), seeded["wide"])
        assertEquals(2 to Cell(10, 0, 1, 1), seeded["p4-3"])
        // No two cells on one unfolded page overlap.
        for (page in 1..2) {
            val cells = seeded.values.filter { it.first == page }.mapNotNull { it.second }
            for (i in cells.indices) for (j in i + 1 until cells.size) assertTrue("${cells[i]} ${cells[j]}", !cells[i].overlaps(cells[j]))
        }
        assertNull(seeded["missing"])
    }

    @Test fun onlyTheUnfoldedScreenPairsMainPages() {
        assertEquals(listOf(1, 1, 2, 2, 3), (1..5).map(Screen.UNFOLDED::fromMain))
        assertEquals(listOf(0, -1), listOf(0, -1).map(Screen.UNFOLDED::fromMain))
        assertEquals(listOf(1, 2, 3), (1..3).map(Screen.COVER::fromMain))
        assertEquals(listOf(1, 2, 3), (1..3).map(Screen.MAIN::fromMain))
    }
}
