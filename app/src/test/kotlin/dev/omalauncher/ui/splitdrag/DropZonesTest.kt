package dev.omalauncher.ui.splitdrag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DropZonesTest {
    // Galaxy Z Fold6: inner screen 2076x2152, outer 1080x2424.
    private val open = dropZones(2076f, 2152f, SplitPlatform.OneUi, null)
    private val folded = dropZones(1080f, 2424f, SplitPlatform.OneUi, null)

    @Test fun oneUiEdgesSplitAndTheMiddlePopsUp() {
        // t = round(2076 * 0.056) = 116
        assertEquals(ZoneKind.SplitLeft, open.zoneAt(50f, 1000f)?.kind)
        assertEquals(ZoneKind.SplitRight, open.zoneAt(2030f, 1000f)?.kind)
        assertEquals(ZoneKind.FullScreen, open.zoneAt(1000f, 50f)?.kind)
        assertEquals(ZoneKind.PopUp, open.zoneAt(200f, 1000f)?.kind)
        assertEquals(ZoneKind.PopUp, open.zoneAt(1038f, 1076f)?.kind)
    }

    @Test fun theThresholdIsTheEdge() {
        assertEquals(ZoneKind.SplitLeft, open.zoneAt(115.9f, 1000f)?.kind)
        assertEquals(ZoneKind.PopUp, open.zoneAt(116f, 1000f)?.kind)
    }

    @Test fun topCornersBelongToTheSides() {
        assertEquals(ZoneKind.SplitLeft, open.zoneAt(50f, 50f)?.kind)
        assertEquals(ZoneKind.SplitRight, open.zoneAt(2030f, 50f)?.kind)
    }

    @Test fun splitPreviewsAreTheHalves() {
        assertEquals(ZoneBox(0f, 0f, 1038f, 2152f), open.single { it.kind == ZoneKind.SplitLeft }.preview)
        assertEquals(ZoneBox(1038f, 0f, 2076f, 2152f), open.single { it.kind == ZoneKind.SplitRight }.preview)
        assertEquals(ZoneBox(0f, 0f, 2076f, 2152f), open.single { it.kind == ZoneKind.FullScreen }.preview)
    }

    @Test fun popUpIsCentered() {
        // shorter side 2076 * 0.6 = 1245.6 wide, longer side 2152 * 0.5 = 1076 high
        val p = open.single { it.kind == ZoneKind.PopUp }.preview
        assertEquals(1245.6f, p.right - p.left, 0.01f)
        assertEquals(1076f, p.bottom - p.top, 0.01f)
        assertEquals(1038f, (p.left + p.right) / 2, 0.01f)
        assertEquals(1076f, (p.top + p.bottom) / 2, 0.01f)
    }

    @Test fun foldedSidesSplitTopAndBottom() {
        // t = round(1080 * 0.056) = 60
        val left = folded.zoneAt(30f, 1200f)!!
        assertEquals(ZoneKind.SplitTop, left.kind)
        assertEquals("Split · top", left.label)
        assertEquals(ZoneBox(0f, 0f, 1080f, 1212f), left.preview)
        assertEquals(ZoneKind.SplitBottom, folded.zoneAt(1050f, 1200f)?.kind)
        assertEquals(ZoneBox(0f, 1212f, 1080f, 2424f), folded.single { it.kind == ZoneKind.SplitBottom }.preview)
    }

    @Test fun theNavigationBarsEdgeHasNoZone() {
        val zones = dropZones(2152f, 2076f, SplitPlatform.OneUi, NavEdge.Left)
        assertEquals(ZoneKind.PopUp, zones.zoneAt(30f, 1000f)?.kind)
        assertNull(zones.firstOrNull { it.kind == ZoneKind.SplitLeft })
        assertEquals(ZoneKind.SplitRight, zones.zoneAt(2120f, 1000f)?.kind)
    }

    @Test fun stockAndroidOnlyOpensFullScreen() {
        val zones = dropZones(2076f, 2152f, SplitPlatform.Stock(freeform = false), null)
        assertEquals(listOf(ZoneKind.FullScreen), zones.map { it.kind })
        assertEquals(ZoneKind.FullScreen, zones.zoneAt(10f, 10f)?.kind)
        assertEquals(ZoneKind.FullScreen, zones.zoneAt(1038f, 1076f)?.kind)
    }

    @Test fun stockWithFreeformPopsUpInTheMiddle() {
        val zones = dropZones(2076f, 2152f, SplitPlatform.Stock(freeform = true), null)
        assertEquals(ZoneKind.PopUp, zones.zoneAt(1038f, 1076f)?.kind)
        assertEquals(ZoneKind.FullScreen, zones.zoneAt(10f, 10f)?.kind)
    }
}
