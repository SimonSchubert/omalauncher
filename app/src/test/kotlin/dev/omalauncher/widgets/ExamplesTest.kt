package dev.omalauncher.widgets

import dev.omalauncher.widgets.lang.Builtins
import dev.omalauncher.widgets.lang.CompileError
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.OwlKind
import dev.omalauncher.widgets.lang.Source
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
