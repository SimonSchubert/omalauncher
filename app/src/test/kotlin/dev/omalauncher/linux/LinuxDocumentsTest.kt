package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

class LinuxDocumentsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun docs() = LinuxDocuments(tmp.newFolder("home"))

    @Test fun rootAndUnusualFilenamesRoundTrip() {
        val docs = docs()
        assertEquals(docs.home, docs.fileOf("home"))
        for (name in listOf("root", "héllo wörld", "notes #1 (2026)", "a+b", "a%2Fb", "%2E%2E", "emoji 🐛", "tab\tname", "%100%", " padded ", "back\\slash")) {
            File(docs.home, name).createNewFile()
            val id = LinuxDocuments.childId("home", name)
            assertEquals(name, docs.fileOf(id).name)
            assertTrue(docs.isChild("home", id))
        }
    }

    @Test fun rejectsMalformedAndTraversingIds() {
        val docs = docs()
        File(docs.home, "folder").mkdir()
        for (id in listOf("", "root", "/home", "home/", "home//folder", "home/.", "home/..", "home/folder/../folder", "home/../../outside", "home/\u0000", "home/.l2s.backing")) {
            assertThrows(id, FileNotFoundException::class.java) { docs.fileOf(id) }
            assertFalse(id, docs.isChild("home", id))
        }
    }

    @Test fun listingsSkipBrokenEscapingAndProotLinksButKeepDotfiles() {
        val docs = docs()
        File(docs.home, "real").createNewFile()
        File(docs.home, ".ssh").mkdir()
        File(docs.home, ".config").mkdir()
        File(docs.home, ".l2s.backing").createNewFile()
        Files.createSymbolicLink(File(docs.home, "broken").toPath(), File(docs.home, "missing").toPath())
        Files.createSymbolicLink(File(docs.home, "escape").toPath(), tmp.newFile("secret").toPath())
        Files.createSymbolicLink(File(docs.home, "inside").toPath(), File(docs.home, "real").toPath())
        assertEquals(listOf(".config", ".ssh", "inside", "real"), docs.children("home").map { it.name })
    }

    @Test fun escapedLinksCannotBeQueriedListedOrMutated() {
        val docs = docs()
        val outside = tmp.newFolder("home-sibling")
        File(outside, "secret").writeText("untouched")
        Files.createSymbolicLink(File(docs.home, "escape").toPath(), outside.toPath())
        assertThrows(FileNotFoundException::class.java) { docs.fileOf("home/escape") }
        assertThrows(FileNotFoundException::class.java) { docs.children("home/escape") }
        assertThrows(FileNotFoundException::class.java) { docs.create("home/escape", "text/plain", "new") }
        assertThrows(FileNotFoundException::class.java) { docs.rename("home/escape", "renamed") }
        assertThrows(FileNotFoundException::class.java) { docs.delete("home/escape") }
        assertEquals("untouched", File(outside, "secret").readText())
        assertEquals(listOf("secret"), outside.list()!!.toList())
    }

    @Test fun treeGrantsRequireRealDescendantsNotJustIdPrefixes() {
        val docs = docs()
        val folder = File(docs.home, "folder").apply { mkdir() }
        File(folder, "nested").mkdir()
        File(folder, "nested/file").createNewFile()
        val sibling = File(docs.home, "folder-sibling").apply { mkdir() }
        File(sibling, "secret").createNewFile()
        Files.createSymbolicLink(File(folder, "escape").toPath(), sibling.toPath())
        Files.createSymbolicLink(File(folder, "back").toPath(), docs.home.toPath())
        assertTrue(docs.isChild("home", "home/folder"))
        assertTrue(docs.isChild("home/folder", "home/folder/nested/file"))
        assertFalse(docs.isChild("home/folder", "home/folder"))
        assertFalse(docs.isChild("home/folder", "home/folder-sibling"))
        assertFalse(docs.isChild("home/folder", "home/folder/escape/secret"))
        assertFalse(docs.isChild("home/folder", "home/folder/back"))
        assertFalse(docs.isChild("home/folder", "home/folder/../folder-sibling/secret"))
        assertFalse(docs.isChild("home/folder", "home/folder/missing"))
        assertFalse(docs.isChild("home/folder/nested/file", "home/folder/nested/file/anything"))
        assertEquals(listOf("nested"), docs.children("home/folder").map { it.name })
    }

    @Test fun renamingAndDeletingLinksLeaveTheirTargetsAlone() {
        val docs = docs()
        val target = File(docs.home, "target").apply { mkdir() }
        File(target, "keep").writeText("keep me")
        Files.createSymbolicLink(File(docs.home, "link").toPath(), target.toPath())
        assertEquals("link", docs.fileOf("home/link").name)
        assertEquals("home/new link", docs.rename("home/link", "new link"))
        assertTrue(Files.isSymbolicLink(File(docs.home, "new link").toPath()))
        docs.delete("home/new link")
        assertEquals("keep me", File(target, "keep").readText())
    }

    @Test fun recursiveDeleteUnlinksNestedSymlinksWithoutFollowingThem() {
        val docs = docs()
        val outside = tmp.newFolder("outside")
        File(outside, "secret").writeText("untouched")
        val target = File(docs.home, "keep").apply { mkdir() }
        File(target, "secret").writeText("also untouched")
        val dir = File(docs.home, "delete").apply { mkdir() }
        File(dir, "nested").mkdir()
        File(dir, "nested/file").createNewFile()
        Files.createSymbolicLink(File(dir, "external").toPath(), outside.toPath())
        Files.createSymbolicLink(File(dir, "internal").toPath(), target.toPath())
        Files.createSymbolicLink(File(dir, "loop").toPath(), dir.toPath())
        Files.createSymbolicLink(File(docs.home, "delete alias").toPath(), dir.toPath())
        val deleted = mutableListOf<String>()
        docs.delete("home/delete", deleted::add)
        assertFalse(dir.exists())
        assertEquals("untouched", File(outside, "secret").readText())
        assertEquals("also untouched", File(target, "secret").readText())
        assertTrue(deleted.containsAll(listOf("home/delete/nested/file", "home/delete/nested", "home/delete/external", "home/delete/internal", "home/delete/loop", "home/delete")))
        assertFalse(deleted.contains("home"))
        assertTrue(deleted.contains("home/keep/secret"))
        assertTrue(deleted.contains("home/delete alias/nested/file"))
    }

    @Test fun homeAndLinksToHomeCannotBeDeletedOrRenamed() {
        val docs = docs()
        Files.createSymbolicLink(File(docs.home, "back").toPath(), docs.home.toPath())
        for (id in listOf("home", "home/back")) {
            assertFalse(docs.canModify(id))
            assertThrows(FileNotFoundException::class.java) { docs.delete(id) }
            assertThrows(FileNotFoundException::class.java) { docs.rename(id, "away") }
        }
        assertTrue(docs.home.isDirectory)
    }

    @Test fun creatingAndRenamingNeverOverwriteExistingOrDanglingEntries() {
        val docs = docs()
        assertEquals("home/notes.txt", docs.create("home", "text/plain", "notes.txt"))
        File(docs.home, "notes.txt").writeText("keep")
        assertEquals("home/notes (1).txt", docs.create("home", "text/plain", "notes.txt"))
        Files.createSymbolicLink(File(docs.home, "broken.txt").toPath(), File(docs.home, "missing").toPath())
        assertEquals("home/broken (1).txt", docs.create("home", "text/plain", "broken.txt"))
        assertEquals("home/notes (2).txt", docs.rename("home/broken (1).txt", "notes.txt"))
        assertEquals("home/folder", docs.create("home", LinuxDocuments.DIRECTORY_MIME_TYPE, "folder"))
        assertEquals("home/renamed", docs.rename("home/folder", "renamed"))
        assertEquals("home/.config", docs.create("home", LinuxDocuments.DIRECTORY_MIME_TYPE, ".config"))
        assertEquals("home/.config (1)", docs.create("home", LinuxDocuments.DIRECTORY_MIME_TYPE, ".config"))
        assertEquals("keep", File(docs.home, "notes.txt").readText())
        assertTrue(Files.isSymbolicLink(File(docs.home, "broken.txt").toPath()))
    }

    @Test fun unchangedRenameDoesNotReturnAReplacementId() {
        val docs = docs()
        val id = docs.create("home", "text/plain", "same.txt")
        docs.fileOf(id).writeText("kept")
        // DocumentsProvider revokes the old grant for every non-null rename result.
        assertNull(docs.rename(id, "same.txt"))
        assertNull(docs.rename(id, " same.txt "))
        assertEquals("kept", docs.fileOf(id).readText())
        assertEquals(listOf("same.txt"), docs.children("home").map { it.name })
    }

    @Test fun renameInvalidatesOldDescendantsAndTheirAliasesOnlyAfterSuccess() {
        val docs = docs()
        val folder = File(docs.home, "folder").apply { mkdir() }
        File(folder, "nested").mkdir()
        File(folder, "nested/note").writeText("private")
        Files.createSymbolicLink(File(docs.home, "alias").toPath(), folder.toPath())
        File(docs.home, "unrelated").writeText("keep")
        val invalidated = mutableListOf<String>()
        assertNull(docs.rename("home/folder", "folder", invalidated::add))
        assertTrue(invalidated.isEmpty())
        val tooLong = "x".repeat(300)
        assertThrows(java.io.IOException::class.java) { docs.rename("home/folder", tooLong, invalidated::add) }
        assertTrue(invalidated.isEmpty())
        assertEquals("home/archive", docs.rename("home/folder", "archive", invalidated::add))
        assertTrue(invalidated.containsAll(listOf("home/folder/nested", "home/folder/nested/note", "home/alias", "home/alias/nested", "home/alias/nested/note")))
        assertFalse(invalidated.contains("home/folder"))
        assertFalse(invalidated.contains("home/unrelated"))
        assertFalse(invalidated.contains("home"))
        assertEquals("private", docs.fileOf("home/archive/nested/note").readText())
    }

    @Test fun missingFilesAndNonDirectoryParentsAreRefused() {
        val docs = docs()
        File(docs.home, "file").createNewFile()
        assertThrows(FileNotFoundException::class.java) { docs.fileOf("home/missing") }
        assertThrows(FileNotFoundException::class.java) { docs.children("home/file") }
        assertThrows(FileNotFoundException::class.java) { docs.create("home/file", "text/plain", "child") }
        assertThrows(FileNotFoundException::class.java) { docs.create("home", "text/plain", ".l2s.backing") }
        assertThrows(FileNotFoundException::class.java) { docs.rename("home/file", ".l2s.backing") }
    }

    @Test fun idsArePathsUnderHome() {
        val home = LinuxDocuments.HOME_ID
        assertEquals("home/Downloads", LinuxDocuments.childId(home, "Downloads"))
        assertEquals("home/Downloads/benchy.gcode", LinuxDocuments.childId("home/Downloads", "benchy.gcode"))
        assertEquals("home/Downloads", LinuxDocuments.parentOf("home/Downloads/benchy.gcode"))
        assertEquals(home, LinuxDocuments.parentOf("home/Downloads"))
        assertEquals("", LinuxDocuments.relativePath(home))
        assertEquals("Downloads/benchy.gcode", LinuxDocuments.relativePath("home/Downloads/benchy.gcode"))
    }

    @Test(expected = FileNotFoundException::class)
    fun idsOutsideHomeAreRefused() {
        LinuxDocuments.relativePath("etc/passwd")
    }

    @Test fun dotfilesShowButProotLinksDont() {
        assertEquals(false, LinuxDocuments.isProotLink(".config"))
        assertEquals(false, LinuxDocuments.isProotLink(".ssh"))
        assertEquals(true, LinuxDocuments.isProotLink(".l2s.known_hosts0001.0001"))
    }

    @Test fun pickerNamesStayOneSegment() {
        assertEquals("a_b", LinuxDocuments.safeName("a/b"))
        assertEquals("untitled", LinuxDocuments.safeName(".."))
        assertEquals("untitled", LinuxDocuments.safeName("  "))
        assertEquals("print.gcode", LinuxDocuments.safeName(" print.gcode "))
    }

    @Test fun grantCleanupDoesNotCanonicalizeUnrelatedRegularFiles() {
        val home = tmp.newFolder("home")
        val bulk = File(home, "unrelated").apply { mkdir() }
        repeat(500) { File(bulk, "bulk-note-$it").createNewFile() }
        val target = File(home, "target").apply { writeText("kept") }
        var unrelatedResolutions = 0
        val docs = LinuxDocuments(home) { file ->
            if (file.name.startsWith("bulk-note-")) unrelatedResolutions++
            file.canonicalFile
        }
        assertEquals("home/renamed", docs.rename("home/target", "renamed"))
        assertEquals("kept", File(home, "renamed").readText())
        assertEquals("unrelated regular entries must not need canonical-path resolution", 0, unrelatedResolutions)
        docs.delete("home/renamed")
        assertEquals("delete must not resolve unrelated regular entries either", 0, unrelatedResolutions)
        assertEquals(500, bulk.list()!!.size)
        assertFalse(target.exists())
    }

    @Test fun grantCleanupUsesProvidedMetadataWithoutFollowingOrdinaryFiles() {
        val home = tmp.newFolder("home")
        repeat(500) { File(home, "bulk-$it").createNewFile() }
        File(home, "target").writeText("private")
        val aliasTarget = File(home, "alias-target").apply { writeText("other") }
        Files.createSymbolicLink(File(home, "alias").toPath(), aliasTarget.toPath())
        val calls = mutableListOf<Pair<String, Boolean>>()
        val docs = LinuxDocuments(home, fileTypeForScan = { file, follow ->
            calls += file.name to follow
            LinuxDocuments.readFileType(file, follow)
        })
        docs.rename("home/target", "renamed")
        assertEquals(500, calls.count { it.first.startsWith("bulk-") && !it.second })
        assertEquals(0, calls.count { it.first.startsWith("bulk-") && it.second })
        assertTrue(calls.contains("alias-target" to true))
        assertEquals("private", File(home, "renamed").readText())
    }

    @Test fun grantCleanupKeepsCanonicalSiblingPrefixSeparate() {
        val docs = docs()
        val dir = File(docs.home, "part").apply { mkdir() }
        File(dir, "note").writeText("renamed")
        val sibling = File(docs.home, "part-extra").apply { mkdir() }
        File(sibling, "note").writeText("kept")
        Files.createSymbolicLink(File(docs.home, "alias").toPath(), sibling.toPath())
        val invalidated = mutableSetOf<String>()
        docs.rename("home/part", "renamed", invalidated::add)
        assertEquals(setOf("home/part/note"), invalidated)
        assertEquals("kept", docs.fileOf("home/alias/note").readText())
        invalidated.clear()
        docs.delete("home/renamed", invalidated::add)
        assertTrue(invalidated.none { it.startsWith("home/part-extra") || it.startsWith("home/alias") })
        assertEquals("kept", docs.fileOf("home/alias/note").readText())
    }

    @Test fun grantCleanupScansPhysicalDirectoryAliasesOnlyOnce() {
        val home = tmp.newFolder("home")
        val a = File(home, "a").apply { mkdir() }
        val b = File(a, "b").apply { mkdir() }
        val c = File(b, "c").apply { mkdir() }
        File(c, "unrelated").writeText("kept")
        repeat(8) {
            Files.createSymbolicLink(File(a, "shortcut-b-$it").toPath(), b.toPath())
            Files.createSymbolicLink(File(b, "shortcut-c-$it").toPath(), c.toPath())
        }
        File(home, "target").writeText("renamed")
        var aliasResolutions = 0
        val docs = LinuxDocuments(home) { file ->
            if (file.name.startsWith("shortcut-")) aliasResolutions++
            file.canonicalFile
        }
        assertEquals("home/renamed", docs.rename("home/target", "renamed"))
        assertEquals("each physical symlink is resolved once, not once per alias path", 16, aliasResolutions)
        docs.delete("home/renamed")
        assertEquals(32, aliasResolutions)
        assertEquals("kept", File(c, "unrelated").readText())
    }

    @Test fun grantCleanupStillExpandsNestedAliasPathsWithoutRevokingParents() {
        val docs = docs()
        val a = File(docs.home, "a").apply { mkdir() }
        val b = File(a, "b").apply { mkdir() }
        File(b, "note").writeText("private")
        Files.createSymbolicLink(File(docs.home, "shortcut-a").toPath(), a.toPath())
        Files.createSymbolicLink(File(a, "shortcut-b").toPath(), b.toPath())
        val invalidated = mutableSetOf<String>()
        assertEquals("home/a/b/renamed", docs.rename("home/a/b/note", "renamed", invalidated::add))
        assertEquals(setOf("home/a/shortcut-b/note", "home/shortcut-a/b/note", "home/shortcut-a/shortcut-b/note"), invalidated)
        assertEquals("private", docs.fileOf("home/shortcut-a/shortcut-b/renamed").readText())
    }

    @Test fun unreadableDirectoryCannotSilentlyHideAnAffectedGrantedAlias() {
        val docs = docs()
        val target = File(docs.home, "target").apply { writeText("private") }
        val hidden = File(docs.home, "hidden").apply { mkdir() }
        Files.createSymbolicLink(File(hidden, "alias").toPath(), target.toPath())
        Files.setPosixFilePermissions(hidden.toPath(), setOf(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE))
        try {
            org.junit.Assume.assumeFalse("requires permissions enforced for the test user", hidden.canRead())
            // A known URI remains readable with directory search permission but no listing.
            assertEquals("private", docs.fileOf("home/hidden/alias").readText())
            val invalidated = mutableListOf<String>()
            assertThrows(java.io.IOException::class.java) { docs.rename("home/target", "renamed", invalidated::add) }
            assertThrows(java.io.IOException::class.java) { docs.delete("home/target", invalidated::add) }
            assertTrue(invalidated.isEmpty())
            assertEquals("private", target.readText())
        } finally {
            Files.setPosixFilePermissions(hidden.toPath(), setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE, java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE))
        }
    }

    @Test fun unsearchableBindPlaceholderDoesNotBlockUnrelatedMutation() {
        val docs = docs()
        val target = File(docs.home, "target").apply { writeText("kept") }
        val opaque = File(docs.home, "opaque-bind-placeholder").apply { mkdir() }
        Files.setPosixFilePermissions(opaque.toPath(), emptySet())
        try {
            org.junit.Assume.assumeFalse("requires directory search permissions enforced for the test user", opaque.canExecute())
            assertThrows(FileNotFoundException::class.java) { docs.fileOf("home/opaque-bind-placeholder/anything") }
            assertEquals("home/renamed", docs.rename("home/target", "renamed"))
            docs.delete("home/renamed")
            assertFalse(target.exists())
            assertTrue(opaque.exists())
        } finally {
            Files.setPosixFilePermissions(opaque.toPath(), setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE, java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE))
        }
    }

    @Test fun takenNamesGetANumber() {
        val dir = Files.createTempDirectory("docs").toFile()
        try {
            assertEquals("part.stl", LinuxDocuments.freeName(dir, "part.stl"))
            File(dir, "part.stl").createNewFile()
            assertEquals("part (1).stl", LinuxDocuments.freeName(dir, "part.stl"))
            File(dir, "part (1).stl").createNewFile()
            assertEquals("part (2).stl", LinuxDocuments.freeName(dir, "part.stl"))
            File(dir, "notes").mkdir()
            assertEquals("notes (1)", LinuxDocuments.freeName(dir, "notes"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
