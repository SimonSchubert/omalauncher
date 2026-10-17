package dev.omalauncher.widgets

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeProfilesTest {
    private val foldedPrefs = FakePrefs()
    private val openPrefs = FakePrefs()
    private val statePrefs = FakePrefs()

    private var enabled = true

    private fun profiles() = HomeProfiles(statePrefs, WidgetLayout(foldedPrefs), WidgetLayout(openPrefs, 12, heal = true)) { enabled }

    @Test fun hidingIsPerProfileAndPersists() {
        profiles().apply {
            active = Profile.Open
            hide("a.owl")
        }
        val again = profiles()
        assertTrue(again.isHidden("a.owl", Profile.Open))
        assertFalse(again.isHidden("a.owl", Profile.Folded))
    }

    @Test fun hidingFreesTheCell() {
        val p = profiles()
        p.folded.set("a.owl", Cell(0, 0, 2, 2))
        p.hide("a.owl")
        assertNull(p.folded.cell("a.owl"))
    }

    @Test fun forgetClearsBothProfiles() {
        val p = profiles()
        p.folded.set("a.owl", Cell(0, 0, 2, 2))
        p.open.set("a.owl", Cell(6, 0, 2, 2))
        p.hide("a.owl", Profile.Folded)
        p.forget("a.owl")
        assertNull(p.folded.cell("a.owl"))
        assertNull(p.open.cell("a.owl"))
        assertFalse(p.isHidden("a.owl", Profile.Folded))
    }

    @Test fun forgettingWhatWasNeverPlacedRecomposesNothing() {
        val p = profiles()
        p.folded.set("a.owl", Cell(0, 0, 2, 2))
        val written = HashSet<Any>()
        val snapshot = Snapshot.takeMutableSnapshot(writeObserver = { written += it })
        try {
            snapshot.enter { p.forget("gone.owl") }
            assertTrue(written.isEmpty())
            snapshot.enter { p.forget("a.owl") }
            assertFalse(written.isEmpty())
        } finally {
            snapshot.dispose()
        }
    }

    @Test fun openPageDefaultsToThePairedFoldedPage() {
        val p = profiles()
        p.active = Profile.Open
        assertEquals(2, p.pageIn(widget("new.owl", 2, 2, page = 4)))
        assertEquals(2, p.pageIn(widget("new.owl", 2, 2, page = 3)))
        p.setOpenPage("new.owl", 5)
        assertEquals(5, p.pageIn(widget("new.owl", 2, 2, page = 3)))
        assertEquals(3, p.pageIn(widget("new.owl", 2, 2, page = 3), Profile.Folded))
    }

    @Test fun somethingNewShowsOnlyWhereItWasMade() {
        val p = profiles()
        p.seed(emptyList())
        p.active = Profile.Open
        assertEquals(Arrival.New, p.arrive("a.owl", existed = false))
        assertTrue(p.isHidden("a.owl", Profile.Folded))
        assertFalse(p.isHidden("a.owl", Profile.Open))
    }

    @Test fun somethingMadeBeforeTheFirstUnfoldCarriesOver() {
        val p = profiles()
        assertEquals(Arrival.New, p.arrive("a.owl", existed = false))
        assertFalse(p.isHidden("a.owl", Profile.Open))
        p.seed(listOf(Placed("a.owl", 1, Cell(0, 0, 2, 2))))
        assertEquals(Cell(0, 0, 2, 2), p.open.cell("a.owl"))
    }

    @Test fun somethingHiddenHereComesBackInsteadOfACopy() {
        val p = profiles()
        p.hide("agent-claude.owl")
        assertEquals(Arrival.Reveal, p.arrive("agent-claude.owl", existed = true))
        assertEquals(Arrival.Present, p.arrive("other.owl", existed = true))
    }

    @Test fun aNewTerminalLeavesTheHiddenOneHidden() {
        val p = profiles()
        p.hide("terminal-abc.owl")
        assertEquals(Arrival.New, p.arrive("terminal-xyz.owl", existed = false))
        assertTrue(p.isHidden("terminal-abc.owl"))
    }

    @Test fun arrivingAfterForgetIsNew() {
        val p = profiles()
        p.hide("a.owl")
        p.forget("a.owl")
        assertEquals(Arrival.New, p.arrive("a.owl", existed = false))
        assertFalse(p.isHidden("a.owl"))
    }

    @Test fun seedingHappensOnceAndCarriesHiddenItems() {
        val p = profiles()
        p.hide("b.owl", Profile.Folded)
        p.seed(listOf(Placed("a.owl", 2, Cell(0, 0, 2, 2)), Placed("b.owl", 1, null)))
        assertEquals(Cell(6, 0, 2, 2), p.open.cell("a.owl"))
        assertEquals(1, p.open.page("a.owl"))
        assertTrue(p.isHidden("b.owl", Profile.Open))
        p.open.set("a.owl", Cell(0, 4, 2, 2))
        p.seed(listOf(Placed("a.owl", 2, Cell(0, 0, 2, 2))))
        assertEquals(Cell(0, 4, 2, 2), p.open.cell("a.owl"))
        assertTrue(profiles().seeded)
    }

    @Test fun inheritMovesAPlaceInBothProfiles() {
        val p = profiles()
        p.folded.set("app:a", Cell(1, 1, 1, 1))
        p.open.set("app:a", Cell(7, 1, 1, 1))
        p.setOpenPage("app:a", 2)
        p.hide("app:a", Profile.Open)
        p.inherit(from = "app:a", to = "folder:x")
        assertEquals(Cell(1, 1, 1, 1), p.folded.cell("folder:x"))
        assertEquals(2, p.open.page("folder:x"))
        assertTrue(p.isHidden("folder:x", Profile.Open))
        assertNull(p.folded.cell("app:a"))
        assertFalse(p.isHidden("app:a", Profile.Open))
    }

    @Test fun foldedStorageIsUntouched() {
        WidgetLayout(foldedPrefs).apply {
            set("a.owl", Cell(0, 0, 2, 2))
            save()
        }
        val before = HashMap(foldedPrefs.values)
        val p = profiles()
        p.seed(listOf(Placed("a.owl", 1, Cell(0, 0, 2, 2))))
        p.active = Profile.Open
        p.open.set("a.owl", Cell(4, 0, 6, 2))
        p.open.save()
        p.setOpenPage("a.owl", 3)
        assertEquals(before, foldedPrefs.values)
    }

    @Test fun seedingWaitsForEverythingToLoad() {
        val p = profiles()
        val items = mutableListOf(Placed("app:a", 1, Cell(0, 0, 1, 1)))
        // Started unfolded: home shows before the widget files are read.
        p.show(Profile.Open, ready = false) { items }
        assertFalse(p.seeded)
        // The open grid placed the app on its own meanwhile.
        p.open.set("app:a", Cell(3, 3, 1, 1))
        items += Placed("w.owl", 2, Cell(0, 0, 3, 2))
        items += Placed("unplaced.owl", 1, null)
        p.open.set("unplaced.owl", Cell(5, 5, 1, 1))
        p.catchUp(ready = true) { items }
        assertTrue(p.seeded)
        assertEquals(Cell(0, 0, 1, 1), p.open.cell("app:a"))
        assertEquals(Cell(6, 0, 3, 2), p.open.cell("w.owl"))
        assertEquals(1, p.open.page("w.owl"))
        assertNull(p.open.cell("unplaced.owl"))
    }

    @Test fun seedingWaitsWhileFolded() {
        val p = profiles()
        p.show(Profile.Folded, ready = true) { listOf(Placed("a.owl", 1, null)) }
        assertFalse(p.seeded)
        p.show(Profile.Open, ready = true) { listOf(Placed("a.owl", 1, null)) }
        assertTrue(p.seeded)
        assertEquals(Profile.Open, p.active)
    }

    @Test fun settlingPinsTheOpenPageSoFoldedMovesStayFolded() {
        val p = profiles()
        val before = widget("agent.owl", 2, 2, page = 3)
        // Before the first unfold nothing is pinned: seeding does that.
        p.settle(listOf(before))
        assertNull(p.open.page("agent.owl"))
        p.seed(emptyList())
        p.settle(listOf(before))
        assertEquals(2, p.open.page("agent.owl"))
        // Moved folded to page 7: still on open page 2.
        assertEquals(2, p.pageIn(widget("agent.owl", 2, 2, page = 7), Profile.Open))
        // One with its own open page keeps it.
        p.setOpenPage("agent.owl", 4)
        p.settle(listOf(widget("agent.owl", 2, 2, page = 1)))
        assertEquals(4, p.open.page("agent.owl"))
    }

    @Test fun everythingSurvivesARestart() {
        val p = profiles()
        p.folded.set("a.owl", Cell(0, 0, 2, 2))
        p.folded.setPage("a.owl", 2)
        p.hide("h.owl", Profile.Folded)
        p.seed(listOf(Placed("a.owl", 2, Cell(0, 0, 2, 2)), Placed("app:x", 1, Cell(1, 1, 1, 1))))
        p.active = Profile.Open
        p.hide("gone.owl")
        p.hide("back.owl")
        p.unhide("back.owl")
        p.open.set("app:x", Cell(8, 1, 1, 1))
        p.setOpenPage("app:x", 3)
        p.inherit(from = "app:x", to = "folder:f")
        p.setOpenPage("a.owl", 2)
        p.open.save()
        p.folded.save()

        val again = profiles()
        assertTrue(again.seeded)
        assertEquals(Cell(0, 0, 2, 2), again.folded.cell("a.owl"))
        assertEquals(2, again.folded.page("a.owl"))
        assertEquals(Cell(6, 0, 2, 2), again.open.cell("a.owl"))
        assertEquals(2, again.open.page("a.owl"))
        assertEquals(Cell(8, 1, 1, 1), again.open.cell("folder:f"))
        assertEquals(3, again.open.page("folder:f"))
        assertNull(again.open.cell("app:x"))
        assertNull(again.open.page("app:x"))
        assertEquals(listOf("h.owl"), again.hiddenKeys(Profile.Folded))
        assertEquals(listOf("h.owl", "gone.owl"), again.hiddenKeys(Profile.Open))
        assertEquals(12, again.open.columns)
    }

    @Test fun disabledNothingIsHiddenAndSomethingNewShowsInBoth() {
        val p = profiles()
        p.seed(emptyList())
        p.hide("a.owl", Profile.Folded)
        enabled = false
        assertFalse(p.isHidden("a.owl", Profile.Folded))
        assertEquals(emptyList<String>(), p.hiddenKeys(Profile.Folded))
        assertEquals(Arrival.Present, p.arrive("a.owl", existed = true))
        assertEquals(Arrival.New, p.arrive("b.owl", existed = false))
        // Turned on again, b shows in both; the old mark on a counts again.
        enabled = true
        assertFalse(p.isHidden("b.owl", Profile.Open))
        assertTrue(p.isHidden("a.owl", Profile.Folded))
    }

    @Test fun disabledNothingSeedsOrSettles() {
        val p = profiles()
        enabled = false
        p.show(Profile.Open, ready = true) { listOf(Placed("a.owl", 1, null)) }
        assertFalse(p.seeded)
        p.seed(emptyList())
        p.settle(listOf(widget("agent.owl", 2, 2, page = 3)))
        assertNull(p.open.page("agent.owl"))
    }

    @Test fun disabledForgetAndInheritStillTouchBothLayouts() {
        val p = profiles()
        enabled = false
        p.folded.set("app:a", Cell(1, 1, 1, 1))
        p.open.set("app:a", Cell(7, 1, 1, 1))
        p.inherit(from = "app:a", to = "folder:x")
        assertEquals(Cell(7, 1, 1, 1), p.open.cell("folder:x"))
        p.forget("folder:x")
        assertNull(p.folded.cell("folder:x"))
        assertNull(p.open.cell("folder:x"))
    }

    @Test fun seedingRescalesFromTheFoldedColumnsToTheOpenOnes() {
        val p = HomeProfiles(statePrefs, WidgetLayout(foldedPrefs, 9), WidgetLayout(openPrefs, 12, heal = true)) { true }
        p.seed(listOf(Placed("a.owl", 2, Cell(3, 0, 6, 2), minWidth = 5), Placed("b.owl", 1, Cell(0, 0, 3, 3))))
        assertEquals(Cell(7, 0, 5, 2), p.open.cell("a.owl"))
        assertEquals(Cell(0, 0, 2, 3), p.open.cell("b.owl"))
    }

    @Test fun anOldOpenLayoutAndItsHiddenItemsAreReadAsTheyAre() {
        // As the fold-layouts build saved them.
        openPrefs.edit().putString("layout", """{"v":1,"data":{"columns":12,"cells":{"a.owl":{"x":6,"y":0,"w":6,"h":2},"app:x":{"x":11,"y":3,"w":1,"h":1}},"pages":{"a.owl":1,"app:x":2}}}""").apply()
        statePrefs.edit().putString("state", """{"v":1,"data":{"hidden":{"folded":["h.owl"],"open":["h.owl","gone.owl"]},"seeded":true}}""").apply()
        val p = HomeProfiles(statePrefs, WidgetLayout(foldedPrefs), WidgetLayout(openPrefs, 8, heal = true)) { true }
        assertTrue(p.seeded)
        assertEquals(12, p.open.columns)
        assertEquals(Cell(6, 0, 6, 2), p.open.cell("a.owl"))
        assertEquals(2, p.open.page("app:x"))
        assertEquals(listOf("h.owl", "gone.owl"), p.hiddenKeys(Profile.Open))
        assertTrue(p.isHidden("h.owl", Profile.Folded))
        // Seeded already: unfolding doesn't pair the pages again.
        p.show(Profile.Open, ready = true) { listOf(Placed("a.owl", 3, Cell(0, 0, 2, 2))) }
        assertEquals(Cell(6, 0, 6, 2), p.open.cell("a.owl"))
        // home.unfolded-columns moves it onto its own grid.
        p.open.regrid(8, listOf(listOf(widget("a.owl", 6, 2))))
        assertEquals(Cell(4, 0, 4, 2), p.open.cell("a.owl"))
    }

    @Test fun hidingTwiceWhileThereIsOneHomeMarksItOnce() {
        val p = profiles()
        enabled = false
        p.hide("a.owl", Profile.Open)
        p.hide("a.owl", Profile.Open)
        enabled = true
        assertEquals(listOf("a.owl"), p.hiddenKeys(Profile.Open))
    }

    @Test fun unhidingDropsAStaleCellSoItsPagePlacesItAfresh() {
        val p = profiles()
        p.hide("a.owl", Profile.Open)
        // Turned off meanwhile, something gave it a cell there again, under what moved in.
        p.open.set("a.owl", Cell(0, 0, 4, 2))
        p.folded.set("a.owl", Cell(1, 1, 2, 2))
        p.unhide("a.owl", Profile.Open)
        assertFalse(p.isHidden("a.owl", Profile.Open))
        assertNull(p.open.cell("a.owl"))
        assertEquals(Cell(1, 1, 2, 2), p.folded.cell("a.owl"))
    }

    @Test fun unhidingWhatIsntHiddenKeepsItsCell() {
        val p = profiles()
        p.open.set("a.owl", Cell(0, 0, 4, 2))
        p.unhide("a.owl", Profile.Open)
        assertEquals(Cell(0, 0, 4, 2), p.open.cell("a.owl"))
    }

    @Test fun inheritingKeepsACellTheHeirHas() {
        val p = profiles()
        p.folded.set("folder:f", Cell(0, 0, 1, 1))
        p.folded.set("app:a", Cell(3, 3, 1, 1))
        p.open.set("folder:f", Cell(8, 0, 1, 1))
        p.inherit(from = "folder:f", to = "app:a")
        assertEquals(Cell(3, 3, 1, 1), p.folded.cell("app:a"))
        assertEquals(Cell(8, 0, 1, 1), p.open.cell("app:a"))
        assertNull(p.folded.cell("folder:f"))
    }
}
