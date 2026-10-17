package dev.omalauncher.taskbar

import dev.omalauncher.ui.splitdrag.DropZone
import dev.omalauncher.ui.splitdrag.OpenPlan
import dev.omalauncher.ui.splitdrag.ZoneBox
import dev.omalauncher.ui.splitdrag.ZoneKind
import dev.omalauncher.ui.splitdrag.zoneAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskbarZonesTest {
    private val label: (String) -> String = { it.replaceFirstChar(Char::uppercase) }
    private val split = ScreenState.Split("maps", "mail", sideBySide = true)

    @Test fun overOneAppItSplitsWithIt() {
        val zones = taskbarZones(ScreenState.Single("maps"), "notes", 1856f, 2160f, canPopUp = true, oneUi = true, labelOf = label)
        assertEquals(ZoneKind.SplitLeft, zones.zoneAt(100f, 1000f)?.kind)
        assertEquals(ZoneKind.SplitRight, zones.zoneAt(1700f, 1000f)?.kind)
    }

    @Test fun overItselfThereIsNoSplit() {
        val zones = taskbarZones(ScreenState.Single("maps"), "maps", 1856f, 2160f, canPopUp = true, oneUi = true, labelOf = label)
        assertNull(zones.firstOrNull { it.kind == ZoneKind.SplitLeft })
    }

    @Test fun inSplitEachHalfReplacesItsApp() {
        val zones = taskbarZones(split, "notes", 1856f, 2160f, canPopUp = true, oneUi = true, labelOf = label)
        val left = zones.zoneAt(100f, 1000f)!!
        assertEquals(ZoneKind.SplitLeft, left.kind)
        assertEquals("Replace Maps", left.label)
        assertEquals(ZoneBox(0f, 0f, 928f, 2160f), left.preview)
        assertEquals("Replace Mail", zones.zoneAt(1700f, 1000f)?.label)
        // Only the halves over a split: no full screen, no pop-up (One UI would put either into a half).
        assertEquals(setOf(ZoneKind.SplitLeft, ZoneKind.SplitRight), zones.map { it.kind }.toSet())
        assertEquals(ZoneKind.SplitLeft, zones.zoneAt(900f, 50f)?.kind)
    }

    @Test fun foldedSplitReplacesTopAndBottom() {
        val folded = ScreenState.Split("maps", "mail", sideBySide = false)
        val zones = taskbarZones(folded, "notes", 968f, 2376f, canPopUp = false, oneUi = true, labelOf = label)
        assertEquals(ZoneKind.SplitTop, zones.zoneAt(400f, 600f)?.kind)
        assertEquals("Replace Mail", zones.zoneAt(400f, 2000f)?.label)
    }

    @Test fun noZoneReplacesTheDraggedAppWithItself() {
        val zones = taskbarZones(split, "maps", 1856f, 2160f, canPopUp = true, oneUi = true, labelOf = label)
        val splitKinds = setOf(ZoneKind.SplitLeft, ZoneKind.SplitRight, ZoneKind.SplitTop, ZoneKind.SplitBottom)
        assertNull(zones.firstOrNull { it.kind in splitKinds })
        assertTrue(zones.isEmpty())
    }

    @Test fun unknownOffersFullScreenAndPopUpOnly() {
        val zones = taskbarZones(ScreenState.Unknown, "notes", 1856f, 2160f, canPopUp = true, oneUi = true, labelOf = label)
        assertEquals(setOf(ZoneKind.FullScreen, ZoneKind.PopUp), zones.map { it.kind }.toSet())
    }

    @Test fun overASplitNothingIsPlanned() {
        val zone = taskbarZones(split, "notes", 1856f, 2160f, true, true, label).first { it.kind == ZoneKind.SplitLeft }
        assertNull(planTaskbarOpen(zone, split, "notes") { it })
    }

    @Test fun aPartnerThatCantBeRelaunchedOffersNoSplit() {
        val zones = taskbarZones(ScreenState.Single("maps"), "notes", 1856f, 2160f, true, true, label, canPartner = { false })
        assertEquals(setOf(ZoneKind.FullScreen, ZoneKind.PopUp), zones.map { it.kind }.toSet())
    }

    @Test fun overOneAppTheSidesFollowTheHomeRule() {
        val single = ScreenState.Single("maps")
        val zones = taskbarZones(single, "notes", 1856f, 2160f, true, true, label)
        assertEquals(OpenPlan.Split("notes", "maps"), planTaskbarOpen(zones.first { it.kind == ZoneKind.SplitLeft }, single, "notes") { it })
        assertEquals(OpenPlan.Split("maps", "notes"), planTaskbarOpen(zones.first { it.kind == ZoneKind.SplitRight }, single, "notes") { it })
    }

    @Test fun anAppThatCantBeFoundOpensNothing() {
        val single = ScreenState.Single("maps")
        val zone = taskbarZones(single, "notes", 1856f, 2160f, true, true, label).first { it.kind == ZoneKind.SplitLeft }
        assertNull(planTaskbarOpen<String>(zone, single, "notes") { null })
    }

    private val linux = ScreenState.Single(LinuxWindow("s-1", "Terminal").id)

    @Test fun overALinuxWindowItSplitsWithIt() {
        val zones = taskbarZones(linux, "notes", 1856f, 2160f, true, true, label)
        assertEquals(setOf(ZoneKind.SplitLeft, ZoneKind.SplitRight), zones.map { it.kind }.filter { it in splitKinds }.toSet())
    }

    @Test fun inASplitWithALinuxWindowItsHalfIsReplacedByItsName() {
        val terminal = LinuxWindow("s-1", "Terminal")
        val zones = taskbarZones(ScreenState.Split("maps", terminal.id, sideBySide = true), "notes", 1856f, 2160f, true, true, labelOf = { if (it == terminal.id) terminal.label else label(it) })
        assertEquals("Replace Terminal", zones.zoneAt(1700f, 1000f)?.label)
        assertEquals("Replace Maps", zones.zoneAt(100f, 1000f)?.label)
    }

    @Test fun rightOfALinuxWindowItComesForwardThenTheAppNextToIt() {
        assertEquals(
            listOf(TrampolineStep.Front(7, 0), TrampolineStep.Open("notes", adjacent = true, delayMs = 450)),
            nextToWindow(ZoneKind.SplitRight),
        )
        assertEquals(nextToWindow(ZoneKind.SplitRight), nextToWindow(ZoneKind.SplitBottom))
    }

    @Test fun leftOfALinuxWindowTheAppOpensThenTheWindowMovesNextToIt() {
        assertEquals(
            listOf(
                TrampolineStep.Open("notes", adjacent = false, delayMs = 0),
                TrampolineStep.Start("window", adjacent = true, delayMs = 450),
                TrampolineStep.Remove(7, HANDOVER_DELAY_MS),
            ),
            nextToWindow(ZoneKind.SplitLeft),
        )
        assertEquals(nextToWindow(ZoneKind.SplitLeft), nextToWindow(ZoneKind.SplitTop))
    }

    /** An app dragged into [kind] next to the Linux window in task 7. */
    private fun nextToWindow(kind: ZoneKind) = splitHalves<String, String>(kind, SplitHalf.App("notes"), SplitHalf.Task(7, "window"))

    @Test fun overALinuxWindowFullScreenStillOpensAsTapped() {
        val zone = taskbarZones(linux, "notes", 1856f, 2160f, true, true, label).first { it.kind == ZoneKind.FullScreen }
        assertEquals(OpenPlan.AsTapped("notes"), planTaskbarOpen(zone, linux, "notes") { null })
    }

    @Test fun aNewLinuxWindowDraggedLeftOpensThenThePartnerNextToIt() {
        assertEquals(
            listOf(TrampolineStep.Start("new", adjacent = false, delayMs = 0), TrampolineStep.Open("maps", adjacent = true, delayMs = 450)),
            splitHalves(ZoneKind.SplitLeft, SplitHalf.Fresh("new"), SplitHalf.App("maps")),
        )
    }

    @Test fun aNewLinuxWindowDraggedRightOpensNextToThePartner() {
        assertEquals(
            listOf(TrampolineStep.Open("maps", adjacent = false, delayMs = 0), TrampolineStep.Start("new", adjacent = true, delayMs = 450)),
            splitHalves(ZoneKind.SplitBottom, SplitHalf.Fresh("new"), SplitHalf.App("maps")),
        )
    }

    @Test fun aLinuxWindowDraggedLeftComesForwardThenThePartnerNextToIt() {
        assertEquals(
            listOf(TrampolineStep.Front(3, 0), TrampolineStep.Open("maps", adjacent = true, delayMs = 450)),
            splitHalves(ZoneKind.SplitTop, SplitHalf.Task(3, "window"), SplitHalf.App("maps")),
        )
    }

    @Test fun aLinuxWindowDraggedRightGetsANewActivityNextToThePartnerAndItsOldTaskGoes() {
        assertEquals(
            listOf(
                TrampolineStep.Open("maps", adjacent = false, delayMs = 0),
                TrampolineStep.Start("window", adjacent = true, delayMs = 450),
                TrampolineStep.Remove(3, HANDOVER_DELAY_MS),
            ),
            splitHalves(ZoneKind.SplitRight, SplitHalf.Task(3, "window"), SplitHalf.App("maps")),
        )
    }

    @Test fun twoLinuxWindowsSplitByBringingOneForwardAndMovingTheOther() {
        assertEquals(
            listOf(
                TrampolineStep.Front(7, 0),
                TrampolineStep.Start("dragged", adjacent = true, delayMs = 450),
                TrampolineStep.Remove(3, HANDOVER_DELAY_MS),
            ),
            splitHalves<String, String>(ZoneKind.SplitRight, SplitHalf.Task(3, "dragged"), SplitHalf.Task(7, "partner")),
        )
        assertEquals(
            listOf(
                TrampolineStep.Start("new", adjacent = false, delayMs = 0),
                TrampolineStep.Start("partner", adjacent = true, delayMs = 450),
                TrampolineStep.Remove(7, HANDOVER_DELAY_MS),
            ),
            splitHalves<String, String>(ZoneKind.SplitLeft, SplitHalf.Fresh("new"), SplitHalf.Task(7, "partner")),
        )
    }

    @Test fun fullScreenAndPopUpNeedNoHalves() {
        assertNull(splitHalves(ZoneKind.FullScreen, SplitHalf.Fresh("new"), SplitHalf.App("maps")))
        assertNull(splitHalves(ZoneKind.PopUp, SplitHalf.Fresh("new"), SplitHalf.App("maps")))
    }

    @Test fun aLinuxAppsOwnWindowOnScreenOffersNoSplitWithItself() {
        val terminal = LinuxWindow("s-1", "Terminal")
        val zones = taskbarZones(ScreenState.Single(terminal.id), terminal.id, 1856f, 2160f, true, true, label)
        assertTrue(zones.none { it.kind in splitKinds })
        assertTrue(taskbarZones(ScreenState.Single(terminal.id), linuxKey("foot"), 1856f, 2160f, true, true, label).any { it.kind in splitKinds })
    }

    private val splitKinds = setOf(ZoneKind.SplitLeft, ZoneKind.SplitRight, ZoneKind.SplitTop, ZoneKind.SplitBottom)
}
