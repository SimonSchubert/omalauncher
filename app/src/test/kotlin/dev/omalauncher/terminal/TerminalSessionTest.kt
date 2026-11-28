package dev.omalauncher.terminal

import dev.omalauncher.HostNative
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * cpp/pty.c and TerminalSession with real programs on a pseudo-terminal of the machine the tests run on: what
 * reaches the screen, keys, resizing and hanging up. Skipped without a C compiler or scripts/fetch-ghostty-vt.sh's
 * library.
 */
class TerminalSessionTest {
    private val sessions = mutableListOf<TerminalSession>()
    private val ended = HashMap<TerminalSession, CountDownLatch>()

    @After fun close() = sessions.forEach(TerminalSession::close)

    private fun start(
        vararg argv: String,
        cols: Int = 80,
        rows: Int = 24,
    ): TerminalSession {
        val latch = CountDownLatch(1)
        val session = TerminalSession(argv.toList(), ENV, "/", cols, rows, onExit = { latch.countDown() })
        sessions += session
        ended[session] = latch
        return session
    }

    private fun sh(script: String) = start("/bin/sh", "-c", script)

    private fun TerminalSession.awaitExit(): Int {
        assertTrue("didn't end", ended.getValue(this).await(10, TimeUnit.SECONDS))
        return exitCode!!
    }

    private fun TerminalSession.awaitText(text: String) {
        val until = System.nanoTime() + 10_000_000_000
        while (text !in vt.text()) {
            if (System.nanoTime() > until) throw AssertionError("no \"$text\" on screen:\n${vt.text()}")
            Thread.sleep(10)
        }
    }

    @Test fun runsAProgramAndShowsItsScreen() {
        val s = sh("printf 'a\\033[31mb\\033[0m\\nline 2'; exit 4")
        assertEquals(4, s.awaitExit())
        assertEquals("ab\nline 2", s.vt.text())
    }

    @Test fun runsOnATerminalOfItsSize() {
        // tty's name with a newline of its own: uutils' tty (Ubuntu 26.04, CI's runner) ends it without one.
        val s = sh("printf '%s\\n' \"\$(tty)\"; stty size")
        s.awaitExit()
        val lines = s.vt.text().lines()
        assertTrue(lines[0], lines[0].startsWith("/dev/"))
        assertEquals("24 80", lines[1])
    }

    @Test fun repliesReachTheProgram() {
        // The program asks where the cursor is; the terminal's answer comes back as input.
        val s = sh("stty raw -echo; printf '\\033[5;7H\\033[6n'; r=$(dd bs=1 count=6 2>/dev/null | tr -d '\\033'); printf '\\033[HGOT %s' \"\$r\"")
        s.awaitExit()
        assertTrue(s.vt.text(), s.vt.text().startsWith("GOT [5;7R"))
    }

    @Test fun typesAndPressesKeys() {
        val s = start("/bin/cat")
        s.type("hello")
        assertTrue(s.press(VtKey.ENTER))
        s.awaitText("hello\nhello")
        // Ctrl+D at the start of a line: end of input.
        assertTrue(s.press(VtKey.D, Vt.CTRL, unshifted = 'd'.code))
        assertEquals(0, s.awaitExit())
    }

    @Test fun ctrlCInterrupts() {
        val s = sh("echo ready; sleep 30; echo not interrupted")
        s.awaitText("ready")
        s.press(VtKey.C, Vt.CTRL, unshifted = 'c'.code)
        assertEquals(128 + 2, s.awaitExit())
    }

    @Test fun resizeReachesTheProgram() {
        val s = sh("trap 'stty size; exit 0' WINCH; echo ready; while :; do sleep 0.05; done")
        s.awaitText("ready")
        s.resize(100, 30, 10, 20)
        s.awaitText("30 100")
        assertEquals(0, s.awaitExit())
    }

    @Test fun hangingUpEndsTheShell() {
        val s = sh("echo ready; sleep 30")
        s.awaitText("ready")
        s.hangUp()
        // SIGHUP, as when foot's window closes.
        assertEquals(128 + 1, s.awaitExit())
        assertFalse(s.type("too late"))
        // The screen stays readable until the session is closed.
        assertEquals("ready", s.vt.text())
    }

    @Test fun aProgramThatDoesNotReadHoldsNobodyUp() {
        val s = sh("stty raw -echo; echo ready; sleep 30")
        s.awaitText("ready")
        // Far more than a terminal buffers: taken at once, and hanging up still ends the shell.
        val started = System.nanoTime()
        assertTrue(s.send(ByteArray(1 shl 20) { 'x'.code.toByte() }))
        assertTrue(s.type("more"))
        assertTrue("send waited", System.nanoTime() - started < 2_000_000_000)
        s.hangUp()
        assertEquals(128 + 1, s.awaitExit())
    }

    @Test fun aLotOfInputArrivesWholeAndInOrder() {
        val s = sh("stty raw -echo; echo ready; head -c 300003 | cksum")
        s.awaitText("ready")
        val block = ByteArray(100_000) { ('a' + it % 26).code.toByte() }
        repeat(3) { assertTrue(s.send(block)) }
        s.type("end")
        // cksum of that input, as `cksum` prints it for the same bytes from a file.
        val all = block + block + block + "end".encodeToByteArray()
        val file = java.io.File.createTempFile("input", ".bin").apply { deleteOnExit() }
        file.writeBytes(all)
        val expected = ProcessBuilder("cksum", file.path).start().inputStream.bufferedReader().readText().substringBefore(' ')
        s.awaitText(expected)
        assertEquals(0, s.awaitExit())
    }

    @Test fun endsWhenTheProgramDoes() {
        val s = sh("exit 0")
        assertEquals(0, s.awaitExit())
        assertFalse(s.press(VtKey.ENTER))
        s.hangUp()
    }

    @Test fun missingProgram() {
        val s = start("/no/such/program")
        assertEquals(127, s.awaitExit())
    }

    @Test fun guestScript() {
        assertEquals(
            "shell=\$(getent passwd \"\$(id -u)\" | cut -d: -f7); exec env COLORTERM=truecolor setsid -c \"\${shell:-/bin/bash}\"",
            TerminalSession.guestScript(null),
        )
        assertEquals(
            "shell=\$(getent passwd \"\$(id -u)\" | cut -d: -f7); exec env COLORTERM=truecolor setsid -c bash -c 'btop'",
            TerminalSession.guestScript("btop"),
        )
    }

    companion object {
        private val ENV = listOf("PATH=/usr/bin:/bin", "TERM=xterm-256color", "LANG=C.UTF-8")

        @BeforeClass
        @JvmStatic
        fun build() {
            HostNative.load("pty.c")
            HostNative.load("vt.c", extra = HostNative.ghosttyVt())
        }
    }
}
