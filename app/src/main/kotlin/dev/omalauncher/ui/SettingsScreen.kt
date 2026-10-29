package dev.omalauncher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.LocalNetwork
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.linux.JobState
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.widgets.Market
import dev.omalauncher.widgets.WidgetEngine
import dev.omalauncher.widgets.market.CatalogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The launcher's settings hub, opened from the gear next to the drawer's search. */
@Composable
fun SettingsScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
    onStyle: () -> Unit,
    onPages: () -> Unit,
    onAgents: () -> Unit,
    onPackages: () -> Unit,
    onLinux: () -> Unit,
    onLicenses: () -> Unit,
    onGestures: () -> Unit,
) {
    val context = LocalContext.current
    val linux by runtime.bootstrap.state.collectAsState()
    val installed = linux == LinuxState.Installed
    var localNetworkMissing by remember { mutableStateOf(LocalNetwork.missing(context)) }
    val askLocalNetwork =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            localNetworkMissing = !granted
            // After two refusals Android stops showing the dialog; the switch is then in the app's own settings.
            if (!granted && (context as? Activity)?.shouldShowRequestPermissionRationale(LocalNetwork.PERMISSION) == false) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    OverlayPanel(stringResource(R.string.settings_title), onDismiss, contentWidth = ReadingWidth) {
        val linuxStatus =
            when (val l = linux) {
                LinuxState.Installed -> stringResource(R.string.settings_linux_installed)
                is LinuxState.Installing ->
                    l.progress?.let { stringResource(R.string.settings_linux_installing_percent, (it * 100).toInt(), stringResource(l.stage.label)) }
                        ?: stringResource(R.string.settings_linux_installing, stringResource(l.stage.label))
                is LinuxState.Failed -> stringResource(R.string.settings_linux_failed)
                LinuxState.Removing -> stringResource(R.string.settings_linux_removing)
                else -> stringResource(R.string.settings_linux_not_installed)
            }
        val needsLinux = stringResource(R.string.settings_needs_linux)
        val rows =
            listOfNotNull(
                SettingsRow(R.string.settings_style, runtime.themes.current?.title ?: stringResource(R.string.settings_style_subtitle), onStyle),
                SettingsRow(R.string.settings_pages, pluralStringResource(R.plurals.settings_pages_subtitle, runtime.widgets.pages.size, runtime.widgets.pages.size), onPages),
                SettingsRow(
                    R.string.settings_gestures,
                    stringResource(R.string.settings_gestures_subtitle, runtime.gestureLabel(runtime.gestures.swipeUp), runtime.gestureLabel(runtime.gestures.swipeDown)),
                    onGestures,
                ),
                SettingsRow(R.string.settings_linux, linuxStatus, onLinux),
                if (installed) {
                    SettingsRow(
                        R.string.settings_agents,
                        runtime.agents.default?.let { stringResource(R.string.settings_agents_default, it.name) } ?: stringResource(R.string.settings_agents_install),
                        onAgents,
                    )
                } else {
                    SettingsRow(R.string.settings_agents, needsLinux, onLinux)
                },
                SettingsRow(R.string.settings_packages, if (installed) stringResource(R.string.settings_packages_subtitle) else needsLinux, if (installed) onPackages else onLinux),
                if (localNetworkMissing) {
                    SettingsRow(R.string.settings_local_network, stringResource(R.string.settings_local_network_subtitle)) {
                        askLocalNetwork.launch(LocalNetwork.PERMISSION)
                    }
                } else {
                    null
                },
                SettingsRow(R.string.settings_android, stringResource(R.string.settings_android_subtitle)) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                SettingsRow(R.string.settings_licenses, stringResource(R.string.settings_licenses_subtitle), onLicenses),
            )
        // The updates scroll with the rows: at a large Android font size a long list of them would leave the rows a strip.
        // Not lazy: scrolled out and back, the updates would check again.
        Column(Modifier.verticalScroll(rememberScrollState())) {
            UpdatesBox(runtime, installed)
            if (installed) SetupUpdates(runtime)
            SettingsRows(rows)
        }
    }
}

/** A row of the settings hub: [title] is its string, which also keys it. */
private class SettingsRow(
    @StringRes val title: Int,
    val subtitle: String,
    val onClick: () -> Unit,
)

@Composable
private fun SettingsRows(rows: List<SettingsRow>) {
    // The local network row goes once it's granted: keys on it go on to the next.
    val next = remember { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in rows) {
            key(row.title) {
                Row(
                    when (row.title) {
                        R.string.settings_local_network -> Modifier.passesKeys(next)
                        R.string.settings_android -> Modifier.focusRequester(next)
                        else -> Modifier
                    }.card(onClick = row.onClick),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(stringResource(row.title), style = Mono.copy(fontSize = TextSize.Item))
                        BasicText(row.subtitle, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
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
        Modifier.padding(bottom = 8.dp).card(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                if (working != null) stringResource(R.string.settings_system_updating, stringResource(working.action).lowercase()) else stringResource(R.string.settings_system_update_failed),
                Modifier.weight(1f),
                style = Mono.copy(color = if (failed != null) Palette.red else Palette.accent),
            )
            if (failed != null) BracketAction(stringResource(R.string.settings_retry), Palette.accent, onClick = runtime::retryUpdates)
        }
        JobLog(state)
    }
}

/**
 * "system updates" while Linux is installed, then "widgets": the store's widgets and apps, shown only while they
 * have updates. Each has its own "update"; the top's "update all" shows when both do, and "check" checks both
 * while nothing is pending. Without Linux, the box is only there while widgets have updates.
 */
@Composable
private fun UpdatesBox(
    runtime: Runtime,
    linux: Boolean,
) {
    val updates = runtime.updates
    val engine = runtime.widgets
    val markets = engine.markets
    if (linux) LaunchedEffect(Unit) { updates.check() }
    LaunchedEffect(Unit) {
        // Opened on its own after the process died, settings comes before the engine has read these.
        withContext(Dispatchers.IO) { markets.forEach { it.load() } }
        markets.forEach { it.refreshIfStale() }
    }
    val linuxPending = linux && (updates.pending?.count ?: 0) > 0
    val pending = markets.flatMap { m -> m.updates.map { m to it } }
    // Edited ones are left to the store, which says their changes would go.
    val (held, ready) = pending.partition { (m, e) -> m.edited(e) }
    val busy = updates.job is JobState.Working || updates.checking || engine.updating || markets.any { it.loading }
    val widgets = pending.isNotEmpty() || engine.updating || engine.updateError != null
    if (!linux && !widgets) return
    Column(
        Modifier.padding(bottom = 8.dp).card(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(stringResource(if (linux) R.string.settings_system_updates else R.string.settings_widget_updates), Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item))
            when {
                busy -> {}
                // With one section pending, its own "update" is the same thing.
                linuxPending && ready.isNotEmpty() ->
                    BracketAction(stringResource(R.string.settings_update_all), Palette.accent) {
                        runtime.updateSystem()
                        engine.updateAll()
                    }
                linuxPending || ready.isNotEmpty() -> {}
                // Not a composition scope: this branch leaves composition as soon as checking starts, which would cancel the check.
                else ->
                    BracketAction(stringResource(R.string.settings_check), Palette.muted) {
                        runtime.checkUpdates()
                        engine.checkUpdates()
                    }
            }
        }
        if (linux) LinuxUpdatesRows(runtime)
        if (linux && widgets) BasicText(stringResource(R.string.settings_widget_updates), Modifier.padding(top = 8.dp), style = Mono.copy(fontSize = TextSize.Item))
        if (widgets) WidgetUpdatesRows(engine, markets, ready, held)
    }
}

/** Pending pacman and agent updates, from the last check; "update" runs them as one job. */
@Composable
private fun LinuxUpdatesRows(runtime: Runtime) {
    val updates = runtime.updates
    val pending = updates.pending
    val job = updates.job
    val working = job is JobState.Working
    val lines =
        when {
            working -> listOf(stringResource(R.string.settings_updating))
            updates.checking -> listOf(stringResource(R.string.settings_checking))
            pending == null -> listOf(stringResource(R.string.settings_not_checked))
            pending.error != null -> listOf(stringResource(pending.error))
            pending.count == 0 -> listOf(stringResource(R.string.settings_up_to_date, ago(pending.checkedAt)))
            // The agents first: they're what most people are waiting for.
            else -> pending.tools.map { "${it.name} ${it.current} → ${it.latest}" } + pending.packages
        }
    val ready = (pending?.count ?: 0) > 0 && !working && !updates.checking
    UpdateLines(lines, ready) { runtime.updateSystem() }
    JobLog(job)
}

/**
 * Store updates for installed widgets and apps, from the catalogs (reloaded when an hour old); "update" installs the
 * [ready] ones. [held] ones were edited, so they're only listed.
 */
@Composable
private fun WidgetUpdatesRows(
    engine: WidgetEngine,
    markets: List<Market>,
    ready: List<Pair<Market, CatalogEntry>>,
    held: List<Pair<Market, CatalogEntry>>,
) {
    val checking = markets.any { it.loading }
    val lines =
        when {
            engine.updating -> listOf(stringResource(R.string.settings_updating))
            checking -> listOf(stringResource(R.string.settings_checking))
            ready.isNotEmpty() || held.isNotEmpty() -> ready.map { (m, e) -> "${e.name} ${m.installedVersion(e)?.version} → ${e.version}" }
            markets.any { it.error != null } -> listOf(stringResource(R.string.settings_store_unreachable))
            markets.any { it.refreshedAt == 0L } -> listOf(stringResource(R.string.settings_not_checked))
            else -> listOf(stringResource(R.string.settings_up_to_date, ago(markets.minOf { it.refreshedAt })))
        }
    UpdateLines(lines, ready.isNotEmpty() && !engine.updating && !checking, engine::updateAll)
    if (!engine.updating && !checking && held.isNotEmpty()) {
        val names = held.joinToString { (m, e) -> "${e.name} ${m.installedVersion(e)?.version} → ${e.version}" }
        BasicText(stringResource(R.string.settings_updates_held, names), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
    }
    engine.updateError?.let { BasicText(it, style = Mono.copy(color = Palette.red, fontSize = TextSize.Small), maxLines = 3) }
}

/**
 * A section of the updates box: what's pending, one per line (accent while [ready]; past [MAX_LINES], how many
 * more), and "update" beside the first line then.
 */
@Composable
private fun UpdateLines(
    lines: List<String>,
    ready: Boolean,
    onUpdate: () -> Unit,
) {
    val shown = if (lines.size > MAX_LINES) lines.take(MAX_LINES - 1) + stringResource(R.string.settings_more_lines, lines.size - MAX_LINES + 1) else lines
    val style = Mono.copy(color = if (ready) Palette.accent else Palette.muted, fontSize = TextSize.Small)
    Row {
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            for (line in shown) BasicText(line, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (ready) BracketAction(stringResource(R.string.settings_update), Palette.accent, onClick = onUpdate)
    }
}

/** Lines a section of the updates box lists before it says how many more there are. */
private const val MAX_LINES = 10

@Composable
private fun ago(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    return when {
        minutes < 1 -> stringResource(R.string.settings_just_now)
        minutes < 60 -> stringResource(R.string.settings_minutes_ago, minutes)
        minutes < 48 * 60 -> stringResource(R.string.settings_hours_ago, minutes / 60)
        else -> stringResource(R.string.settings_days_ago, minutes / (24 * 60))
    }
}
