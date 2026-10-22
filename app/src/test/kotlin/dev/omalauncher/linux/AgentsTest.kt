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
            "if [ -z \"\$(mise ls -g node)\" ] && [ -n \"\$(mise ls -g 'opencode' 'npm:@xai-official/grok' 'npm:@moonshot-ai/kimi-code')\" ]; " +
                "then mise use -g node@lts; fi",
            MISE_NODE_FOR_AGENTS,
        )
    }
}
