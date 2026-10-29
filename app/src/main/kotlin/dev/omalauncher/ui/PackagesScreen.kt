package dev.omalauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.R
import dev.omalauncher.linux.JobState
import dev.omalauncher.linux.Package
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Search Arch Linux ARM's repos and the AUR, install or remove packages; empty search lists what's installed. */
@Composable
fun PackagesScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val pacman = runtime.pacman
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val revision = pacman.revision.intValue
    // (query, results): a result for an older query means a search is still running.
    val loaded by produceState<Pair<String, List<Package>>?>(null, query, revision) {
        val q = query.trim()
        if (q.isNotEmpty()) delay(350) // debounce typing
        value = q to (if (q.isEmpty()) pacman.installed() else pacman.search(q))
    }
    val searching = loaded?.first != query.trim()

    fun act(block: suspend () -> Boolean) {
        // New or removed .desktop files should show up in the drawer right away.
        scope.launch { if (block()) runtime.refreshApps() }
    }

    OverlayPanel(stringResource(R.string.packages_title), onDismiss, contentWidth = ReadingWidth) {
        SearchField(query, onChange = { query = it }, onGo = {}, placeholder = stringResource(R.string.packages_search))
        BasicText(
            stringResource(R.string.packages_intro),
            Modifier.padding(vertical = 8.dp),
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
        val list = loaded?.second.orEmpty()
        val status =
            when {
                searching -> stringResource(if (query.isBlank()) R.string.packages_loading else R.string.packages_searching)
                query.isBlank() -> stringResource(R.string.packages_installed, list.size)
                list.isEmpty() -> stringResource(R.string.packages_none)
                else -> pluralStringResource(R.plurals.packages_results, list.size, list.size)
            }
        BasicText(status, Modifier.padding(bottom = 6.dp), style = Mono.copy(color = Palette.muted))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // By repo too: a package in two repos (one the user added) is listed by pacman twice.
            items(list, key = { "${it.repo}/${it.name}" }) { pkg ->
                PackageRow(
                    pkg = pkg,
                    core = pkg.name in pacman.core,
                    state = pacman.states[pkg.name] ?: JobState.Idle,
                    onInstall = { act { pacman.install(pkg) } },
                    onRemove = { act { pacman.remove(pkg.name) } },
                )
            }
        }
    }
}

@Composable
private fun PackageRow(
    pkg: Package,
    core: Boolean,
    state: JobState,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    // Removal takes a second tap: it also removes dependencies nothing else uses.
    var confirming by remember(pkg.name, pkg.installed) { mutableStateOf(false) }
    Column(
        Modifier.card(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicText(pkg.name, style = Mono.copy(fontSize = TextSize.Item))
                    Spacer(Modifier.padding(start = 8.dp))
                    BasicText(
                        listOfNotNull(pkg.repo, pkg.version).joinToString(" "),
                        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                        maxLines = 1,
                    )
                }
                if (pkg.description.isNotEmpty()) {
                    BasicText(pkg.description, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 2)
                }
            }
            // Keys stay on the row's button as it changes: remove to confirm, and through the job to what it ends with.
            val confirm = remember { FocusRequester() }
            when {
                state is JobState.Working -> BasicText(stringResource(R.string.linux_job_working, stringResource(state.action)), style = Mono.copy(color = Palette.accent))
                pkg.installed && core -> BasicText(stringResource(R.string.packages_required), Modifier.padding(horizontal = 8.dp), style = Mono.copy(color = Palette.muted))
                pkg.installed && confirming -> BracketAction(stringResource(R.string.packages_confirm), Palette.red, Modifier.focusRequester(confirm).passesKeys(), onRemove)
                pkg.installed -> BracketAction(stringResource(R.string.packages_remove), Palette.red, Modifier.takesPassedKeys().passesKeys(confirm)) { confirming = true }
                else -> BracketAction(stringResource(R.string.packages_install), Palette.accent, Modifier.takesPassedKeys().passesKeys(), onInstall)
            }
        }
        JobLog(state)
    }
}
