package dev.omalauncher.linux

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import dev.omalauncher.upgrade.ManagedFiles
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Isolated fixtures: no distro download, user preferences, storage grants, or installed rootfs touched. */
class AndroidIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scratch: File
    private lateinit var broker: AndoBroker
    private val binary get() = File(context.applicationInfo.nativeLibraryDir, "libando.so")
    private val socket get() = File(broker.directory, "ando.sock")

    @Before fun setup() {
        scratch = File(context.cacheDir, "ando-test-${System.nanoTime()}").apply { mkdirs() }
        broker = AndoBroker(binary, File(scratch, "broker"))
        broker.start()
        assertNull(broker.error)
        assertTrue(socket.exists())
    }

    @After fun cleanup() {
        if (::broker.isInitialized) broker.stop()
        if (::scratch.isInitialized) scratch.deleteRecursively()
    }

    private fun client(vararg args: String): Process = ProcessBuilder(listOf(binary.path) + args).apply {
        environment()["OMALAUNCHER_ANDO_SOCKET"] = socket.path
        directory(scratch)
    }.start()

    private data class Result(val code: Int, val out: String, val err: String)
    private fun result(process: Process, input: ByteArray = byteArrayOf()): Result {
        val out = CompletableFuture.supplyAsync { process.inputStream.readBytes().decodeToString() }
        val err = CompletableFuture.supplyAsync { process.errorStream.readBytes().decodeToString() }
        process.outputStream.use { it.write(input) }
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("ando timed out") }
        return Result(process.exitValue(), out.get(2, TimeUnit.SECONDS), err.get(2, TimeUnit.SECONDS))
    }

    @Test fun preservesArgumentsStreamsAndExitStatus() {
        val text = "space ' quote \\ newline\n雪"
        val r = result(client("/system/bin/sh", "-c", "printf '%s' \"\$1\"; printf error >&2; exit 37", "test", text))
        assertEquals(Result(37, text, "error"), r)
        assertEquals("input\n", result(client("cat"), "input\n".toByteArray()).out)
    }

    @Test fun preservesWorkingDirectoryAndUsesRealAndroidIdentity() {
        val dir = File(scratch, "working directory").apply { mkdirs() }
        val r = result(client("-D", dir.path, "pwd"))
        assertEquals(r.toString(), 0, r.code)
        assertEquals(dir.canonicalPath, r.out.trim())
        assertEquals(android.os.Process.myUid().toString(), result(client("id", "-u")).out.trim())
    }

    @Test fun environmentsAreIsolatedAndExplicitValuesWin() {
        val p = ProcessBuilder(binary.path, "-E", "-e", "ANDO_TEST=explicit\nvalue", "/system/bin/sh", "-c", "printf '%s|%s' \"\$ANDO_TEST\" \"\$PATH\"")
        p.directory(scratch)
        p.environment().apply { put("OMALAUNCHER_ANDO_SOCKET", socket.path); put("ANDO_TEST", "inherited"); put("PATH", "/guest-only") }
        val r = result(p.start())
        assertEquals(r.toString(), 0, r.code)
        assertTrue(r.out.startsWith("explicit\nvalue|"))
        assertFalse(r.out.contains("/guest-only"))
        assertEquals("", result(client("/system/bin/sh", "-c", "printf '%s' \"\$ANDO_TEST\"")).out)
    }

    @Test fun shellPreservesEmptyArgumentsAndMetacharacters() {
        val r = result(client("-s", "printf", "<%s>", "", "a; echo bad", "quote'"))
        assertEquals(Result(0, "<><a; echo bad><quote'>", ""), r)
    }

    @Test fun closedStandardDescriptorsAreReplacedByDevNull() {
        for (fd in 0..2) {
            val p = ProcessBuilder("/system/bin/sh", "-c", "exec ${shellQuote(binary.path)} /system/bin/true $fd<&-").apply {
                directory(scratch)
                environment()["OMALAUNCHER_ANDO_SOCKET"] = socket.path
            }.start()
            assertEquals(0, result(p).code)
        }
    }

    @Test fun forwardsTerminationSignalsToTheCommandGroup() {
        val pidFile = File(scratch, "command-pid")
        val script = "${shellQuote(binary.path)} /system/bin/sh -c " +
            shellQuote("echo \$\$ > ${shellQuote(pidFile.path)}; exec sleep 60") +
            " & client=\$!; while [ ! -s ${shellQuote(pidFile.path)} ]; do sleep 0.02; done; kill -TERM \$client; wait \$client"
        val p = ProcessBuilder("/system/bin/sh", "-c", script).apply {
            directory(scratch)
            environment()["OMALAUNCHER_ANDO_SOCKET"] = socket.path
        }.start()
        assertEquals(143, result(p).code)
        assertThrows(android.system.ErrnoException::class.java) { Os.kill(pidFile.readText().trim().toInt(), 0) }
    }

    @Test fun reportsMissingCommandsAndDisabledBroker() {
        assertEquals(127, result(client("/system/bin/no-such-omalauncher-command")).code)
        broker.stop()
        val r = result(client("id"))
        assertEquals(127, r.code)
        assertTrue(r.err.contains("Enable it in Omalauncher"))
    }

    @Test fun concurrentClientsAndMalformedRequestDoNotPoisonBroker() {
        LocalSocket().use {
            it.connect(LocalSocketAddress(socket.path, LocalSocketAddress.Namespace.FILESYSTEM))
            it.outputStream.write('F'.code)
        }
        val clients = (1..8).map { client("/system/bin/sh", "-c", "printf '%s' \"\$1\"", "test", it.toString()) }
        clients.forEachIndexed { index, p -> assertEquals(Result(0, (index + 1).toString(), ""), result(p)) }
    }

    @Test fun stopTerminatesRunningCommandsAndCanRestart() {
        val p = client("/system/bin/sh", "-c", "echo \$\$; exec sleep 60")
        val pid = CompletableFuture.supplyAsync { p.inputStream.bufferedReader().readLine().toInt() }.get(3, TimeUnit.SECONDS)
        broker.stop()
        assertTrue(p.waitFor(3, TimeUnit.SECONDS))
        assertThrows(android.system.ErrnoException::class.java) { Os.kill(pid, 0) }
        broker.start()
        assertNull(broker.error)
        assertEquals(0, result(client("true")).code)
    }

    @Test fun disconnectTerminatesCommand() {
        val p = client("/system/bin/sh", "-c", "echo \$\$; exec sleep 60")
        val pid = CompletableFuture.supplyAsync { p.inputStream.bufferedReader().readLine().toInt() }.get(3, TimeUnit.SECONDS)
        p.destroyForcibly()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline && runCatching { Os.kill(pid, 0) }.isSuccess) Thread.sleep(20)
        assertThrows(android.system.ErrnoException::class.java) { Os.kill(pid, 0) }
    }

    @Test fun provisioningRepairsTheCliAndRefreshesExistingInstalls() {
        val root = File(scratch, "rootfs").apply { mkdirs() }
        val managed = ManagedFiles(root, File(scratch, "manifest.json"))
        managed.sync(broker.managed(root))
        val cli = File(root, "usr/local/bin/ando")
        assertArrayEquals(binary.readBytes(), cli.readBytes())
        cli.writeText("stale version")
        managed.sync(broker.managed(root))
        assertArrayEquals(binary.readBytes(), cli.readBytes())
        assertTrue(cli.canExecute())
    }

    @Test fun prootGuestTransfersBoundCwdAndWritesExternalStorage() {
        val native = binary.parentFile!!
        val proot = File(native, "libproot.so")
        assertTrue("Run scripts/fetch-test-proot.py before emulator instrumentation", proot.isFile)
        val root = File(scratch, "guest").apply { mkdirs() }
        File(root, "usr/local/bin").mkdirs()
        binary.copyTo(File(root, "usr/local/bin/ando")).setExecutable(true)
        val shared = File(scratch, "external documents").apply { mkdirs() }
        val binds = StorageBinds.prepare(root, listOf(StorageBind(shared.path, "/mnt/documents")))
        File(shared, "from-android").writeText("android-data")
        val argv = listOf(proot.path, "--kill-on-exit", "-0", "-r", root.path, "-b", "/dev",
            "-b", "${broker.directory.path}:${Proot.GUEST_ANDO_DIR}") +
            binds.flatMap { (guest, host) -> listOf("-b", "${host.path}:$guest") } +
            listOf("-w", "/", "/usr/local/bin/ando", "-D", "/mnt/documents", "/system/bin/sh", "-c",
                "cat from-android; printf linux-data > from-linux; printf '|'; id -u")
        val p = ProcessBuilder(argv).apply {
            environment().apply {
                clear()
                put("PROOT_LOADER", File(native, "libproot-loader.so").path)
                put("PROOT_TMP_DIR", scratch.path)
                put("LD_LIBRARY_PATH", native.path)
            }
        }.start()
        val r = result(p)
        assertEquals(r.toString(), 0, r.code)
        assertEquals("android-data|${android.os.Process.myUid()}", r.out.trim())
        assertEquals("linux-data", File(shared, "from-linux").readText())
        root.deleteRecursively()
        assertEquals("linux-data", File(shared, "from-linux").readText())
    }

    @Test fun bindPreparationRejectsMissingSourcesDataHidingAndSymlinks() {
        val root = File(scratch, "rootfs").apply { mkdirs() }
        val source = File(scratch, "shared folder").apply { mkdirs() }
        val bind = StorageBind(source.path, "/mnt/shared")
        StorageBinds.prepare(root, listOf(bind))
        File(source, "keep").writeText("external")
        StorageBinds.prepare(root, listOf(bind))
        File(root, "mnt/shared/private").writeText("private")
        assertThrows(IllegalArgumentException::class.java) { StorageBinds.prepare(root, listOf(bind)) }
        assertThrows(IllegalArgumentException::class.java) { StorageBinds.prepare(root, listOf(bind.copy(hostPath = source.path + "/missing"))) }
        Os.symlink(source.path, File(root, "linked").path)
        assertThrows(IllegalArgumentException::class.java) { StorageBinds.prepare(root, listOf(bind.copy(guestPath = "/linked/sub"))) }
        File(root, "linked").delete()
        root.deleteRecursively()
        assertEquals("external", File(source, "keep").readText())
    }
}
