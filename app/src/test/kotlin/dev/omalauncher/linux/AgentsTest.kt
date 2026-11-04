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

    /**
     * Hermes takes its first prompt as `chat -q <prompt>`: a bare argument starts its TUI instead, and
     * `-q` is what answers once and exits, which is what the bar and the widget engine read.
     */
    @Test fun hermesReceivesItsPromptThroughChatQuery() {
        val prompt = "Explain \"it's quoted\"; \$(exit 17)\nand keep \$HOME literal."
        val script = """
            set -- ${agentInvocation("hermes", prompt)}
            test "${'$'}#" -eq 4 &&
            test "${'$'}1" = hermes &&
            test "${'$'}2" = chat &&
            test "${'$'}3" = -q &&
            test "${'$'}4" = "${'$'}EXPECTED_PROMPT"
        """.trimIndent()
        val process = ProcessBuilder("sh", "-c", script)
            .redirectErrorStream(true)
            .apply { environment()["EXPECTED_PROMPT"] = prompt }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
    }

    /**
     * The Hermes install command puts uv's link mode where pm still reads it. pm runs uv with
     * XDG_CONFIG_HOME and XDG_CONFIG_DIRS on an empty directory, so ~/.config/uv/uv.toml is invisible to it
     * and /etc/uv/uv.toml is the file that survives; an exported UV_LINK_MODE never arrives either. The path
     * is rewritten to a temp dir here because /etc needs root — the rest of the command runs as it ships,
     * with curl stubbed so the installer's own arguments can be read back.
     */
    @Test fun hermesWritesTheLinkModePmStillReads() {
        val sandbox = createTempDirectory("hermes-install").toFile()
        val stubDir = File(sandbox, "bin").apply { mkdirs() }
        val recorded = File(sandbox, "installer-args")
        File(stubDir, "curl").apply {
            // The installer script arrives as curl's output on the pipe: this stands in for it with a
            // script that records what `bash -s --` handed over.
            writeText(
                """
                #!/bin/sh
                cat <<'SCRIPT'
                printf '%s\n' "${'$'}@" > "${'$'}RECORDED"
                SCRIPT
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
        val install = AGENTS.single { it.bin == "hermes" }.install!!
        assertTrue("the install must write the file pm reads: $install", "mkdir -p /etc/uv &&" in install)
        val command = install.replace("/etc/uv", "${sandbox.path}/etc/uv")
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .apply {
                environment()["PATH"] = "${stubDir.path}:" + environment()["PATH"]
                environment()["RECORDED"] = recorded.path
            }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
        assertEquals("link-mode = \"copy\"", File(sandbox, "etc/uv/uv.toml").readText().trim())
        assertEquals(
            listOf("--non-interactive", "--skip-browser", "--skip-computer-use"),
            recorded.readLines(),
        )
    }

    /**
     * A failed self-install leaves its command behind, and the bar must not offer what cannot answer. Hermes
     * writes ~/.local/bin/hermes and its checkout first and its dependency sync last, so a runtime directory
     * without `.hermes-bootstrap-complete` is an install that stopped partway; a command with no runtime
     * beside it is the user's own wrapper to another machine, which counts whatever the runtime looks like.
     */
    @Test fun aRuntimeWithoutItsDoneFileIsNotAnInstall() {
        val home = createTempDirectory("agents").toFile()
        val hermes = AGENTS.single { it.bin == "hermes" }
        val claude = AGENTS.first { it.bin == "claude" }
        File(home, ".local/bin").mkdirs()
        File(home, ".local/bin/hermes").writeText("#!/bin/sh\nexec ssh -t vps hermes chat -q \"\$*\"\n")
        File(home, ".local/bin/claude").writeText("#!/bin/sh\n")
        assertTrue("a command alone is the user's", installedByHand(home, hermes))
        File(home, ".hermes/hermes-agent").mkdirs()
        assertTrue("a runtime without the done file is a failed install", !installedByHand(home, hermes))
        assertTrue("an agent without a done file needs only its command", installedByHand(home, claude))
        File(home, hermes.done!!).writeText("")
        assertTrue("a finished runtime is an install", installedByHand(home, hermes))
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
