package dev.omalauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class StripPinsTest {
    @Test fun keepsPinOrder() {
        val pins = listOf("a:com.example/.Main", "l:btop", "a:org.other/.Start")
        assertEquals(pins, StripPins.decode(StripPins.encode(pins)))
    }

    @Test fun nothingSavedIsNoPins() {
        assertEquals(emptyList<String>(), StripPins.decode(null))
        assertEquals(emptyList<String>(), StripPins.decode(""))
        assertEquals(emptyList<String>(), StripPins.decode("not json"))
    }

    @Test fun dropsBlanksAndDuplicates() {
        assertEquals(listOf("l:a", "l:b"), StripPins.decode("""{"v":1,"data":["l:a","","l:b","l:a"]}"""))
    }

    @Test fun cellsGrowToFillUpToTheMax() {
        assertEquals(56f, stripCellSize(340f, 3))
        assertEquals(56f, stripCellSize(340f, 0))
    }

    @Test fun cellsShrinkToFitThenScroll() {
        assertEquals(34f, stripCellSize(340f, 10))
        assertEquals(32f, stripCellSize(340f, 20))
    }
}
