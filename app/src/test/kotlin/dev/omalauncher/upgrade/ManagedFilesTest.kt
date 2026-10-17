package dev.omalauncher.upgrade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ManagedFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val base get() = tmp.root
    private val files get() = ManagedFiles(base, File(base, "managed.json"))

    private fun seeded(
        name: String,
        text: String,
    ) = Managed.text(File(base, name), Policy.Seeded) { text }

    private fun owned(
        name: String,
        text: String,
    ) = Managed.text(File(base, name), Policy.Owned) { text }

    private fun read(name: String) = File(base, name).readText()

    @Test fun writesNewFiles() {
        files.sync(listOf(seeded("a.conf", "v1"), owned("bin/shim", "#!/bin/sh")))
        assertEquals("v1", read("a.conf"))
        assertEquals("#!/bin/sh", read("bin/shim"))
    }

    @Test fun updatesUneditedDefaults() {
        files.sync(listOf(seeded("a.conf", "v1")))
        files.sync(listOf(seeded("a.conf", "v2")))
        assertEquals("v2", read("a.conf"))
    }

    @Test fun keepsEditedDefaults() {
        files.sync(listOf(seeded("a.conf", "v1")))
        File(base, "a.conf").writeText("mine")
        files.sync(listOf(seeded("a.conf", "v2")))
        assertEquals("mine", read("a.conf"))
        // Still the user's on later updates, too.
        files.sync(listOf(seeded("a.conf", "v3")))
        assertEquals("mine", read("a.conf"))
    }

    @Test fun neverAdoptsFilesThatWereThereFirst() {
        File(base, "a.conf").writeText("theirs")
        files.sync(listOf(seeded("a.conf", "v1")))
        files.sync(listOf(seeded("a.conf", "v2")))
        assertEquals("theirs", read("a.conf"))
    }

    @Test fun doesNotBringBackDeletedDefaults() {
        files.sync(listOf(seeded("a.conf", "v1")))
        File(base, "a.conf").delete()
        files.sync(listOf(seeded("a.conf", "v2")))
        assertFalse(File(base, "a.conf").exists())
    }

    @Test fun alwaysRewritesOwnedFiles() {
        files.sync(listOf(owned("shim", "v1")))
        File(base, "shim").writeText("broken")
        files.sync(listOf(owned("shim", "v2")))
        assertEquals("v2", read("shim"))
    }

    @Test fun removesDroppedFilesUnlessEdited() {
        files.sync(listOf(seeded("old.desktop", "x"), seeded("edited.desktop", "y"), seeded("kept.desktop", "z")))
        File(base, "edited.desktop").writeText("mine")
        files.sync(listOf(seeded("kept.desktop", "z")))
        assertFalse(File(base, "old.desktop").exists())
        assertEquals("mine", read("edited.desktop"))
        assertEquals("z", read("kept.desktop"))
    }

    @Test fun forgetAllStartsOver() {
        files.sync(listOf(seeded("a.conf", "v1")))
        files.forgetAll()
        File(base, "a.conf").delete()
        files.sync(listOf(seeded("a.conf", "v2")))
        assertEquals("v2", read("a.conf"))
    }

    @Test fun executableFlag() {
        files.sync(listOf(Managed.text(File(base, "run"), Policy.Owned, executable = true) { "#!/bin/sh" }))
        assertTrue(File(base, "run").canExecute())
    }

    @Test fun aFailedWriteRemovesNothingAndRetries() {
        files.sync(listOf(seeded("a.conf", "v1"), seeded("b.conf", "x")))
        val broken = Managed(File(base, "a.conf"), Policy.Seeded) { error("asset missing") }
        files.sync(listOf(broken))
        // b.conf dropped out in the same sync, but it failed: nothing is deleted this time.
        assertEquals("x", read("b.conf"))
        files.sync(listOf(seeded("a.conf", "v2")))
        assertEquals("v2", read("a.conf"))
        assertFalse(File(base, "b.conf").exists())
    }
}
