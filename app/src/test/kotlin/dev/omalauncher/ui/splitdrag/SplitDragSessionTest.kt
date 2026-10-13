package dev.omalauncher.ui.splitdrag

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitDragSessionTest {
    private fun session() = SplitDragSession().apply { area = 1080f to 2424f }

    @Test fun followsTheFinger() {
        val s = session()
        s.begin("Maps", null, Offset(10f, 20f), canSplit = true, canPopUp = true) {}
        assertEquals("Maps", s.label)
        assertEquals(Offset(10f, 20f), s.finger)
        s.move(Offset(30f, 40f))
        assertEquals(Offset(30f, 40f), s.finger)
    }

    @Test fun lettingGoOpensTheZoneUnderTheFinger() {
        val s = session()
        var dropped: DropZone? = null
        s.begin("Maps", null, Offset(540f, 2300f), canSplit = true, canPopUp = true) { dropped = it }
        s.end(Offset(540f, 2000f))
        assertEquals(ZoneKind.SplitBottom, dropped?.kind)
        assertNull(s.label)
        assertNull(s.finger)
    }

    @Test fun endingWithoutAPointOpensNothing() {
        val s = session()
        var dropped: DropZone? = null
        s.begin("Maps", null, Offset(540f, 2300f), canSplit = true, canPopUp = true) { dropped = it }
        s.end(null)
        assertNull(dropped)
        assertNull(s.label)
    }

    @Test fun zonesFollowWhatThePhoneCanDo() {
        val s = session()
        s.begin("Maps", null, Offset(540f, 2300f), canSplit = false, canPopUp = false) {}
        assertEquals(listOf(ZoneKind.FullScreen, ZoneKind.FullScreen), s.zones().map { it.kind })
    }
}
