package dev.omalauncher.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

fun widget(
    name: String,
    w: Int,
    h: Int,
    page: Int = 1,
    minWidth: Int = 1,
) = WidgetItem(WidgetSpec(File(name), name, page, 0, null, size = w to h, minSize = minWidth to 1))

class WidgetLayoutTest {
    @Test fun wideGridPlacesAcrossBothHalves() {
        val prefs = FakePrefs()
        val layout = WidgetLayout(prefs, 12, heal = true)
        layout.place(listOf(widget("a.owl", 12, 2)), 12, 10)
        assertEquals(Cell(0, 0, 12, 2), layout.cell("a.owl"))
        // Saved with its own column count, so reading it back doesn't rescale it.
        val again = WidgetLayout(prefs, 8, heal = true)
        assertEquals(12, again.columns)
        assertEquals(Cell(0, 0, 12, 2), again.cell("a.owl"))
    }

    @Test fun columnsStartAtTheInitialCountOnlyWhenNothingIsSaved() {
        assertEquals(CLASSIC_COLUMNS, WidgetLayout(FakePrefs()).columns)
        assertEquals(10, WidgetLayout(FakePrefs(), 10).columns)
        val prefs = FakePrefs()
        WidgetLayout(prefs).apply {
            set("a.owl", Cell(3, 0, 3, 2))
            save()
        }
        val open = WidgetLayout(prefs, 12, heal = true)
        assertEquals(CLASSIC_COLUMNS, open.columns)
        open.regrid(12, listOf(listOf(widget("a.owl", 3, 2))))
        assertEquals(Cell(6, 0, 6, 2), open.cell("a.owl"))
    }

    @Test fun regridWorksOnASecondLayoutAlone() {
        val phonePrefs = FakePrefs()
        val openPrefs = FakePrefs()
        val phone = WidgetLayout(phonePrefs)
        val open = WidgetLayout(openPrefs, 12, heal = true)
        phone.set("a.owl", Cell(0, 0, 6, 2))
        open.set("a.owl", Cell(6, 0, 6, 2))
        phone.save()
        open.regrid(8, listOf(listOf(widget("a.owl", 6, 2))))
        assertEquals(8, open.columns)
        assertEquals(Cell(4, 0, 4, 2), open.cell("a.owl"))
        assertEquals(CLASSIC_COLUMNS, phone.columns)
        assertEquals(Cell(0, 0, 6, 2), phone.cell("a.owl"))
        assertEquals(Cell(4, 0, 4, 2), WidgetLayout(openPrefs, 12).cell("a.owl"))
        assertEquals(CLASSIC_COLUMNS, WidgetLayout(phonePrefs).columns)
    }

    @Test fun clearCellKeepsThePage() {
        val layout = WidgetLayout(FakePrefs(), 12)
        layout.set("a.owl", Cell(0, 0, 2, 2))
        layout.setPage("a.owl", 3)
        layout.clearCell("a.owl")
        assertNull(layout.cell("a.owl"))
        assertEquals(3, layout.page("a.owl"))
    }

    @Test fun setPageCanLeaveSavingToTheCaller() {
        val prefs = FakePrefs()
        val layout = WidgetLayout(prefs)
        layout.setPage("a.owl", 2, persist = false)
        layout.clearCell("b.owl", persist = false)
        assertTrue(prefs.values.isEmpty())
        layout.save()
        assertEquals(2, WidgetLayout(prefs).page("a.owl"))
    }

    @Test fun removingWhatIsntThereWritesNothing() {
        val prefs = FakePrefs()
        val layout = WidgetLayout(prefs)
        layout.remove("a.owl")
        assertTrue(prefs.values.isEmpty())
        layout.set("a.owl", Cell(0, 0, 1, 1))
        layout.remove("a.owl")
        assertNull(WidgetLayout(prefs).cell("a.owl"))
    }

    @Test fun aCellPastTheEdgeOrOverAnotherIsPlacedAfreshWhenHealing() {
        val layout = WidgetLayout(FakePrefs(), heal = true)
        // As a drop on the open grid could save into the folded layout before profiles switched touch handling.
        layout.set("a.owl", Cell(0, 3, 6, 4))
        layout.set("b.owl", Cell(6, 5, 6, 2))
        layout.set("c.owl", Cell(0, 4, 3, 2))
        layout.place(listOf(widget("a.owl", 6, 4), widget("b.owl", 6, 2), widget("c.owl", 3, 2)), CLASSIC_COLUMNS, 10)
        assertEquals(Cell(0, 3, 6, 4), layout.cell("a.owl"))
        assertEquals(Cell(0, 0, 6, 2), layout.cell("b.owl"))
        assertEquals(Cell(0, 7, 3, 2), layout.cell("c.owl"))
    }

    @Test fun thePhoneLayoutLeavesSavedCellsAlone() {
        val layout = WidgetLayout(FakePrefs())
        layout.set("a.owl", Cell(0, 3, 6, 4))
        layout.set("b.owl", Cell(6, 5, 6, 2))
        layout.set("c.owl", Cell(0, 4, 3, 2))
        layout.place(listOf(widget("a.owl", 6, 4), widget("b.owl", 6, 2), widget("c.owl", 3, 2)), CLASSIC_COLUMNS, 10)
        assertEquals(Cell(0, 3, 6, 4), layout.cell("a.owl"))
        assertEquals(Cell(6, 5, 6, 2), layout.cell("b.owl"))
        assertEquals(Cell(0, 4, 3, 2), layout.cell("c.owl"))
    }

    @Test fun aSoundLayoutIsLeftAsItIs() {
        val prefs = FakePrefs()
        val layout = WidgetLayout(prefs, heal = true)
        layout.set("a.owl", Cell(0, 0, 3, 3))
        layout.set("b.owl", Cell(3, 0, 3, 3))
        layout.save()
        val before = HashMap(prefs.values)
        layout.place(listOf(widget("a.owl", 3, 3), widget("b.owl", 3, 3)), CLASSIC_COLUMNS, 10)
        assertEquals(before, prefs.values)
    }
}
