package dev.omalauncher.widgets

import org.junit.Assert.*
import org.junit.Test

class GridReorderTest {
    private fun valid(original: Map<String, Cell>, target: Cell, result: Map<String, Cell>?, cols: Int, rows: Int) {
        assertNotNull(result)
        val plan = result!!
        assertEquals(original.keys, plan.keys)
        plan.forEach { (key, c) ->
            assertEquals(original.getValue(key).w, c.w)
            assertEquals(original.getValue(key).h, c.h)
            assertTrue(c.x >= 0 && c.y >= 0 && c.right <= cols && c.bottom <= rows)
            assertFalse(c.overlaps(target))
            assertFalse(plan.any { (other, cell) -> other != key && cell.overlaps(c) })
        }
    }

    @Test fun emptySpaceKeepsNeighbours() {
        val cells = mapOf("a" to Cell(0, 0, 2, 2))
        assertEquals(cells, reorderCells(cells, Cell(2, 0, 2, 2), 6, 4))
    }

    @Test fun fullRowShiftsForwardAndBackward() {
        val forward = (1..5).associate { "$it" to Cell(it, 0, 1, 1) }
        val shifted = reorderCells(forward, Cell(5, 0, 1, 1), 6, 1, before = true)
        valid(forward, Cell(5, 0, 1, 1), shifted, 6, 1)
        (1..5).forEach { assertEquals(Cell(it - 1, 0, 1, 1), shifted!!["$it"]) }
        val backward = (0..4).associate { "$it" to Cell(it, 0, 1, 1) }
        val reversed = reorderCells(backward, Cell(0, 0, 1, 1), 6, 1)
        valid(backward, Cell(0, 0, 1, 1), reversed, 6, 1)
        (0..4).forEach { assertEquals(Cell(it + 1, 0, 1, 1), reversed!!["$it"]) }
    }

    @Test fun fullPagePreservesMixedWidgetSizes() {
        val cells = mapOf("wide" to Cell(2, 0, 4, 2), "small" to Cell(0, 2, 2, 2), "bottom" to Cell(2, 2, 4, 2))
        val target = Cell(0, 2, 2, 2)
        valid(cells, target, reorderCells(cells, target, 6, 4, before = true), 6, 4)
    }

    @Test fun noRoomAndInvalidTargetsAreRejected() {
        val cells = mapOf("full" to Cell(0, 0, 6, 4))
        assertNull(reorderCells(cells, Cell(0, 0, 1, 1), 6, 4))
        assertNull(reorderCells(emptyMap(), Cell(5, 0, 2, 1), 6, 4))
    }

    @Test fun previewDoesNotMutateSavedCells() {
        val cells = linkedMapOf("a" to Cell(0, 0, 3, 2), "b" to Cell(3, 0, 3, 2))
        val saved = cells.toMap()
        val target = Cell(0, 0, 3, 2)
        val first = reorderCells(cells, target, 6, 4)
        valid(cells, target, first, 6, 4)
        assertEquals(saved, cells)
        assertEquals(first, reorderCells(cells, target, 6, 4))
    }

    @Test fun everyPositionOnFullGridCanBeReordered() {
        for (from in 0 until 24) for (to in 0 until 24) {
            val cells = (0 until 24).filter { it != from }.associate { "$it" to Cell(it % 6, it / 6, 1, 1) }
            val target = Cell(to % 6, to / 6, 1, 1)
            valid(cells, target, reorderCells(cells, target, 6, 4, before = to > from), 6, 4)
        }
    }

    @Test fun crossPageDropOnlyMovesDestinationNeighbours() {
        val cells = mapOf("a" to Cell(0, 0, 3, 2), "b" to Cell(3, 0, 3, 2))
        val target = Cell(0, 0, 3, 2)
        valid(cells, target, reorderCells(cells, target, 6, 4), 6, 4)
        assertEquals(Cell(3, 0, 3, 2), reorderCells(cells, target, 6, 4)!!["b"])
    }
}
