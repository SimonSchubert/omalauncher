package dev.omalauncher.ui.splitdrag

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitDragSessionTest {
    private fun session() = SplitDragSession().apply { area = 1080f to 2424f }

    @Test fun followsTheFinger() {
        val s = session()
        s.begin("Maps", null, Offset(10f, 20f), canSplit = true, canPopUp = true, oneUi = true) {}
        assertEquals("Maps", s.label)
        assertEquals(Offset(10f, 20f), s.finger)
        s.move(Offset(30f, 40f))
        assertEquals(Offset(30f, 40f), s.finger)
    }

    @Test fun lettingGoOpensTheZoneUnderTheFinger() {
        val s = session()
        var dropped: DropZone? = null
        s.begin("Maps", null, Offset(540f, 2300f), canSplit = true, canPopUp = true, oneUi = true) { dropped = it }
        s.end(Offset(540f, 2000f))
        assertEquals(ZoneKind.SplitBottom, dropped?.kind)
        assertNull(s.label)
        assertNull(s.finger)
    }

    @Test fun endingWithoutAPointOpensNothing() {
        val s = session()
        var dropped: DropZone? = null
        s.begin("Maps", null, Offset(540f, 2300f), canSplit = true, canPopUp = true, oneUi = true) { dropped = it }
        s.end(null)
        assertNull(dropped)
        assertNull(s.label)
    }

    @Test fun stockAndroidSplitsAnUnfoldedScreenTopAndBottom() {
        val s = SplitDragSession().apply { area = 2076f to 2152f }
        s.begin("Maps", null, Offset(100f, 100f), canSplit = true, canPopUp = true, oneUi = false) {}
        assertEquals(listOf(ZoneKind.SplitTop, ZoneKind.SplitBottom), s.zones().map { it.kind }.filter { it != ZoneKind.FullScreen && it != ZoneKind.PopUp })
    }

    @Test fun zonesFollowWhatThePhoneCanDo() {
        val s = session()
        s.begin("Maps", null, Offset(540f, 2300f), canSplit = false, canPopUp = false, oneUi = true) {}
        assertEquals(listOf(ZoneKind.FullScreen, ZoneKind.FullScreen), s.zones().map { it.kind })
    }

    @Test fun aLayoutDecidesTheZones() {
        val s = session()
        var dropped: DropZone? = null
        val only = DropZone(ZoneKind.PopUp, "Pop-up", ZoneBox(0f, 0f, 1080f, 2424f), ZoneBox(0f, 0f, 10f, 10f))
        s.begin("Maps", null, Offset(5f, 5f), canSplit = true, canPopUp = true, oneUi = true, layout = { _, _ -> listOf(only) }) { dropped = it }
        assertEquals(listOf(only), s.zones())
        s.end(Offset(500f, 500f))
        assertEquals(only, dropped)
    }
}
