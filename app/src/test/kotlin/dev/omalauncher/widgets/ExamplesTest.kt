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

    /** The README is what agents learn the language from; its samples must compile. */
    @Test fun readmeSamplesCompile() {
        val blocks = Regex("```owl\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(File("src/main/assets/widgets/README.md").readText()).map { it.groupValues[1] }.toList()
        assertTrue(blocks.size >= 4)
        for (b in blocks) {
            val src = if (OwlKind.entries.any { b.trimStart().startsWith(it.word + " ") }) b else "widget {\n  state load = 42\n  state cpu = 1\n  state mem = 2\n$b\n}"
            compile("README sample", src)
        }
    }

    /** Agents only use what the README names: every built-in and handler action is in one of its code spans. */
    @Test fun readmeNamesEveryBuiltinAndAction() {
        val prose = File("src/main/assets/widgets/README.md").readText().replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), "")
        val spans = Regex("`([^`]+)`").findAll(prose).map { it.groupValues[1] }.toList()
        val words = spans.flatMap { Regex("[A-Za-z_][A-Za-z0-9_.]*").findAll(it).map { m -> m.value } }.toSet()
        val missing = (Builtins.all.keys + Builtins.actions.keys).filter { it !in words }
        assertTrue("not in the README: $missing", missing.isEmpty())
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
