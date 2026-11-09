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
 * omalauncher.sh against a stand-in for PhoneBridge: its arguments, the request it writes, and how it turns answers
 * (and none) into output and exit codes. The bridge's side of the inbox is PhoneBridge's; this only fakes it.
 */
class OmalauncherScriptTest {
    private val script = File("src/main/assets/omalauncher.sh")
    private lateinit var dir: File
    private lateinit var inbox: File

    /** What the stand-in answers; null: it takes no request, as a launcher not listening. */
    @Volatile private var answer: ((List<String>) -> String)? = { "ok" }

    @Volatile private var answerAfterMs = 0L

    @Volatile private var received: List<String>? = null

    @Volatile private var running = true
    private lateinit var launcher: Thread

    @Before fun setUp() {
        assumeTrue("needs bash 5 (EPOCHREALTIME)", BASH != null)
        // Canonical: bash gives $PWD as the real path (/private/var on macOS).
        dir = createTempDirectory("omalauncher").toFile().canonicalFile
        inbox = File(dir, "phone").apply { mkdirs() }
        launcher =
            Thread {
                while (running) {
                    for (req in inbox.listFiles { f -> f.name.endsWith(".req") && !f.name.startsWith(".") }.orEmpty()) {
                        val id = req.name.removeSuffix(".req")
                        val reply = answer ?: continue
                        // The script writes it in place; the launcher waits for it to be closed, this for its last NUL.
                        val text = req.readText()
                        if (!text.endsWith('\u0000')) continue
                        val args = text.removeSuffix("\u0000").split('\u0000')
                        req.delete()
                        received = args
                        File(inbox, "$id.ack").createNewFile()
                        Thread {
                            Thread.sleep(answerAfterMs)
                            File(inbox, ".$id.tmp").writeText(reply(args) + "\n")
                            File(inbox, ".$id.tmp").renameTo(File(inbox, "$id.done"))
                        }.apply { isDaemon = true }.start()
                    }
                    Thread.sleep(20)
                }
            }.apply {
                isDaemon = true
                start()
            }
    }

    @After fun tearDown() {
        running = false
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private class Run(
        val code: Int,
        val out: String,
        val err: String,
    )

    private fun run(vararg args: String): Run {
        val p =
            ProcessBuilder(listOf(BASH!!, script.absolutePath) + args)
                .directory(dir)
                .apply { environment()["OMALAUNCHER_INBOX"] = inbox.path }
                .start()
        assertTrue("omalauncher ${args.joinToString(" ")} hung", p.waitFor(30, TimeUnit.SECONDS))
        return Run(p.exitValue(), p.inputStream.bufferedReader().readText(), p.errorStream.bufferedReader().readText())
    }

    @Test fun helpGoesToStdoutAndExitsZero() {
        for (h in listOf("--help", "-h", "help")) {
            val r = run(h)
            assertEquals(h, 0, r.code)
            assertTrue(h, r.out.startsWith("usage: omalauncher"))
        }
        assertEquals(0, run("notify", "--help").code)
        assertEquals(null, received)
    }

    @Test fun wrongArgumentsGoToStderrAndExitTwo() {
        for (args in listOf(arrayOf(), arrayOf("nope"), arrayOf("battery", "x"), arrayOf("notify"), arrayOf("notify", ""), arrayOf("share"), arrayOf("share", "missing.txt"))) {
            val r = run(*args)
            assertEquals(args.joinToString(" "), 2, r.code)
            assertEquals("", r.out)
            assertTrue(r.err.isNotEmpty())
        }
        assertEquals(null, received)
    }

    @Test fun optionsAreRefusedUntilThereAreSome() {
        val r = run("notify", "--silent", "done")
        assertEquals(2, r.code)
        assertTrue(r.err, "--" in r.err)
        assertEquals(null, received)
    }

    @Test fun doubleDashPassesWordsThatStartWithADash() {
        assertEquals(0, run("notify", "--", "-5 degrees", "--out").code)
        assertEquals(listOf("notify", "-5 degrees", "--out"), received)
    }

    @Test fun textSpansLines() {
        assertEquals(0, run("notify", "build", "line one\nline two").code)
        assertEquals(listOf("notify", "build", "line one\nline two"), received)
    }

    @Test fun versionFlagIsTheVersionCommand() {
        answer = { "ok\n{\"version\":\"1\"}" }
        val r = run("--version")
        assertEquals(0, r.code)
        assertEquals("{\"version\":\"1\"}\n", r.out)
        assertEquals(listOf("version"), received)
    }

    @Test fun shareSendsAbsolutePaths() {
        File(dir, "a.txt").writeText("a")
        File(dir, "-b.txt").writeText("b")
        assertEquals(0, run("share", "a.txt", "--", "-b.txt", "/etc/hosts").code)
        assertEquals(listOf("share", File(dir, "a.txt").path, File(dir, "-b.txt").path, "/etc/hosts"), received)
    }

    /** Linux paths go absolute, phone folder paths as given. */
    @Test fun folderCopiesSendLinuxPathsAbsolute() {
        File(dir, "a.txt").writeText("a")
        File(dir, "sub").mkdir()
        assertEquals(0, run("get", "Camera/a.jpg", "Camera/b.jpg", "sub").code)
        assertEquals(listOf("get", "Camera/a.jpg", "Camera/b.jpg", File(dir, "sub").path), received)
        assertEquals(0, run("put", "a.txt", "sub", "/etc/hosts", "Documents/notes").code)
        assertEquals(listOf("put", File(dir, "a.txt").path, File(dir, "sub").path, "/etc/hosts", "Documents/notes"), received)
        assertEquals(0, run("pick", "sub").code)
        assertEquals(listOf("pick", File(dir, "sub").path), received)
        assertEquals(0, run("pick").code)
        assertEquals(listOf("pick"), received)
        assertEquals(0, run("ls", "Camera").code)
        assertEquals(listOf("ls", "Camera"), received)
    }

    @Test fun folderCopiesCheckTheirArguments() {
        received = null
        for (args in listOf(arrayOf("get", "Camera/a.jpg"), arrayOf("put", "a.txt"), arrayOf("put", "missing.txt", "Documents"), arrayOf("folders", "x"), arrayOf("pick", "a", "b"))) {
            assertEquals(args.joinToString(" "), 2, run(*args).code)
        }
        assertEquals("nothing went to the launcher", null, received)
    }

    @Test fun answersBecomeExitCodes() {
        for ((reply, code) in listOf("ok\n[1,2]" to 0, "usage: days: 1 to 366" to 2, "denied: not allowed" to 3, "error: no AirPods connected" to 1)) {
            answer = { reply }
            val r = run("calendar")
            assertEquals(reply, code, r.code)
            if (code == 0) assertEquals("[1,2]\n", r.out) else assertEquals("omalauncher: ${reply.substringAfter(": ")}\n", r.err)
        }
        answer = { "ok" }
        assertEquals("", run("notify", "hi").out)
    }

    @Test fun noInboxExitsFour() {
        inbox.deleteRecursively()
        assertEquals(4, run("battery").code)
    }

    @Test fun aRequestNobodyTakesExitsFourSoon() {
        answer = null
        val start = System.nanoTime()
        val r = run("battery")
        assertEquals(4, r.code)
        assertTrue(r.err, "didn't take" in r.err)
        assertTrue(System.nanoTime() - start < 15_000_000_000)
        assertEquals("the request is taken back", 0, inbox.listFiles().orEmpty().size)
    }

    /** Past the 5 s a request has to be taken in: once acked, a slow answer is still waited for. */
    @Test fun anAckedRequestIsWaitedForLonger() {
        answerAfterMs = 7_000
        answer = { "ok\n{\"level\":50}" }
        val r = run("battery")
        assertEquals(r.err, 0, r.code)
        assertEquals(0, inbox.listFiles().orEmpty().size)
    }

    /** `omalauncher version` lists what the launcher answers: the script, its usage and the launcher agree on it. */
    @Test fun commandsAgree() {
        val bridge = File("src/main/kotlin/dev/omalauncher/linux/PhoneBridge.kt").readText()
        val listed = Regex("""val COMMANDS = listOf\((.*?)\)""").find(bridge)!!.groupValues[1].let { l -> Regex("\"(\\w+)\"").findAll(l).map { it.groupValues[1] }.toSet() }
        val answered = Regex("""^\s+"(\w+)" -> """, RegexOption.MULTILINE).findAll(bridge.substringAfter("when (args[0])").substringBefore("else ->")).map { it.groupValues[1] }.toSet()
        val text = script.readText()
        // The command's case: the last at the top level, ended by the first esac there.
        val cases = text.substring(text.lastIndexOf("\ncase \$1 in\n")).substringBefore("\nesac")
        val cased = Regex("""^  ([\w |]+)\)""", RegexOption.MULTILINE).findAll(cases).flatMap { it.groupValues[1].split('|').map(String::trim) }.toSet()
        val usage = Regex("""omalauncher (\w+)""").findAll(text.substringAfter("<<USAGE").substringBefore("\nUSAGE")).map { it.groupValues[1] }.toSet()
        assertEquals("answered by PhoneBridge", listed, answered)
        assertEquals("taken by the script", listed, cased - "*")
        assertEquals("in the usage", listed, usage)
    }

    private companion object {
        val BASH =
            listOf("bash", "/opt/local/bin/bash", "/opt/homebrew/bin/bash", "/usr/local/bin/bash", "/usr/bin/bash", "/bin/bash").firstOrNull { bash ->
                runCatching {
                    val p = ProcessBuilder(bash, "-c", "[ \"\${BASH_VERSINFO[0]}\" -ge 5 ]").start()
                    p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
                }.getOrDefault(false)
            }
    }
}
