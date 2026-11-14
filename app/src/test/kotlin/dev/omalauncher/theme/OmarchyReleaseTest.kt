package dev.omalauncher.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Collections

class OmarchyReleaseTest {
    @get:Rule val tmp = TemporaryFolder()

    private val repo =
        mapOf(
            "themes/nord/colors.toml" to "background = \"#2e3440\"",
            "themes/nord/backgrounds/1.jpg" to "jpeg",
            "config/btop/btop.conf" to "theme = nord",
        )
    private val release = ReleaseFiles("abc", repo.map { (path, text) -> ReleaseFile(path, text.length.toLong()) })
    private val fetched = Collections.synchronizedList(mutableListOf<String>())
    private val dir by lazy { File(tmp.root, "omarchy") }

    private fun release(failOn: String? = null) =
        OmarchyRelease(dir) { path ->
            if (path == failOn) throw IOException("offline")
            fetched += path
            repo.getValue(path).toByteArray()
        }

    private fun download(r: OmarchyRelease = release()) = r.download(release, "nord", File(tmp.root, "user")) {}

    @Test fun parsesTheBundledList() {
        val files = ReleaseFiles.parse("# comment\nref abc\n12 themes/nord/colors.toml\n0 config/lazygit/config.yml\n")
        assertEquals("abc", files.ref)
        assertEquals(listOf(ReleaseFile("themes/nord/colors.toml", 12), ReleaseFile("config/lazygit/config.yml", 0)), files.files)
    }

    @Test fun bundledListIsForThePinnedCommit() {
        val files = ReleaseFiles.parse(File("src/main/assets/${ThemeStore.RELEASE_FILES}").readText())
        assertEquals(ThemeStore.OMARCHY_REF, files.ref)
        assertTrue(files.files.any { it.path.endsWith("/colors.toml") })
        assertFalse(files.files.any { it.path.startsWith("/") || ".." in it.path })
    }

    @Test fun downloadsEveryFileAndMarksTheRelease() {
        download()
        repo.forEach { (path, text) -> assertEquals(text, File(dir, path).readText()) }
        assertEquals("abc", File(dir, ".omalauncher-ref").readText().trim())
        assertFalse(File(tmp.root, ".omarchy.download").exists())
    }

    @Test fun anInterruptedDownloadKeepsTheOldReleaseAndResumes() {
        File(dir, "themes/old/colors.toml").apply { parentFile.mkdirs() }.writeText("old")
        try {
            download(release(failOn = "config/btop/btop.conf"))
            fail()
        } catch (e: IOException) {
            assertEquals("offline", e.message)
        }
        assertEquals("old", File(dir, "themes/old/colors.toml").readText())
        fetched.clear()
        download()
        assertEquals(listOf("config/btop/btop.conf"), fetched.toList())
        assertFalse(File(dir, "themes/old").exists())
    }

    @Test fun startsOverForAnotherCommitOrAWrongSize() {
        val staging = File(tmp.root, ".omarchy.download")
        File(staging, "themes/nord/colors.toml").apply { parentFile.mkdirs() }.writeText("x".repeat(repo.getValue("themes/nord/colors.toml").length))
        File(staging, "stale.txt").writeText("from a tarball")
        download()
        assertEquals(repo.getValue("themes/nord/colors.toml"), File(dir, "themes/nord/colors.toml").readText())
        assertFalse(File(dir, "stale.txt").exists())
    }

    @Test fun rejectsAFileOfTheWrongSize() {
        val r = OmarchyRelease(dir) { "short".toByteArray() }
        try {
            download(r)
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("expected"))
        }
        assertFalse(dir.exists())
    }
}
