package dev.omalauncher.widgets.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColumnHugsTest {
    @Test fun anUnsizedColumnInARowHugsSinceOwl11() {
        assertTrue(columnHugs(11, sized = false, inRow = true, inHuggingColumn = false))
        assertFalse(columnHugs(10, sized = false, inRow = true, inHuggingColumn = false))
    }

    @Test fun aSizedColumnFillsWhatItWasGiven() {
        // The drawer's cells have weight: 1, and keep their even share of the row.
        assertFalse(columnHugs(11, sized = true, inRow = true, inHuggingColumn = false))
        assertFalse(columnHugs(11, sized = true, inRow = false, inHuggingColumn = true))
    }

    @Test fun aColumnInAHuggingColumnHugsToo() {
        assertTrue(columnHugs(11, sized = false, inRow = false, inHuggingColumn = true))
    }

    @Test fun aColumnElsewhereFills() {
        assertFalse(columnHugs(11, sized = false, inRow = false, inHuggingColumn = false))
    }
}
