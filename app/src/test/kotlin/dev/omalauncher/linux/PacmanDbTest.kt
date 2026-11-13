package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PacmanDbTest {
    @get:Rule val dir = TemporaryFolder()

    private val local by lazy { dir.newFolder("local") }

    private fun entry(
        name: String,
        vararg files: String,
    ) = File(local, name).also { e ->
        e.mkdirs()
        files.forEach { File(e, it).writeText("x") }
    }

    @Test
    fun dropsAnEntryAKilledInstallLeftWithOnlyMtree() {
        val npm = entry("npm-12.2.1-1", "mtree")
        val node = entry("nodejs-26.13.0-1", "desc", "files", "mtree")

        assertEquals(listOf("npm-12.2.1-1"), Pacman.dropUnfinished(local))
        assertFalse(npm.exists())
        assertTrue(File(node, "desc").isFile)
    }

    @Test
    fun dropsAnEmptyEntry() {
        entry("glibc-2.42-1")

        assertEquals(listOf("glibc-2.42-1"), Pacman.dropUnfinished(local))
    }

    @Test
    fun keepsCompleteEntriesAndTheAlpmDbVersionFile() {
        entry("bash-5.3-1", "desc", "files", "mtree")
        // pacman's own marker of the database format sits beside the entries.
        File(local, "ALPM_DB_VERSION").writeText("9")

        assertEquals(emptyList<String>(), Pacman.dropUnfinished(local))
        assertTrue(File(local, "bash-5.3-1/desc").isFile)
        assertTrue(File(local, "ALPM_DB_VERSION").isFile)
    }

    @Test
    fun noDatabaseYet() {
        assertEquals(emptyList<String>(), Pacman.dropUnfinished(File(dir.root, "missing")))
    }
}
