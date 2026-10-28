package dev.omalauncher.linux

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class GuestFileBridgeTest {
    @get:Rule val dir = TemporaryFolder()
    private val root by lazy { dir.newFolder("arch") }
    private val runtime by lazy { dir.newFolder("runtime") }
    private val own by lazy { dir.newFolder("widget-state") }
    private val prefix = Proot.GUEST_RUNTIME_DIR
    private fun host(path: String): File = if (path == prefix || path.startsWith("$prefix/")) File(runtime, path.removePrefix(prefix).trimStart('/')) else File(root, path.trimStart('/'))
    private val fs by lazy { GuestFs(::host) { it.startsWith(root.canonicalFile) || it.startsWith(runtime.canonicalFile) } }

    @Test fun replyMustResolveInsideRuntime() {
        val pipe = File(runtime, "pick.test").apply { writeText("fixture") }
        assertEquals(pipe, guestReplyFile(fs, "$prefix/pick.test"))
        assertNull(guestReplyFile(fs, "/root/reply"))
        assertNull(guestReplyFile(fs, "$prefix/../reply"))
        Files.createSymbolicLink(File(runtime, "escape").toPath(), own.toPath())
        assertNull(guestReplyFile(fs, "$prefix/escape/reply"))
    }

    @Test fun replyLinkIsRecheckedAfterPickerWasOpened() {
        val good = File(runtime, "pick.good").apply { writeText("fixture") }
        val link = File(runtime, "pick.reply")
        Files.createSymbolicLink(link.toPath(), File("pick.good").toPath())
        assertEquals(good, guestReplyFile(fs, "$prefix/pick.reply"))
        Files.delete(link.toPath())
        Files.createSymbolicLink(link.toPath(), own.toPath())
        assertNull(guestReplyFile(fs, "$prefix/pick.reply"))
    }
}
