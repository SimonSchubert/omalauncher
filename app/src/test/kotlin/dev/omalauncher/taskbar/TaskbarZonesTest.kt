package dev.omalauncher.taskbar

import dev.omalauncher.ui.splitdrag.OpenPlan
import dev.omalauncher.ui.splitdrag.ZoneBox
import dev.omalauncher.ui.splitdrag.ZoneKind
import dev.omalauncher.ui.splitdrag.zoneAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(ZoneKind.FullScreen, zones.zoneAt(900f, 50f)?.kind)
        assertEquals(ZoneKind.PopUp, zones.zoneAt(928f, 1080f)?.kind)
    }

    @Test fun foldedSplitReplacesTopAndBottom() {
        val folded = ScreenState.Split("maps", "mail", sideBySide = false)
        val zones = taskbarZones(folded, "notes", 968f, 2376f, canPopUp = false, oneUi = true, labelOf = label)
        assertEquals(ZoneKind.SplitTop, zones.zoneAt(400f, 600f)?.kind)
        assertEquals("Replace Mail", zones.zoneAt(400f, 2000f)?.label)
    }

    @Test fun noZoneReplacesTheDraggedAppWithItself() {
        val zones = taskbarZones(split, "maps", 1856f, 2160f, canPopUp = true, oneUi = true, labelOf = label)
        assertNull(zones.firstOrNull { it.kind == ZoneKind.SplitLeft })
        assertEquals("Replace Mail", zones.first { it.kind == ZoneKind.SplitRight }.label)
    }

    @Test fun unknownOffersFullScreenAndPopUpOnly() {
        val zones = taskbarZones(ScreenState.Unknown, "notes", 1856f, 2160f, canPopUp = true, oneUi = true, labelOf = label)
        assertEquals(setOf(ZoneKind.FullScreen, ZoneKind.PopUp), zones.map { it.kind }.toSet())
    }

    @Test fun replacingTheLeftBringsTheRightForwardFirst() {
        val zone = taskbarZones(split, "notes", 1856f, 2160f, true, true, label).first { it.kind == ZoneKind.SplitLeft }
        assertEquals(OpenPlan.Split("mail", "notes"), planTaskbarOpen(zone, split, "notes") { it })
    }

    @Test fun replacingTheRightBringsTheLeftForwardFirst() {
        val zone = taskbarZones(split, "notes", 1856f, 2160f, true, true, label).first { it.kind == ZoneKind.SplitRight }
        assertEquals(OpenPlan.Split("maps", "notes"), planTaskbarOpen(zone, split, "notes") { it })
    }

    @Test fun overOneAppTheSidesFollowTheHomeRule() {
        val single = ScreenState.Single("maps")
        val zones = taskbarZones(single, "notes", 1856f, 2160f, true, true, label)
        assertEquals(OpenPlan.Split("notes", "maps"), planTaskbarOpen(zones.first { it.kind == ZoneKind.SplitLeft }, single, "notes") { it })
        assertEquals(OpenPlan.Split("maps", "notes"), planTaskbarOpen(zones.first { it.kind == ZoneKind.SplitRight }, single, "notes") { it })
    }

    @Test fun anAppThatCantBeFoundOpensNothing() {
        val zone = taskbarZones(split, "notes", 1856f, 2160f, true, true, label).first { it.kind == ZoneKind.SplitLeft }
        assertNull(planTaskbarOpen<String>(zone, split, "notes") { null })
    }
}
