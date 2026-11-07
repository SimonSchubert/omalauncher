package dev.omalauncher.linux

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class GuestFileBridgeTest {
    @get:Rule val dir = TemporaryFolder()
    private val root by lazy { dir.newFolder("arch") }
    private val own by lazy { dir.newFolder("widget-state") }
    private fun host(path: String): File = File(root, path.trimStart('/'))
    private val fs by lazy { GuestFs(::host) { it.startsWith(root.canonicalFile) } }
    private fun put(name: String, text: String = "payload") = text.byteInputStream().use { importGuestFile(fs, name, it) }

    @Test fun importKeepsExistingFilesAndNumbersNames() {
        assertEquals("/root/Downloads/a.stl", put("a.stl", "first"))
        assertEquals("/root/Downloads/a (1).stl", put("a.stl", "second"))
        assertEquals("first", host("/root/Downloads/a.stl").readText())
        assertEquals("second", host("/root/Downloads/a (1).stl").readText())
    }

    @Test fun danglingNameIsOccupiedAndItsTargetIsNotCreated() {
        val downloads = host("/root/Downloads").apply { mkdirs() }
        val outside = File(own, "absent.stl")
        Files.createSymbolicLink(File(downloads, "a.stl").toPath(), outside.toPath())
        assertEquals("/root/Downloads/a (1).stl", put("a.stl"))
        assertFalse(outside.exists())
        assertTrue(Files.isSymbolicLink(File(downloads, "a.stl").toPath()))
    }

    @Test fun downloadsLinkUsesGuestNotAndroidNamespace() {
        host("/root").mkdirs()
        val protected = File(own, "a.stl").apply { writeText("private") }
        Files.createSymbolicLink(host("/root/Downloads").toPath(), own.toPath())
        put("a.stl")
        assertEquals("private", protected.readText())
        assertFalse(File(own, "a (1).stl").exists())
        assertEquals("payload", fs.host("/root/Downloads/a.stl").readText())
    }

    @Test fun leakyBindIsRejectedBeforeWriting() {
        val leaky = GuestFs({ own }) { it.startsWith(root.canonicalFile) }
        assertTrue(runCatching { "x".byteInputStream().use { importGuestFile(leaky, "a", it) } }.exceptionOrNull() is IOException)
        assertEquals(0, own.listFiles()!!.size)
    }

    @Test fun failedReadRemovesOnlyNewPartialFile() {
        put("a.stl", "original")
        val broken = object : InputStream() { override fun read(): Int = throw IOException("read failed") }
        assertTrue(runCatching { importGuestFile(fs, "a.stl", broken) }.exceptionOrNull() is IOException)
        assertEquals("original", host("/root/Downloads/a.stl").readText())
        assertFalse(host("/root/Downloads/a (1).stl").exists())
    }

    @Test fun importedNameStaysOneComponent() {
        assertEquals("/root/Downloads/.._a.stl", put("../a.stl"))
        assertEquals("/root/Downloads/shared", put(".."))
    }
}
