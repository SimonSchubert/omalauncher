package dev.omalauncher.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopPagesTest {
    @Test fun inheritedTilePagesShareTheDesktopWhileFacesStayFullWidth() {
        val pages = listOf(-1, 0, 1, 2, 3)
        val full: (Int) -> Boolean = { it <= 0 }
        assertEquals(listOf(listOf(-1), listOf(0), listOf(1, 2, 3)), desktopSlots(pages, 3, full))
        assertEquals(pages.map { listOf(it) }, desktopSlots(pages, 1, full))
    }
}
