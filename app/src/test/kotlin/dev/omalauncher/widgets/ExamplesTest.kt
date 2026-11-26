package dev.omalauncher.widgets

import dev.omalauncher.widgets.lang.Budget
import dev.omalauncher.widgets.lang.Builtins
import dev.omalauncher.widgets.lang.CompileError
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Env
import dev.omalauncher.widgets.lang.Host
import dev.omalauncher.widgets.lang.OwlKind
import dev.omalauncher.widgets.lang.Source
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ExamplesTest {
    private val examples = File("src/main/assets/widgets").walkTopDown().filter { it.extension == "owl" }.sortedBy { it.name }.toList()

    private fun compile(
        name: String,
        src: String,
    ) = try {
        Compiler.compile(src)
    } catch (e: CompileError) {
        fail("$name\n${Source(src).format(e)}\n\n$src")
        throw e
    }

    @Test fun everyExampleCompiles() {
        assertTrue("no examples found", examples.size >= 8)
        for (f in examples) compile(f.name, f.readText())
    }

    /** The phone app's keypad pastes the first number in what was copied, written any way, and leaves the keypad be without one. */
    @Test fun phoneKeypadPastesANumber() {
        val src = File("src/main/assets/widgets/examples/phone.owl").readText()
        val start = src.indexOf("onLongPress: {\n          clip = paste()")
        assertTrue("phone.owl's keypad has no paste", start >= 0)
        val handler = src.substring(start + "onLongPress: ".length, src.indexOf("\n        }\n", start) + "\n        }".length)
        val p = compile("paste", "widget {\n meta { owl: 26 }\n state digits = \"12\"\n state clip = \"\"\n onTap: $handler\n Text { text: digits }\n}")
        for ((copied, digits) in listOf(
            "+420 777 123 456" to "+420777123456",
            // Web pages' hard and thin spaces, and their hyphens.
            "+420\u00a0777\u00a0123\u00a0456" to "+420777123456",
            "777\u2011123\u2011456" to "777123456",
            "777\u2013123\u2013456" to "777123456",
            "call me at (777) 123-456 tomorrow" to "777123456",
            // The number, not the first digit in the text.
            "Jan Novák, Dlouhá 5, 110 00 Praha, +420 777 123 456" to "+420777123456",
            "Meeting at 14:30, call 777123456" to "777123456",
            "112" to "112",
            // Too long for the keypad: it isn't cut into a different number to call.
            "1234 5678 9012 3456 7890 1234 5678" to "12",
            "00420777123456" to "+420777123456",
            "*#06#" to "*#06#",
            "no number here" to "12",
            "" to "12",
        )) {
            val slots = arrayOf<Value>(Str("12"), Str(""))
            val host =
                object : Host {
                    override val budget = Budget()

                    override fun read(slot: Int) = slots[slot]

                    override fun write(
                        slot: Int,
                        value: Value,
                    ) {
                        slots[slot] = value
                    }

                    override fun theme(name: String): Int? = null

                    override fun time() = 0.0

                    override fun act(
                        name: String,
                        args: List<Value>,
                    ) {}

                    override fun pasteInto(slot: Int) = write(slot, Str(copied))
                }
            p.onTap!!.run(Env.root(host), emptyList())
            assertEquals(copied, Str(digits), slots[0])
            assertEquals("the clipboard isn't kept", Str(""), slots[1])
        }
    }

    /** The store's built-in agent tiles. */
    @Test fun agentWidgetsCompile() {
        for (agent in dev.omalauncher.linux.AGENTS) {
            val p = compile(agent.bin, agentWidget(agent))
            assertEquals(listOf("cd ~ && ${agent.command}; exec bash"), p.commands)
        }
    }

    /** What agents learn the language from: the README and the docs it points to. */
    private val readme = File("src/main/assets/widgets/README.md")
    private val docs = File("src/main/assets/widgets/docs").listFiles { f -> f.extension == "md" }.orEmpty().sortedBy { it.name }

    /** The docs' samples must compile. */
    @Test fun readmeSamplesCompile() {
        var count = 0
        for (f in listOf(readme) + docs) {
            for (m in Regex("```owl\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(f.readText())) {
                val b = m.groupValues[1]
                val src = if (OwlKind.entries.any { b.trimStart().startsWith(it.word + " ") }) b else "widget {\n  state load = 42\n  state cpu = 1\n  state mem = 2\n$b\n}"
                compile("sample in ${f.name}", src)
                count++
            }
        }
        assertTrue(count >= 20)
    }

    /** Agents only use what the docs name: every built-in and handler action is in one of their code spans. */
    @Test fun readmeNamesEveryBuiltinAndAction() {
        val prose = (listOf(readme) + docs).joinToString("\n") { it.readText() }.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), "")
        val spans = Regex("`([^`]+)`").findAll(prose).map { it.groupValues[1] }.toList()
        val words = spans.flatMap { Regex("[A-Za-z_][A-Za-z0-9_.]*").findAll(it).map { m -> m.value } }.toSet()
        val missing = (Builtins.all.keys + Builtins.actions.keys).filter { it !in words }
        assertTrue("not in the docs: $missing", missing.isEmpty())
    }

    /** The README's index lists every docs file, and every docs/x.md named anywhere is one. */
    @Test fun docsAreIndexed() {
        assertTrue(docs.size >= 10)
        val index = readme.readText().substringAfter("## The docs\n").substringBefore("\n## ")
        val unlisted = docs.map { "docs/${it.name}" }.filter { "| $it |" !in index }
        assertTrue("not in the README's docs table: $unlisted", unlisted.isEmpty())
        val named = (listOf(readme) + docs).flatMap { f -> Regex("""docs/[\w-]+\.md""").findAll(f.readText()).map { f.name to it.value } }
        val missing = named.filter { (_, path) -> !File("src/main/assets/widgets/$path").exists() }
        assertTrue("named but not there: $missing", missing.isEmpty())
    }

    /** The README's Examples table points agents at files; each must be one this app ships. */
    @Test fun readmeExamplesExist() {
        val section = readme.readText().substringAfter("## Examples\n").substringBefore("\n## ")
        val named = Regex("`((?:examples/)?[\\w-]+?)(?:\\.owl)?`").findAll(section).map { it.groupValues[1] }.filter { it.first().isDigit() || "/" in it || it.startsWith("drawer") || it.startsWith("keybar") }.toList()
        assertTrue(named.size >= 20)
        val missing = named.filter { !File("src/main/assets/widgets/$it.owl").exists() }
        assertTrue("not in assets/widgets: $missing", missing.isEmpty())
    }

    @Test fun examplesListTheirCommands() {
        val p = Compiler.compile(examples.first { it.name == "20-system.owl" }.readText())
        assertEquals(listOf("free -b | awk '/Mem:/ {print \$2, \$3}'", "btop"), p.commands)
    }

    /** The fetch tile shows the phone without Linux; its Arch lines wait for it. */
    @Test fun fetchWorksWithoutLinux() {
        val p = Compiler.compile(examples.first { it.name == "20-fetch.owl" }.readText())
        assertTrue(!p.needsLinux)
        assertTrue(p.commands.first().startsWith(Compiler.IF_LINUX))
    }
}
