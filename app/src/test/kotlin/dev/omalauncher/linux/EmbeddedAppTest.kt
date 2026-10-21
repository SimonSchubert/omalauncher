package dev.omalauncher.linux

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EmbeddedAppTest {
    @Test fun commandKeepsItsShellSyntaxAndReceivesTheExactAppId() {
        val command = EmbeddedAppCommand("printf '%s' \"\u0024OMALAUNCHER_APP_ID\"; printf '|ok'")
        val id = "org.example.a'b \u0024(uname)"
        val child = ProcessBuilder("sh", "-c", command.launch(id)).start()
        assertEquals("$id|ok", child.inputStream.bufferedReader().readText())
        assertEquals(0, child.waitFor())
    }

    @Test fun sdl2AndSdl3GetTheSameId() {
        val command = EmbeddedAppCommand("printf '%s|%s' \"\u0024SDL_VIDEO_WAYLAND_WMCLASS\" \"\u0024SDL_APP_ID\"")
        val child = ProcessBuilder("sh", "-c", command.launch("omarchy.linux-tile-7")).start()
        assertEquals("omarchy.linux-tile-7|omarchy.linux-tile-7", child.inputStream.bufferedReader().readText())
        assertEquals(0, child.waitFor())
    }

    @Test fun rejectsBlankCommandsAndInvalidExplicitIds() {
        assertNotNull(EmbeddedAppCommand("  ").error())
        assertNotNull(EmbeddedAppCommand("demo", "bad id").error())
        assertNull(EmbeddedAppCommand("demo", "org.example.Viewer").error())
    }

    @Test fun cancellingBeforeLaunchDoesNotStartTheProgram() {
        val done = CountDownLatch(1)
        var built = false
        val process = EmbeddedAppProcess({ built = true; ProcessBuilder("false") }) { _, _ -> done.countDown() }
        process.close()
        process.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertFalse(built)
    }

    @Test fun cancellingDuringStartupReapsTheChild() {
        val building = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val done = CountDownLatch(1)
        val process = EmbeddedAppProcess({
            building.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
            ProcessBuilder("sleep", "30")
        }) { _, _ -> done.countDown() }
        process.start()
        assertTrue(building.await(5, TimeUnit.SECONDS))
        process.close()
        proceed.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Test fun reportsProgramFailuresWithOutput() {
        val done = CountDownLatch(1)
        var result: Pair<Int, String>? = null
        EmbeddedAppProcess({ ProcessBuilder("sh", "-c", "echo missing-library >&2; exit 7") }) { code, output ->
            result = code to output
            done.countDown()
        }.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(7 to "missing-library", result)
    }

    @Test fun outputWithoutNewlinesIsBoundedAndKeepsItsTail() {
        val done = CountDownLatch(1)
        var result: Pair<Int, String>? = null
        EmbeddedAppProcess({
            ProcessBuilder("python3", "-c", "import sys; sys.stdout.write('x' * 2000000 + 'TAIL')")
        }) { code, output -> result = code to output; done.countDown() }.start()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(0, result!!.first)
        assertEquals(300, result!!.second.length)
        assertTrue(result!!.second.endsWith("TAIL"))
    }

    @Test fun startingTwiceOnlyLaunchesOneProcess() {
        val built = java.util.concurrent.atomic.AtomicInteger()
        val done = CountDownLatch(1)
        val process = EmbeddedAppProcess({ built.incrementAndGet(); ProcessBuilder("true") }) { _, _ -> done.countDown() }
        process.start()
        process.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, built.get())
    }

    @Test fun stopTimeoutDoesNotPretendAnUnreapedProcessHasExited() {
        val marker = java.nio.file.Files.createTempFile("linuxapp-stop-", ".pid")
        val timeout = CountDownLatch(1)
        val done = CountDownLatch(1)
        var child: ProcessHandle? = null
        val process = EmbeddedAppProcess(
            builder = { ProcessBuilder("python3", "-c",
                "import signal,os,time,pathlib; signal.signal(signal.SIGTERM,signal.SIG_IGN); " +
                    "pathlib.Path('" + marker + "').write_text(str(os.getpid())); time.sleep(30)") },
            onStopTimeout = { timeout.countDown() },
            stopTimeoutMs = 100,
        ) { _, _ -> done.countDown() }
        try {
            process.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (java.nio.file.Files.size(marker) == 0L && System.nanoTime() < deadline) Thread.sleep(10)
            child = ProcessHandle.of(java.nio.file.Files.readString(marker).toLong()).orElseThrow()
            process.close()
            assertTrue(timeout.await(5, TimeUnit.SECONDS))
            assertTrue(child.isAlive)
            assertEquals(1L, done.count)
        } finally {
            // This is a direct host child, not proot: forced cleanup is safe only in this test.
            child?.destroyForcibly()
            process.close()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            java.nio.file.Files.deleteIfExists(marker)
        }
    }

}
