package dev.omalauncher.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class CellTest {
    @Test fun rescalesOntoFewerColumnsAndBack() {
        val row = (0 until 6).map { Cell(it, 0, 1, 1) }
        assertEquals(listOf(0, 0, 1, 2, 3, 4), row.map { it.rescaled(6, 5).x })
        assertEquals(listOf(0, 0, 1, 2, 3, 4), row.map { it.rescaled(6, 5).rescaled(5, 6).x })
        assertEquals(Cell(2, 3, 2, 2), Cell(3, 3, 3, 2).rescaled(6, 5).rescaled(5, 6))
        assertEquals(Cell(0, 0, 6, 2), Cell(0, 0, 6, 2).rescaled(6, 4).rescaled(4, 6))
        assertEquals(Cell(4, 1, 2, 1), Cell(4, 1, 2, 1).rescaled(6, 6))
    }

    @Test fun keepsAWidgetAsWideAsItsMinimum() {
        assertEquals(Cell(2, 0, 2, 3), Cell(3, 0, 3, 3).rescaled(6, 4))
        assertEquals(Cell(1, 0, 3, 3), Cell(3, 0, 3, 3).rescaled(6, 4, minWidth = 3))
        assertEquals(Cell(0, 0, 4, 2), Cell(1, 0, 5, 2).rescaled(6, 4, minWidth = 5))
    }

    @Test fun aCellPastItsOwnColumnsEndsUpAtTheNewEdge() {
        assertEquals(Cell(11, 0, 1, 1), Cell(8, 0, 1, 1).rescaled(6, 12))
        assertEquals(Cell(3, 0, 1, 2), Cell(7, 0, 2, 2).rescaled(6, 4))
        assertEquals(Cell(9, 0, 3, 1), Cell(6, 0, 1, 1).rescaled(6, 12, minWidth = 3))
    }
}
