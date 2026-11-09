package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AgentsTest {
    /** A missing alias would make opening that agent's window throw. */
    @Test fun everyAgentHasAWindowAlias() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val aliases = Regex("""<activity-alias(.*?)/>""", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.groupValues[1] }.toList()
        for (agent in AGENTS) {
            val alias = aliases.firstOrNull { "android:name=\".${agent.windowAlias}\"" in it }
            assertTrue("${agent.bin}: no .${agent.windowAlias} in the manifest", alias != null)
            assertTrue("${agent.bin}: not labelled \"${agent.name}\"", "android:label=\"${agent.name}\"" in alias!!)
            assertTrue("${agent.bin}: no logo", "android:icon=\"@drawable/agent_${agent.bin.replace('-', '_')}\"" in alias)
        }
    }

    @Test fun copilotReceivesAnInteractivePromptAsOneLiteralArgument() {
        val prompt = "Explain \"it's quoted\"; \$(exit 17)\nand keep \$HOME literal."
        val script = """
            set -- ${agentInvocation("copilot", prompt)}
            test "${'$'}#" -eq 3 &&
            test "${'$'}1" = copilot &&
            test "${'$'}2" = -i &&
            test "${'$'}3" = "${'$'}EXPECTED_PROMPT"
        """.trimIndent()
        val process = ProcessBuilder("sh", "-c", script)
            .redirectErrorStream(true)
            .apply { environment()["EXPECTED_PROMPT"] = prompt }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
    }

    /** A prompt from a widget can't pass the agent an option: claude --settings=... would run its hooks. */
    @Test fun aPromptStartingWithADashIsNoOption() {
        val script = """
            set -- ${agentInvocation("claude", "--settings={}")}
            test "${'$'}#" -eq 2 &&
            test "${'$'}2" = " --settings={}"
        """.trimIndent()
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
    }

    /** Prune deletes what `mise ls --prunable` lists, only under mise's installs, then lets mise tidy up. */
    @Test fun pruneDeletesPrunableVersionsItself() {
        val dir = createTempDirectory("prune").toFile()
        val old = File(dir, "mise/installs/opencode/2.1.0").apply { mkdirs() }
        val kept = File(dir, "mise/installs/opencode/2.1.2").apply { mkdirs() }
        val outside = File(dir, "elsewhere").apply { mkdirs() }
        val stub = File(dir, "bin/mise").apply { parentFile.mkdirs() }
        stub.writeText(
            """
            #!/bin/sh
            if [ "${'$'}1" = ls ]; then
              printf '{\n  "opencode": [\n    {\n      "version": "2.1.0",\n      "install_path": "%s",\n      "installed": true\n    },\n' "${old.path}"
              printf '    {\n      "install_path": "%s"\n    }\n  ]\n}\n' "${outside.path}"
            else
              echo "${'$'}*" >"${dir.path}/pruned"
            fi
            """.trimIndent(),
        )
        stub.setExecutable(true)
        val process = ProcessBuilder("sh", "-c", MISE_PRUNE).redirectErrorStream(true)
        process.environment()["PATH"] = "${stub.parent}:${System.getenv("PATH")}"
        val p = process.start()
        val output = p.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, p.waitFor())
        assertTrue("old version left", !old.exists())
        assertTrue("current version deleted", kept.exists())
        assertTrue("deleted outside mise's installs", outside.exists())
        assertEquals("prune -y\n", File(dir, "pruned").readText())
        dir.deleteRecursively()
    }

    /** OpenCode installs from before 2 have no node, and mise needs it for OpenCode 2's postinstall. */
    @Test fun updatesAddNodeForOpencode() {
        assertEquals(
            "if [ -z \"\$(mise ls -g node)\" ] && [ -n \"\$(mise ls -g 'opencode' 'npm:@xai-official/grok' 'npm:@moonshot-ai/kimi-code' 'qwen' " +
                "'npm:@minimax-ai/code[allow_builds=better-sqlite3]' 'npm:@kilocode/cli' 'amp[prerelease=true]' 'npm:command-code')\" ]; " +
                "then mise use -g node@lts; fi",
            MISE_NODE_FOR_AGENTS,
        )
    }

    /** Omarchy 4.5's agents get the prompt whole, however it's quoted, in the form each one takes. */
    @Test fun newAgentsReceiveThePromptLiterally() {
        val prompt = "Explain \"it's quoted\"; \$(exit 17)\nand keep \$HOME literal."
        val expected =
            mapOf(
                "qwen" to listOf("qwen", "--prompt-interactive=$prompt"),
                "kilo" to listOf("kilo", "--prompt=$prompt"),
                "kiro-cli" to listOf("kiro-cli", "chat", "--", prompt),
                "mcode" to listOf("mcode", "--", prompt),
                "devin" to listOf("devin", "--", prompt),
                "commandcode" to listOf("commandcode", "--", prompt),
            )
        val dir = createTempDirectory("agents").toFile()
        fun stub(
            bin: String,
            body: String,
        ) = File(dir, bin).apply {
            writeText("#!/bin/sh\n$body\n")
            setExecutable(true)
        }

        fun run(command: String): String {
            val pb = ProcessBuilder("sh", "-c", command)
            pb.environment()["PATH"] = "${dir.path}:${System.getenv("PATH")}"
            val p = pb.start()
            return p.inputStream.bufferedReader().use { it.readText() }.also { assertEquals(command, 0, p.waitFor()) }
        }
        for ((bin, args) in expected) {
            // Each agent prints its name and arguments, one per NUL-terminated field.
            stub(bin, "printf '%s\\0' \"${'$'}(basename \"${'$'}0\")\" \"${'$'}@\"")
            assertEquals(bin, args, run(agentInvocation(bin, prompt)).split('\u0000').dropLast(1))
        }
        // Amp reads its first message from stdin, then stays interactive.
        stub("amp", "cat")
        assertEquals(prompt + "\n", run(agentInvocation("amp", prompt)))
        dir.deleteRecursively()
    }
}
