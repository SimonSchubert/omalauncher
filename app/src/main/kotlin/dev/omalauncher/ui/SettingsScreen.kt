package dev.omalauncher.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.JobState
import dev.omalauncher.linux.LinuxState

/** The launcher's settings hub, opened from the gear next to the drawer's search. */
@Composable
fun SettingsScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
    onStyle: () -> Unit,
    onAgents: () -> Unit,
    onPackages: () -> Unit,
    onStore: () -> Unit,
    onLinux: () -> Unit,
    onLicenses: () -> Unit,
    onGestures: () -> Unit,
) {
    val context = LocalContext.current
    val linux by runtime.bootstrap.state.collectAsState()
    val installed = linux == LinuxState.Installed
    OverlayPanel("settings", onDismiss, contentWidth = ReadingWidth) {
        val linuxStatus =
            when (val l = linux) {
                LinuxState.Installed -> "installed · remove"
                is LinuxState.Installing -> "installing" + (l.progress?.let { " ${(it * 100).toInt()}%" } ?: "") + " · ${l.stage.label}"
                is LinuxState.Failed -> "setup stopped · retry"
                LinuxState.Removing -> "removing…"
                else -> "not installed · install Arch Linux for terminal apps and agents"
            }
        val rows =
            listOf(
                Triple("style", runtime.themes.current?.title ?: "theme, background, home screen", onStyle),
                Triple("gestures", "swipe up: ${runtime.gestures.swipeUp.label} · down: ${runtime.gestures.swipeDown.label}", onGestures),
                Triple("linux", linuxStatus, onLinux),
                if (installed) {
                    Triple("coding agents", runtime.agents.default?.let { "default: ${it.name}" } ?: "install Claude Code, OpenCode, …", onAgents)
                } else {
                    Triple("coding agents", "needs linux", onLinux)
                },
                Triple("packages", if (installed) "install Linux apps and tools with pacman and the AUR" else "needs linux", if (installed) onPackages else onLinux),
                Triple("widget store", "add widgets to the home screen", onStore),
                Triple("android settings", "the phone's system settings") {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                Triple("licenses", "open source software in Omalauncher", onLicenses),
            )
        if (installed) {
            LaunchedEffect(Unit) { runtime.updates.check() }
            UpdatesRow(runtime)
            SetupUpdates(runtime)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.first }) { (title, subtitle, onClick) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(LauncherStyle.shape(12.dp))
                        .background(Palette.surface)
                        .clickable(onClick = onClick)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(title, style = Mono.copy(fontSize = TextSize.Item))
                        BasicText(subtitle, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                    }
                    Symbol(Glyph.ChevronRight, Palette.accent, fontSize = TextSize.Title)
                }
            }
        }
    }
}

/** Rootfs updates from a new app version that need the network; they run in the background and show here. */
@Composable
private fun SetupUpdates(runtime: Runtime) {
    val state by runtime.bootstrap.updates.collectAsState()
    val failed = state as? JobState.Failed
    val working = state as? JobState.Working
    if (failed == null && working == null) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(LauncherStyle.shape(12.dp))
            .background(Palette.surface)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                if (working != null) "updating the system: ${working.action.lowercase()}…" else "a system update didn't finish",
                Modifier.weight(1f),
                style = Mono.copy(color = if (failed != null) Palette.red else Palette.accent),
            )
            if (failed != null) BracketAction("retry", Palette.accent, runtime::retryUpdates)
        }
        JobLog(state)
    }
}

/** Pending pacman and agent updates, from the last check; "update all" runs them as one job. */
@Composable
private fun UpdatesRow(runtime: Runtime) {
    val updates = runtime.updates
    val pending = updates.pending
    val job = updates.job
    val working = job is JobState.Working
    val status =
        when {
            working -> "updating…"
            updates.checking -> "checking…"
            pending == null -> "not checked yet"
            pending.error != null -> pending.error
            pending.count == 0 -> "up to date · checked ${ago(pending.checkedAt)}"
            else ->
                listOfNotNull(
                    pending.packages.size.takeIf { it > 0 }?.let { "$it package${if (it == 1) "" else "s"}" },
                    pending.tools.takeIf { it.isNotEmpty() }?.joinToString { "${it.name} ${it.current} → ${it.latest}" },
                ).joinToString(" · ")
        }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(LauncherStyle.shape(12.dp))
            .background(Palette.surface)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText("system updates", style = Mono.copy(fontSize = TextSize.Item))
                BasicText(
                    status,
                    style = Mono.copy(color = if ((pending?.count ?: 0) > 0 && !working) Palette.accent else Palette.muted, fontSize = TextSize.Small),
                    maxLines = 3,
                )
            }
            when {
                working || updates.checking -> {}
                (pending?.count ?: 0) > 0 -> BracketAction("update all", Palette.accent, runtime::updateSystem)
                else -> {
                    val scope = rememberCoroutineScope()
                    BracketAction("check", Palette.muted) { scope.launch { updates.check(force = true) } }
                }
            }
        }
        JobLog(job)
    }
}

private fun ago(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 48 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (24 * 60)}d ago"
    }
}
