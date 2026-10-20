package dev.omalauncher.widgets.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class WidgetFontsTest {
    /** A font's table directory with only an fvar table, holding [axes] as (tag, min, max). */
    private fun font(vararg axes: Triple<String, Int, Int>): ByteArray {
        val fvar = 12 + 16
        val b = ByteBuffer.allocate(fvar + 16 + 20 * axes.size)
        b.putInt(0x00010000).putShort(1).putShort(0).putShort(0).putShort(0)
        b.put("fvar".toByteArray()).putInt(0).putInt(fvar).putInt(16 + 20 * axes.size)
        // Version, the axes' offset from the table, a reserved field, then the axes' count and size.
        b.putShort(1).putShort(0).putShort(16).putShort(2).putShort(axes.size.toShort()).putShort(20).putShort(0).putShort(0)
        for ((tag, min, max) in axes) b.put(tag.toByteArray()).putInt(min shl 16).putInt(min shl 16).putInt(max shl 16).putShort(0).putShort(0)
        return b.array()
    }

    @Test fun readsTheWeightAxisOfAVariableFont() {
        assertEquals(100..900, weightAxis(font(Triple("wght", 100, 900))))
        assertEquals(200..700, weightAxis(font(Triple("wdth", 75, 100), Triple("wght", 200, 700))))
    }

    @Test fun aFontWithoutOneHasNone() {
        assertNull(weightAxis(font(Triple("wdth", 75, 100))))
        assertNull(weightAxis(font()))
        // No tables at all, and one cut short.
        assertNull(weightAxis(ByteBuffer.allocate(12).putInt(0x00010000).array()))
        assertNull(weightAxis(font(Triple("wght", 100, 900)).copyOf(40)))
    }
}
