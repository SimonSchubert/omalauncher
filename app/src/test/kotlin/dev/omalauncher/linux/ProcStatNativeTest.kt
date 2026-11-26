package dev.omalauncher.linux

import dev.omalauncher.HostNative
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** cpp/procstat.c against ProcShim's Kotlin, over a /proc of made-up processes. Skipped where there's no C compiler. */
class ProcStatNativeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun stat(
        proc: File,
        pid: String,
        text: String,
    ) = File(proc, pid).apply { mkdirs() }.resolve("stat").writeText(text)

    /** A stat line as the kernel writes it, utime and stime the 14th and 15th fields. */
    private fun line(
        name: String,
        utime: String,
        stime: String,
    ) = "1234 ($name) S 1 1234 1234 0 -1 4194560 1543 0 0 0 $utime $stime 0 0 20 0 1 0 391 9338880 891 18446744073709551615\n"

    @Test fun sameTicksAsKotlin() {
        val proc = tmp.newFolder("proc")
        stat(proc, "1", line("init", "120", "45"))
        stat(proc, "22", line("a) b (c", "7", "3"))
        stat(proc, "333", line("tab\tname", "1000000", "2000000"))
        stat(proc, "4444", line("x", "abc", "-5"))
        stat(proc, "55", "55 (short) S 1 2 3\n")
        stat(proc, "66", "")
        stat(proc, "77", "no name at all 1 2 3 4 5 6 7 8 9 10 11 12 13 14")
        stat(proc, "88", line("double  space", "9", "9").replace(" 0 -1", "  0 -1"))
        // Not processes.
        stat(proc, "self", line("self", "500", "500"))
        stat(proc, "12a", line("x", "500", "500"))
        File(proc, "99").mkdirs()
        val (user, system) = ProcShim.kotlinCpuTicks(proc)
        assertArrayEquals(longArrayOf(user, system), ProcStatNative.ticks(proc.path))
    }

    @Test fun noFolder() = assertNull(ProcStatNative.ticks(File(tmp.root, "none").path))

    companion object {
        @BeforeClass
        @JvmStatic
        fun build() = HostNative.load("procstat.c")
    }
}
