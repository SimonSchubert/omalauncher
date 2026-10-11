package dev.omalauncher.linux

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** A coding agent CLI, installed the way Omarchy's omarchy-default-agent does it: via mise. */
data class Agent(
    val bin: String,
    val name: String,
    val description: String,
    /** mise package spec. */
    val pkg: String,
) {
    /** Its activity alias in the manifest (cursor-agent -> CursorAgentWindow), so Recents names its window after it. */
    val windowAlias get() = bin.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Window"
}

/** Omarchy's agent list (omarchy-default-agent), limited to ones mise can install on arm64. */
val AGENTS =
    listOf(
        Agent("claude", "Claude Code", "Anthropic's coding agent", "claude"),
        Agent("opencode", "OpenCode", "Open source agent, any model provider", "opencode"),
        Agent("codex", "Codex", "OpenAI's coding agent", "codex"),
        Agent("grok", "Grok", "xAI's coding agent", "npm:@xai-official/grok"),
        Agent("copilot", "GitHub Copilot", "GitHub's coding agent", "copilot"),
        Agent("crush", "Crush", "Charm's agent for the terminal", "crush"),
        Agent("cursor-agent", "Cursor CLI", "Cursor's agent for the terminal", "cursor-agent"),
        Agent("agy", "Antigravity", "Google's Gemini coding agent", "antigravity-cli"),
        Agent("pi", "Pi", "Minimal, extensible coding agent", "pi"),
    )

/**
 * Starts [agentBin] with a first prompt; OpenCode takes it as --prompt, the others as an argument.
 * [readOnly], for reading something untrusted: Claude Code starts in plan mode, where it can read but not
 * run commands or change files whatever the user's settings say. The others have no flag for that we can
 * rely on inside proot, and only have the prompt.
 */
fun agentInvocation(
    agentBin: String,
    prompt: String,
    readOnly: Boolean = false,
): String {
    val quoted = shellQuote(prompt)
    return when {
        agentBin == "opencode" -> "$agentBin --prompt $quoted"
        agentBin == "claude" && readOnly -> "$agentBin --permission-mode plan $quoted"
        else -> "$agentBin $quoted"
    }
}

/** A new-app session in ~/apps: the agent asks what to build, then adds it to the app drawer. */
fun newAppCommand(agentBin: String): String {
    val prompt =
        "Ask me what app to build, then build it in its own directory here. It runs on Arch Linux ARM (aarch64) inside proot " +
            "on an Android phone, under a Wayland compositor: no systemd, no X11, OpenGL usually in software, a small touch screen. " +
            "Install what it needs with pacman. When it works, add a desktop entry at ~/.local/share/applications/<name>.desktop " +
            "(Terminal=true for terminal apps) so it shows up in my app drawer."
    return "mkdir -p ~/apps && cd ~/apps && ${agentInvocation(agentBin, prompt)}; exec bash"
}

class AgentStore(
    private val proot: Proot,
    /** Called when the set of installed agents changed, so their desktop entries follow. */
    private val onChanged: () -> Unit,
) {
    private val mise get() = File(proot.home, ".local/bin/mise")
    private val shims get() = File(proot.home, ".local/share/mise/shims")

    val states = mutableStateMapOf<String, JobState>()

    /** Shims are symlinks to a guest path (/root/.local/bin/mise), so don't follow them from the host. */
    fun isInstalled(agent: Agent) = Files.exists(File(shims, agent.bin).toPath(), LinkOption.NOFOLLOW_LINKS)

    /** The agent picked in the prompt bar, kept in the guest home where scripts can read it too. */
    private val preferredFile get() = File(proot.launcherConfig, "agent")
    private var preferred by mutableStateOf<String?>(null)

    /** Reads what's on disk; call off the main thread before the UI needs [default]. */
    fun load() {
        preferred = runCatching { preferredFile.readText().trim() }.getOrNull()
        refresh()
    }

    /** Installed agents in Omarchy's order; re-read from disk after installs and removals. */
    var installed by mutableStateOf<List<Agent>>(emptyList())
        private set

    private fun refresh() {
        installed = AGENTS.filter { isInstalled(it) }
    }

    /** The agent the launcher opens for prompts and widget work: the picked one, else the first installed in Omarchy's order. */
    val default: Agent? get() = installed.firstOrNull { it.bin == preferred } ?: installed.firstOrNull()

    fun choose(agent: Agent) {
        preferred = agent.bin
        runCatching {
            preferredFile.parentFile?.mkdirs()
            preferredFile.writeText(agent.bin + "\n")
        }.onFailure { Log.w("agents", "cannot save the default agent", it) }
    }

    suspend fun install(agent: Agent) =
        run(agent, "installing") { installScript(agent) }.also { ok -> if (ok) onInstalled(agent) }

    /** Installs [agent] with mise, bootstrapping mise itself if needed. */
    fun installScript(agent: Agent) =
        buildString {
            if (!mise.exists()) append("curl -fsSL https://mise.run | sh && ")
            if (agent.pkg.startsWith("npm:")) append("mise use -g node@lts && ")
            append("mise use -g ${shellQuote(agent.pkg)}")
            // Downloads are only needed during install; storage on phones is tight.
            append(" && mise cache clear && rm -rf ~/.local/share/mise/downloads")
        }

    /** Call after [installScript] succeeded: makes the agent show up in the drawer. */
    fun onInstalled(agent: Agent) {
        refresh()
        onChanged()
    }

    suspend fun remove(agent: Agent) =
        run(agent, "removing") { "mise unuse -g ${shellQuote(agent.pkg)} && mise prune -y" }
            .also {
                refresh()
                onChanged()
            }

    /** Drawer entries for the installed agents, with their logos (see [bundledIcons]); each opens in a terminal in the home directory. */
    fun managed(): List<Managed> =
        installed.map { agent ->
            Managed.text(File(proot.applications, "agent-${agent.bin}.desktop"), Policy.Owned) {
                "[Desktop Entry]\nType=Application\nName=${agent.name}\nTerminal=true\nExec=${agent.bin}\nIcon=${bundledIcon("agent-${agent.bin}")}\n"
            }
        } + codexConfig()

    /**
     * Codex starts a shared background server and reads its start time with `ps`; when that fails, Codex
     * refuses to start ("failed to read start time for pid-managed app server", rerun with --no-daemon).
     * Proot kills that server with its window anyway, so don't start it. A system layer, so the user's
     * ~/.codex/config.toml stays theirs and can turn it back on.
     */
    private fun codexConfig() =
        if (installed.none { it.bin == "codex" }) {
            emptyList()
        } else {
            listOf(
                Managed.text(File(proot.rootfs, "etc/codex/config.toml"), Policy.Owned) {
                    "# Written by the launcher.\n[features]\ndaemon_auto_start = false\n"
                },
            )
        }

    private suspend fun run(
        agent: Agent,
        action: String,
        script: () -> String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            states[agent.bin] = JobState.Working(action, emptyList())
            val (ok, tail) = proot.runJob(script()) { states[agent.bin] = JobState.Working(action, it) }
            Log.i("agents", "$action ${agent.bin}: ${if (ok) "ok" else "failed"}")
            states[agent.bin] = if (ok) JobState.Idle else JobState.Failed(tail.toList())
            ok
        }
}
