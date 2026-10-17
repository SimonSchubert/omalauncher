package dev.omalauncher.ui.splitdrag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DropZonesTest {
    // Galaxy Z Fold6: inner screen 2076x2152, outer 1080x2424.
    private val open = dropZones(2076f, 2152f, canSplit = true, canPopUp = true, sideBySide = true)
    private val folded = dropZones(1080f, 2424f, canSplit = true, canPopUp = true, sideBySide = false)

    @Test fun unfoldedHalvesSplitSideBySide() {
        assertEquals(ZoneKind.SplitLeft, open.zoneAt(100f, 1000f)?.kind)
        assertEquals(ZoneKind.SplitRight, open.zoneAt(1900f, 1000f)?.kind)
        assertEquals(ZoneBox(0f, 0f, 1038f, 2152f), open.zoneAt(100f, 1000f)?.preview)
        assertEquals("Split · left", open.zoneAt(100f, 1000f)?.label)
    }

    @Test fun foldedHalvesSplitTopAndBottom() {
        assertEquals(ZoneKind.SplitTop, folded.zoneAt(540f, 600f)?.kind)
        assertEquals(ZoneKind.SplitBottom, folded.zoneAt(540f, 2000f)?.kind)
        assertEquals(ZoneBox(0f, 0f, 1080f, 1212f), folded.zoneAt(540f, 600f)?.preview)
        assertEquals(ZoneBox(0f, 1212f, 1080f, 2424f), folded.zoneAt(540f, 2000f)?.preview)
    }

    @Test fun theMiddlePopsUp() {
        // upright: 60% of 2076 = 1245.6 wide, 40% of 2152 = 860.8 high, centred
        val p = open.zoneAt(1038f, 1076f)!!
        assertEquals(ZoneKind.PopUp, p.kind)
        assertEquals(1245.6f, p.preview.right - p.preview.left, 0.01f)
        assertEquals(860.8f, p.preview.bottom - p.preview.top, 0.01f)
        assertEquals(p.trigger, p.preview)
        assertEquals(ZoneKind.PopUp, folded.zoneAt(540f, 1212f)?.kind)
    }

    @Test fun theTopBandOpensFullScreenOverTheHalves() {
        // 7% of 2152 = 150.64
        assertEquals(ZoneKind.FullScreen, open.zoneAt(1038f, 50f)?.kind)
        assertEquals(ZoneKind.FullScreen, open.zoneAt(100f, 150f)?.kind)
        assertEquals(ZoneKind.SplitLeft, open.zoneAt(100f, 151f)?.kind)
        assertEquals(ZoneBox(0f, 0f, 2076f, 2152f), open.zoneAt(1038f, 50f)?.preview)
    }

    @Test fun withoutSplitTheRestOpensFullScreen() {
        val zones = dropZones(2076f, 2152f, canSplit = false, canPopUp = true, sideBySide = true)
        assertEquals(ZoneKind.FullScreen, zones.zoneAt(100f, 1000f)?.kind)
        assertEquals(ZoneKind.PopUp, zones.zoneAt(1038f, 1076f)?.kind)
        assertNull(zones.firstOrNull { it.kind == ZoneKind.SplitLeft })
    }

    @Test fun withoutPopUpTheMiddleIsAHalf() {
        val zones = dropZones(2076f, 2152f, canSplit = true, canPopUp = false, sideBySide = true)
        assertEquals(ZoneKind.SplitLeft, zones.zoneAt(1000f, 1076f)?.kind)
        assertNull(zones.firstOrNull { it.kind == ZoneKind.PopUp })
    }

    @Test fun landscapePopUpIsWide() {
        val zones = dropZones(2400f, 1080f, canSplit = true, canPopUp = true, sideBySide = true)
        val p = zones.first { it.kind == ZoneKind.PopUp }.preview
        assertEquals(960f, p.right - p.left, 0.01f)
        assertEquals(648f, p.bottom - p.top, 0.01f)
    }

    @Test fun oneUiSplitsByShapeAndStockByOrientation() {
        assertEquals(true, splitsSideBySide(2076f, 2152f, oneUi = true))
        assertEquals(false, splitsSideBySide(2076f, 2152f, oneUi = false))
        assertEquals(false, splitsSideBySide(1080f, 2424f, oneUi = true))
        assertEquals(true, splitsSideBySide(2400f, 1080f, oneUi = false))
    }

    @Test fun outsideTheScreenIsNoZone() {
        assertNull(open.zoneAt(-5f, 1000f))
        assertNull(open.zoneAt(1000f, 2200f))
    }
}
