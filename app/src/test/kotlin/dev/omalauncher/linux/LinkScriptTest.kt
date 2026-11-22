package dev.omalauncher.linux

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/**
 * omalauncher-link.sh, what a linked computer's key is forced to: which requests it passes to `omalauncher link`
 * (a stand-in here that prints its arguments and the file put hands over), and what it refuses.
 */
class LinkScriptTest {
    private val script = File("src/main/assets/omalauncher-link.sh")
    private lateinit var dir: File
    private lateinit var fake: File

    @Before fun setUp() {
        assumeTrue("needs bash", BASH != null)
        dir = createTempDirectory("link").toFile().canonicalFile
        fake =
            File(dir, "omalauncher").apply {
                writeText("#!/bin/sh\nprintf '%s|' \"\$@\"\n[ \"\$2\" = put ] && cat \"\$5\"\nexit 0\n")
                setExecutable(true)
            }
    }

    @After fun tearDown() {
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private class Run(
        val code: Int,
        val out: String,
    )

    private fun run(
        command: String?,
        stdin: ByteArray = ByteArray(0),
        id: String = "abc123",
        theme: File? = null,
    ): Run {
        val p =
            ProcessBuilder(BASH!!, script.absolutePath, id)
                .apply {
                    environment()["OMALAUNCHER_COMMAND"] = fake.path
                    environment()["OMALAUNCHER_LINK_TMP"] = dir.path
                    if (theme != null) environment()["OMALAUNCHER_LINK_THEME"] = theme.path
                    if (command != null) environment()["SSH_ORIGINAL_COMMAND"] = command else environment().remove("SSH_ORIGINAL_COMMAND")
                }.redirectErrorStream(true)
                .start()
        p.outputStream.use { it.write(stdin) }
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS))
        return Run(p.exitValue(), out)
    }

    @Test fun passesRequestsWithTheComputersId() {
        assertEquals("link|hello|abc123|", run("hello").out)
        assertEquals("link|widgets|abc123|", run("widgets").out)
        assertEquals("link|get|abc123|clock.owl|", run("get clock.owl").out)
        assertEquals("link|theme|abc123|tokyo-night|", run("theme tokyo-night").out)
        assertEquals("link|sync|abc123|", run("sync").out)
        assertEquals("link|sync|abc123|background|on|", run("sync background on").out)
        assertEquals(2, run("sync background").code)
        assertEquals("link|sync|abc123|widget|notes.owl|on|", run("sync widget notes.owl on").out)
        assertEquals("link|synced|abc123|", run("synced").out)
        assertEquals("link|state|abc123|get|notes.owl|", run("state get notes.owl").out)
        assertEquals(2, run("state notes.owl").code)
    }

    @Test fun putHandsOverStdinAndCleansUp() {
        val r = run("put clock.owl", "widget \"clock\" {}\n".toByteArray())
        assertEquals(0, r.code)
        assertTrue(r.out, r.out.startsWith("link|put|abc123|clock.owl|${dir.path}/omalauncher-link."))
        assertTrue(r.out.endsWith("|widget \"clock\" {}\n"))
        assertEquals(listOf("omalauncher"), dir.list()!!.toList())
    }

    @Test fun refusesTheRest() {
        for (bad in listOf(null, "", "sh", "widgets extra", "get", "get a b", "put", "hello; sh")) {
            val r = run(bad)
            assertEquals("'$bad': ${r.out}", 2, r.code)
            assertTrue(r.out, !r.out.startsWith("link|"))
        }
        assertEquals(2, run("hello", id = "").code)
        // Words aren't globbed or run: they reach the launcher as they are, which checks names.
        assertEquals("link|get|abc123|*|", run("get *").out)
        assertEquals("link|get|abc123|\$(id)|", run("get \$(id)").out)
    }

    @Test fun watchAnswersOnceThemeOrBackgroundIsntTheOneSeen() {
        // The launcher allows the theme, not the background: the background stays out of the answer and the key.
        fake.writeText("#!/bin/sh\necho '{\"theme\":true,\"background\":false}'\n")
        val theme = File(dir, "theme").apply { writeText("tokyo-night\n\n1-launch.jpg\n") }
        assertEquals(
            "{\"theme\":\"tokyo-night\",\"source\":\"\",\"background\":\"\",\"key\":\"tokyo-night//\",\"syncTheme\":true}\n",
            run("watch ristretto//", theme = theme).out,
        )
        // Both allowed: a new background alone is a change.
        fake.writeText("#!/bin/sh\necho '{\"theme\":true,\"background\":true}'\n")
        Thread {
            Thread.sleep(1_000)
            theme.writeText("redshift\nhttps://github.com/TheFifeDawg/omarchy-redshift-theme\n2-orbit.jpg\n")
        }.start()
        assertEquals(
            "{\"theme\":\"redshift\",\"source\":\"https://github.com/TheFifeDawg/omarchy-redshift-theme\",\"background\":\"2-orbit.jpg\",\"key\":\"redshift/2-orbit.jpg/\",\"syncTheme\":true}\n",
            run("watch tokyo-night/1-launch.jpg/", theme = theme).out,
        )
        // What the launcher wouldn't write isn't passed on.
        theme.writeText("redshift\nhttps://evil.example/x\"}\na\"b.jpg\n")
        assertEquals(
            "{\"theme\":\"redshift\",\"source\":\"\",\"background\":\"\",\"key\":\"redshift//\",\"syncTheme\":true}\n",
            run("watch", theme = theme).out,
        )
    }

    @Test fun bggetSendsTheImageTheLauncherCopiedAndDeletesIt() {
        val img = File(dir, "omalauncher-bg-abc").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        fake.writeText("#!/bin/sh\necho '{\"path\":\"${img.path}\"}'\n")
        val r = run("bgget 2-orbit.jpg")
        assertEquals(0, r.code)
        assertEquals("\u0001\u0002\u0003", r.out)
        assertTrue(!img.exists())
        // A path anywhere else isn't read.
        fake.writeText("#!/bin/sh\necho '{\"path\":\"/etc/passwd\"}'\n")
        assertEquals(1, run("bgget 2-orbit.jpg").code)
    }

    @Test fun watchStopsWhereTheLauncherSaysNo() {
        fake.writeText("#!/bin/sh\necho 'omalauncher: theme sync is off' >&2\nexit 3\n")
        val r = run("watch", theme = File(dir, "theme").apply { writeText("x\n") })
        assertEquals(3, r.code)
        assertTrue(r.out, !r.out.contains("theme\""))
    }

    @Test fun watchWakesForAWidgetChange() {
        fake.writeText("#!/bin/sh\necho '{\"theme\":true,\"background\":false}'\n")
        val theme = File(dir, "theme").apply { writeText("x\n\n\nabc123\n") }
        assertEquals("{\"theme\":\"x\",\"source\":\"\",\"background\":\"\",\"key\":\"x//abc123\",\"syncTheme\":true}\n", run("watch x//000", theme = theme).out)
    }

    @Test fun statePutHandsOverStdin() {
        fake.writeText("#!/bin/sh\nprintf '%s|' \"\$@\"\ncat \"\$6\"\n")
        val r = run("state put notes.owl", "{\"savedAt\":1,\"values\":{}}".toByteArray())
        assertTrue(r.out, r.out.startsWith("link|state|abc123|put|notes.owl|"))
        assertTrue(r.out, r.out.endsWith("{\"savedAt\":1,\"values\":{}}"))
        assertEquals(listOf("omalauncher"), dir.list()!!.toList())
    }

    /** omalauncher-sync, the plugin's script that the launcher runs to follow other devices (DeviceSync), parses. */
    @Test fun syncEngineParses() {
        val p = ProcessBuilder(BASH!!, "-n", File("src/main/assets/omalauncher-sync.sh").absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals(out, 0, p.exitValue())
    }

    @Test fun refusesAWidgetTooBig() {
        val r = run("put big.owl", ByteArray(ComputerLink.MAX_BYTES.toInt() + 1) { 'a'.code.toByte() })
        assertEquals(2, r.code)
        assertEquals(listOf("omalauncher"), dir.list()!!.toList())
    }

    private companion object {
        val BASH = listOf("/opt/homebrew/bin/bash", "/usr/local/bin/bash", "/usr/bin/bash", "/bin/bash").firstOrNull { File(it).canExecute() }
    }
}
