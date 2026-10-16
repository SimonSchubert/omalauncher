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

/**
 * A coding agent CLI, installed the way Omarchy's omarchy-default-agent does it: via mise.
 * An agent mise cannot install (Hermes) brings its own [install] command instead.
 */
data class Agent(
    val bin: String,
    val name: String,
    val description: String,
    /** mise package spec; for an agent with its own [install] it is only an identifier. */
    val pkg: String,
    /**
     * The agent's own installer, run in the guest home. Nothing here overwrites a copy of
     * [bin] the user put in ~/.local/bin, so a self-installed agent, or one pointed at
     * another machine, is left alone (see [AgentStore.isInstalled]).
     */
    val install: String? = null,
    /** What starts it without a prompt, when that's more than [bin]. */
    val command: String = bin,
) {
    /** Its activity alias in the manifest (cursor-agent -> CursorAgentWindow), so Recents names its window after it. */
    val windowAlias get() = bin.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Window"
}

/**
 * Omarchy's agent list (omarchy-default-agent), limited to ones mise can install on arm64,
 * plus Hermes, which Omarchy installs with its own installer rather than mise.
 */
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
        Agent(
            "hermes",
            "Hermes Agent",
            "Nous Research's agent; on this phone or another machine",
            "hermes",
            // uv hardlinks wheels out of its cache, and the guest runs under proot's
            // --link2symlink with /tmp bound elsewhere, so those links fail; copy instead.
            // The install command's UV_LINK_MODE=copy only reaches the install: at runtime
            // `hermes chat -q` re-runs uv without it and dies ("Operation not permitted",
            // os error 1, on a hardlink out of ~/.hermes/cache/uv/archive-v0). Write uv.toml
            // so every later uv run — chat, update, doctor — copies too.
            //
            // HERMES_REPO_URL points the clone at the DeseretSaint fork, which carries the
            // proot fixes upstream lacks: pm forwards UV_LINK_MODE into its runtime sync
            // (otherwise the pm-runtime `uv sync` hardlinks and dies under --link2symlink),
            // and install.sh recovers a commit-graph-corrupt checkout with `git fetch
            // --refetch`. The installer script still comes from the upstream CDN; only the
            // repo it clones is redirected. Set HERMES_REPO_URL="" to install vanilla upstream.
            install = "mkdir -p ~/.config/uv && printf '%s\\n' " +
                "'link-mode = \"copy\"' > ~/.config/uv/uv.toml && " +
                "curl -fsSL https://hermes-agent.nousresearch.com/install.sh | " +
                "env UV_LINK_MODE=copy " +
                "HERMES_REPO_URL=https://github.com/DeseretSaint/hermes-agent.git " +
                "bash -s -- --non-interactive --skip-browser --skip-computer-use",
        ),
        Agent("omp", "Oh My Pi", "Pi with tools and plugins built in", "github:can1357/oh-my-pi"),
        Agent("ori", "Ori", "OpenRouter's coding agent", "github:OpenRouterLabs/ori-releases", command = "ori code"),
        Agent(
            "muse",
            "Muse Code",
            "Meta's coding agent",
            // Meta's launcher fetches and updates the native binary for this platform.
            "http:muse[url=https://api.meta.ai/muse-launcher.sh,bin=muse,version_list_url=https://api.meta.ai/muse-code/channels/muse-stable,version_json_path=.version]",
        ),
        Agent("kimi", "Kimi", "Moonshot AI's coding agent", "npm:@moonshot-ai/kimi-code"),
    )

/**
 * Starts [agentBin] with a first prompt; OpenCode takes it as --prompt, Hermes as `chat -q`,
 * the others as an argument.
 * Ori's agent is `ori code`, which stays open only with --interactive. Kimi takes a prompt only in
 * print mode, so the session it ran in is continued after the answer.
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
        agentBin == "hermes" -> "$agentBin chat -q $quoted"
        agentBin == "claude" && readOnly -> "$agentBin --permission-mode plan $quoted"
        agentBin == "ori" -> "ori code --interactive --prompt $quoted"
        agentBin == "kimi" -> "kimi --prompt $quoted && kimi --continue"
        else -> "$agentBin $quoted"
    }
}

class AgentStore(
    private val proot: Proot,
    /** Called when the set of installed agents changed, so their desktop entries follow. */
    private val onChanged: () -> Unit,
) {
    private val mise get() = File(proot.home, ".local/bin/mise")
    private val shims get() = File(proot.home, ".local/share/mise/shims")

    /** Where an agent's own installer, and the user's own agent command, put [Agent.bin]. */
    private val localBin get() = File(proot.home, ".local/bin")

    val states = mutableStateMapOf<String, JobState>()

    /**
     * Shims are symlinks to a guest path (/root/.local/bin/mise), so don't follow them from the host.
     *
     * A mise shim means this screen installed it. An executable at ~/.local/bin/[Agent.bin] is the
     * user's: a self-installing agent, or a script that reaches an agent somewhere else (another
     * machine, a container). Count it as installed so the bar can pick it, and never write over it.
     */
    fun isInstalled(agent: Agent) =
        Files.exists(File(shims, agent.bin).toPath(), LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(File(localBin, agent.bin).toPath(), LinkOption.NOFOLLOW_LINKS)

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

    /** Installs [agent] with its own installer, or with mise (bootstrapping mise if needed). */
    fun installScript(agent: Agent) =
        agent.install
            ?: buildString {
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

    /**
     * Removes an agent this screen installed. An agent with its own installer is left where it is:
     * its command in ~/.local/bin may be the user's own, so the launcher only says so.
     */
    suspend fun remove(agent: Agent) =
        run(agent, "removing") {
            if (agent.install != null) {
                "echo '${agent.name} is installed outside the launcher (or by hand); " +
                    "remove it where it lives: ~/.local/bin/${agent.bin}'"
            } else {
                "mise unuse -g ${shellQuote(agent.pkg)} && mise prune -y"
            }
        }
            .also {
                refresh()
                onChanged()
            }

    /** Drawer entries for the installed agents, with their logos (see [bundledIcons]); each opens in a terminal in the home directory. */
    fun managed(): List<Managed> =
        installed.map { agent ->
            Managed.text(File(proot.applications, "agent-${agent.bin}.desktop"), Policy.Owned) {
                "[Desktop Entry]\nType=Application\nName=${agent.name}\nTerminal=true\nExec=${agent.command}\nIcon=${bundledIcon("agent-${agent.bin}")}\n"
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
