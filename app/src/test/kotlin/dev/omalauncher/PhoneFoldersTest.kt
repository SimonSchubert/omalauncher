package dev.omalauncher

import dev.omalauncher.ui.FilesFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PhoneFoldersTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun link(
        at: File,
        to: String,
    ) = Files.createSymbolicLink(at.toPath(), File(to).toPath()).toFile()

    private fun fifo(at: File) = at.also { assertEquals(0, ProcessBuilder("mkfifo", it.path).start().waitFor()) }

    @Test fun onlyRealFilesAndFoldersCopyOut() {
        val dir = tmp.newFolder("d")
        assertTrue(copiesOut(File(dir, "f").apply { writeText("x") }))
        assertTrue(copiesOut(File(dir, "sub").apply { mkdir() }))
        assertFalse("a link to a file", copiesOut(link(File(dir, "l"), "f")))
        assertFalse("a link to a folder, maybe its own", copiesOut(link(File(dir, "loop"), ".")))
        assertFalse("a guest path Android can't find", copiesOut(link(File(dir, "abs"), "/nope/nowhere")))
        assertFalse("a pipe never ends", copiesOut(fifo(File(dir, "p"))))
    }

    @Test fun aLinkAtTheDestinationGoesAndItsTargetStays() {
        val dir = tmp.newFolder("d")
        val launcherOwn = File(dir, "own.xml").apply { writeText("secret") }
        val planted = link(File(dir, "name"), launcherOwn.path)
        clearForCopy(planted)
        assertFalse(Files.exists(planted.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("secret", launcherOwn.readText())
    }

    @Test fun aBrokenLinkOrPipeAtTheDestinationGoes() {
        val dir = tmp.newFolder("d")
        val broken = link(File(dir, "b"), "/nope")
        val pipe = fifo(File(dir, "p"))
        clearForCopy(broken)
        clearForCopy(pipe)
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun realFilesAndFoldersAtTheDestinationStay() {
        val dir = tmp.newFolder("d")
        val file = File(dir, "f").apply { writeText("x") }
        val sub = File(dir, "s").apply { mkdir() }
        clearForCopy(file)
        clearForCopy(sub)
        clearForCopy(File(dir, "missing"))
        assertTrue(file.isFile && sub.isDirectory)
    }

    @Test fun aGrantedFolderOpensAsTheGrant() {
        val grants = listOf("Pictures" to "Pictures", "Download/X" to "X", "Pictures/Trips" to "Trips")
        assertEquals("phone:/Pictures", FilesFs.viaGrant("/sdcard/Pictures", grants))
        assertEquals("phone:/Pictures/Screenshots/a b", FilesFs.viaGrant("/sdcard/Pictures/Screenshots/a b", grants))
        assertEquals("the innermost grant", "phone:/Trips/2026", FilesFs.viaGrant("/sdcard/Pictures/Trips/2026", grants))
        assertEquals("phone:/X", FilesFs.viaGrant("/sdcard/Download/X", grants))
    }

    @Test fun otherPathsStayWhereTheyAre() {
        val grants = listOf("Pictures" to "Pictures", "Download/X" to "X", "" to "whole storage")
        for (path in listOf("/sdcard", "/sdcard/DCIM", "/sdcard/Picturesque", "/sdcard/Download", "/root/sdcard/Pictures", "phone:/Pictures")) {
            assertEquals(path, null, FilesFs.viaGrant(path, grants))
        }
    }

    @Test fun storagePathsOnlyForTheSharedStorage() {
        val own = "com.android.externalstorage.documents"
        assertEquals("Pictures", storagePathOf(own, "primary:Pictures"))
        assertEquals("Download/X", storagePathOf(own, "primary:Download/X"))
        assertEquals("a memory card", null, storagePathOf(own, "1234-ABCD:Pictures"))
        assertEquals("a cloud drive", null, storagePathOf("com.google.android.apps.docs.storage", "primary:Pictures"))
    }

    @Test fun pastedNamesDontClash() {
        assertEquals("a.txt", FilesFs.freeName(listOf("b.txt"), "a.txt"))
        assertEquals("a (copy).txt", FilesFs.freeName(listOf("a.txt"), "a.txt"))
        assertEquals("a (copy 2).txt", FilesFs.freeName(listOf("a.txt", "a (copy).txt"), "a.txt"))
        assertEquals("noext (copy)", FilesFs.freeName(listOf("noext"), "noext"))
        assertEquals(".hidden (copy)", FilesFs.freeName(listOf(".hidden"), ".hidden"))
    }
}
