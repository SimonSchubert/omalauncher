package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputTailTest {
    private fun tail(output: String) = OutputTail().also { t -> forEachOutputLine(output.reader()) { t.add(it) } }.toList()

    /** curl, pacman and mise redraw their progress with carriage returns; only the latest state stays. */
    @Test fun progressRedrawsInPlace() {
        assertEquals(
            listOf("downloading", "100% done", "installed"),
            tail("downloading\n 10%\r 50%\r100% done\ninstalled\n"),
        )
    }

    /** mise without a terminal, as it printed on the Pixel: a new frame every 3 s, each replacing the last. */
    @Test fun miseFramesReplaceEachOther() {
        val output =
            """
            mise by @jdx – installing 2 tools
            mise ░░░░░░░░░░░░░░░░ 0/2 · 3.0s
              jq@1.7.1  installing  3.0s
              fd@10.2.0  installing  3.0s
            mise █░░░░░░░░░░░░░░░ 0/2 · 6.0s
              jq@1.7.1  downloading  6.0s  0.2/1.7 MB · 154 kB/s
              fd@10.2.0  downloading  6.0s
            mise ✓ jq@1.7.1  7.4s  jq-linux-arm64
            mise ████████░░░░░░░░ 1/2 · 9.0s
              fd@10.2.0  downloading  9.0s  0.9/1.7 MB · 220 kB/s
            mise ✓ fd@10.2.0  10.1s
            mise ████████████████ 2/2 · installed 2 tools in 10.2s
            mise WARN  jq installed but not activated
            """.trimIndent() + "\n"
        assertEquals(
            listOf(
                "mise by @jdx – installing 2 tools",
                "mise ✓ jq@1.7.1  7.4s  jq-linux-arm64",
                "mise ✓ fd@10.2.0  10.1s",
                "mise ████████████████ 2/2 · installed 2 tools in 10.2s",
                "mise WARN  jq installed but not activated",
            ),
            tail(output),
        )
    }

    @Test fun framesSurviveTheTailDroppingLines() {
        val t = OutputTail(max = 3)
        listOf("a", "b", "c", "x ████ 1", "  tool", "d", "x ████ 2").forEach { t.add(it) }
        assertEquals(listOf("d", "x ████ 2"), t.toList())
    }

    @Test fun windowsLineEndsAreNewlines() {
        assertEquals(listOf("one", "two"), tail("one\r\ntwo\r\n"))
    }

    @Test fun blankLinesAndUnfinishedLastLine() {
        assertEquals(listOf("a", "b", "c"), tail("a\n\n\rb\n  \nc"))
    }
}
