package dev.omalauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.AGENTS
import dev.omalauncher.linux.Agent
import dev.omalauncher.linux.JobState
import kotlinx.coroutines.launch

/** Install and launch coding agents (Claude Code, OpenCode, Codex, Grok, ...). */
@Composable
fun AgentsScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
    onOpen: (Agent) -> Unit,
) {
    val store = runtime.agents
    val scope = rememberCoroutineScope()
    OverlayPanel("coding agents", onDismiss, contentWidth = ReadingWidth) {
        BasicText(
            "Installed with mise, like Omarchy. Logins open in your browser; paste keys with the keyboard's clipboard.",
            Modifier.padding(vertical = 8.dp),
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AGENTS, key = { it.bin }) { agent ->
                AgentRow(
                    agent = agent,
                    installed = agent in store.installed,
                    state = store.states[agent.bin] ?: JobState.Idle,
                    onInstall = { scope.launch { store.install(agent) } },
                    onRemove = { scope.launch { store.remove(agent) } },
                    onOpen = { onOpen(agent) },
                )
            }
        }
    }
}

@Composable
private fun AgentRow(
    agent: Agent,
    installed: Boolean,
    state: JobState,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        Modifier.card(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText(agent.name, style = Mono.copy(fontSize = TextSize.Item))
                BasicText(agent.description, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
            when {
                state is JobState.Working -> BasicText("${state.action}…", style = Mono.copy(color = Palette.accent))
                installed -> {
                    BracketAction("open", Palette.green, onOpen)
                    BracketAction("remove", Palette.red, onRemove)
                }
                else -> BracketAction("install", Palette.accent, onInstall)
            }
        }
        JobLog(state)
    }
}
