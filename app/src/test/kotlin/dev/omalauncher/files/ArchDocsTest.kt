package dev.omalauncher.files

import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchDocsTest {

    @get:Rule var tmp = TemporaryFolder()

    private fun docs(root: File = tmp.newFolder("root")) = ArchDocs(root)

    @Test fun roundsTripsIdsThroughWeirdNames() {
        val docs = docs()
        for (name in listOf("héllo wörld", "notes #1 (2026)", "a+b", "a%2Fb", "…", "emoji 🐛", "tab\tname", "%100%")) {
            val id = docs.childDocId(docs.rootDocId, name)
            assertEquals(name, docs.fileFor(id).name)
            assertEquals(name, docs.displayNameFor(id))
        }
    }

    /** Crafted ids must never decode their way out of the shown root. */
    @Test fun rejectsTraversalAttempts() {
        val docs = docs()
        for (attempt in listOf("../ssh", "a/../../etc", "%2E%2E", "a/%2E%2E/%2E%2E/etc")) {
            assertThrows(FileNotFoundException::class.java) { docs.fileFor(attempt) }
        }
    }

    @Test fun listingSkipsBrokenSymlinks() {
        val root = tmp.newFolder("root")
        File(root, "real").createNewFile()
        File(root, "dir").mkdir()
        Files.createSymbolicLink(File(root, "dangling").toPath(), Path.of(root.name, "missing"))
        val docs = ArchDocs(root)
        assertEquals(listOf("dir", "real"), docs.visibleChildren(root).map(File::getName))
    }

    /** A symlink pointing outside the shown root must resolve to nothing usable; the target itself gets no id, either. */
    @Test fun refusesSymlinksThatEscapeTheRoot() {
        val root = tmp.newFolder("root")
        val outside = tmp.newFolder("outside")
        val secret = File(outside, "secret").apply { createNewFile() }
        Files.createSymbolicLink(File(root, "escape").toPath(), secret.toPath())
        val docs = ArchDocs(root)
        assertThrows(FileNotFoundException::class.java) { docs.fileFor(docs.childDocId(docs.rootDocId, "escape")) }
        assertNull(docs.docIdFor(secret))
    }

    /** A link that stays inside the root still works and keeps its own identity. */
    @Test fun resolvesSymlinksInsideTheRoot() {
        val root = tmp.newFolder("root")
        File(root, "target").createNewFile()
        Files.createSymbolicLink(File(root, "link").toPath(), Path.of("target"))
        val docs = ArchDocs(root)
        val id = docs.childDocId(docs.rootDocId, "link")
        assertTrue(docs.fileFor(id).isFile)
    }

    @Test fun uniquifiesCreatedNamesWithoutOverwriting() {
        val root = tmp.newFolder("root")
        val docs = ArchDocs(root)
        assertEquals("notes", docs.uniqueChild(root, "notes").name)
        File(root, "notes").createNewFile()
        assertEquals("notes (2)", docs.uniqueChild(root, "notes").name)
        File(root, "notes.txt").createNewFile()
        assertEquals("notes (2).txt", docs.uniqueChild(root, "notes.txt").name)
    }

    @Test fun refusesUnusableNames() {
        val docs = docs()
        for (bad in listOf("", ".", "..", "a/b", "a/b/")) {
            assertThrows(FileNotFoundException::class.java) { docs.uniqueChild(tmp.newFolder(), bad) }
        }
    }

    @Test fun idsRoundTripTheRootItself() {
        val docs = docs()
        assertEquals("root", docs.docIdFor(docs.root))
        assertEquals(docs.root, docs.fileFor("root"))
        assertEquals("/", docs.displayNameFor("root"))
    }
}
