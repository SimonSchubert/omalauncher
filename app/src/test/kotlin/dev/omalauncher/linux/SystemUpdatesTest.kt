package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SystemUpdatesTest {
    /** A check killed mid-sync leaves its lock; the next one clears it rather than failing on it until Linux restarts (#322). */
    @Test fun checkClearsALockNoPacmanHolds() {
        val dir = createTempDirectory("updates").toFile()
        val t = File(dir, "omalauncher-updates")
        val bin = File(dir, "bin").apply { mkdirs() }
        // pacman as it behaves: -Sy fails on a lock, otherwise takes and drops it.
        File(bin, "pacman").apply {
            writeText(
                """
                #!/bin/sh
                case "${'$'}1" in
                  -Sy) [ -e "$t/db.lck" ] && exit 1; exit 0 ;;
                  -Qu) echo 'bash 5.3-1 -> 5.3-2' ;;
                esac
                """.trimIndent(),
            )
            setExecutable(true)
        }
        val pgrep = File(bin, "pgrep").apply { writeText(""); setExecutable(true) }
        fun check(): String {
            val script = SystemUpdates.CHECK_SCRIPT.replace("/tmp/omalauncher-updates", t.path)
            val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true)
            process.environment()["PATH"] = "${bin.path}:${System.getenv("PATH")}"
            return process.start().inputStream.bufferedReader().use { it.readText() }
        }

        t.mkdirs()
        File(t, "db.lck").writeText("")
        pgrep.writeText("#!/bin/sh\nexit 1\n")
        assertTrue(check().startsWith("#pacman\nbash 5.3-1 -> 5.3-2\n"))
        assertTrue("stale lock left", !File(t, "db.lck").exists())

        // A pacman that's running may hold it: that lock stays.
        File(t, "db.lck").writeText("")
        pgrep.writeText("#!/bin/sh\nexit 0\n")
        assertEquals("#pacman-error\n#mise\n", check().substringBefore("{"))
        assertTrue("a held lock went", File(t, "db.lck").exists())
        dir.deleteRecursively()
    }
}
