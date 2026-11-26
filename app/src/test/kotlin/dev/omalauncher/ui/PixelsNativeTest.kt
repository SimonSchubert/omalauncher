package dev.omalauncher.ui

import dev.omalauncher.HostNative
import dev.omalauncher.widgets.render.FrostCache
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.Random

/** cpp/pixels.c against the Kotlin it's a port of: the same pixels. Skipped where there's no C compiler. */
class PixelsNativeTest {
    private val rnd = Random(4)

    /** A picture with smooth parts and noise, alpha included. */
    private fun picture(
        w: Int,
        h: Int,
    ) = IntArray(w * h) { i ->
        if (rnd.nextInt(3) == 0) rnd.nextInt() else (((i % w) * 255 / w) shl 16) or (((i / w) * 255 / h) shl 8) or (0xff shl 24) or (i and 0xff)
    }

    @Test fun sameBlur() {
        for ((w, h) in listOf(52 to 113, 1 to 40, 40 to 1, 1 to 1, 135 to 300)) {
            for (radius in listOf(1, 3, 8, 24)) {
                val kotlin = picture(w, h)
                val c = kotlin.copyOf()
                FrostCache.blur(kotlin, w, h, radius)
                assertTrue(PixelsNative.blur(c, w, h, radius))
                assertArrayEquals("$w x $h by $radius", kotlin, c)
            }
        }
    }

    @Test fun sameMask() {
        for (round in 0 until 50) {
            val size = 1 + rnd.nextInt(200)
            val kotlin =
                when (round % 5) {
                    // A black logo on nothing: left as it is.
                    0 -> IntArray(size * size) { if (rnd.nextBoolean()) 0xff000000.toInt() or rnd.nextInt(8) else 0 }
                    1 -> IntArray(size * size)
                    else -> picture(size, size)
                }
            val c = kotlin.copyOf()
            val changed = brightnessToAlpha(kotlin)
            assertEquals("round $round", if (changed) 1 else 0, PixelsNative.brightnessToAlpha(c, BLACK_LOGO))
            assertArrayEquals("round $round", kotlin, c)
        }
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun build() = HostNative.load("pixels.c")
    }
}
