package dev.omalauncher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.LocalNetwork
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
    OverlayPanel("settings", onDismiss, contentWidth = ReadingWidth) {
        val linuxStatus =
            when (val l = linux) {
                LinuxState.Installed -> UiText.t("installed · remove")
                is LinuxState.Installing -> UiText.t("installing") + (l.progress?.let { " ${(it * 100).toInt()}%" } ?: "") + " · " + UiText.t(l.stage.label)
                is LinuxState.Failed -> UiText.t("setup stopped · retry")
                LinuxState.Removing -> UiText.t("removing…")
                else -> UiText.t("not installed · install Arch Linux for terminal apps and agents")
            }
        val pageCount = runtime.widgets.pages.size
        val rows =
            listOfNotNull(
                Triple("style", runtime.themes.current?.title ?: UiText.t("theme, background, home screen"), onStyle),
                Triple("pages", (if (pageCount == 1) UiText.t("1 page") else UiText.t("%s pages", pageCount)) + UiText.t(" · arrange, edit, remove"), onPages),
                Triple("gestures", UiText.t("up: %s · down: %s", runtime.gestureLabel(runtime.gestures.swipeUp), runtime.gestureLabel(runtime.gestures.swipeDown)), onGestures),
                Triple("linux", linuxStatus, onLinux),
                if (installed) {
                    Triple("coding agents", runtime.agents.default?.let { UiText.t("default: %s", it.name) } ?: UiText.t("install Claude Code, OpenCode, …"), onAgents)
                } else {
                    Triple("coding agents", UiText.t("needs linux"), onLinux)
                },
                Triple("packages", UiText.t(if (installed) "install Linux apps and tools with pacman and the AUR" else "needs linux"), if (installed) onPackages else onLinux),
                if (localNetworkMissing) {
                    Triple("local network", UiText.t("let widgets and linux reach your printer, home assistant, …")) {
                        askLocalNetwork.launch(LocalNetwork.PERMISSION)
                    }
                } else {
                    null
                },
                Triple("android settings", UiText.t("the phone's system settings")) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                Triple("licenses", UiText.t("open source software in Omalauncher"), onLicenses),
            )
        UpdatesBox(runtime, installed)
        if (installed) SetupUpdates(runtime)
        // The local network row goes once it's granted: keys on it go on to the next.
        val next = remember { FocusRequester() }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.first }) { (title, subtitle, onClick) ->
                Row(
                    when (title) {
                        "local network" -> Modifier.passesKeys(next)
                        "android settings" -> Modifier.focusRequester(next)
                        else -> Modifier
                    }.card(onClick = onClick),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(UiText.t(title), style = Mono.copy(fontSize = TextSize.Item))
                        BasicText(UiText.t(subtitle), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
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
                if (working != null) UiText.t("updating the system: %s…", working.action.lowercase()) else UiText.t("a system update didn't finish"),
                Modifier.weight(1f),
                style = Mono.copy(color = if (failed != null) Palette.red else Palette.accent),
            )
            if (failed != null) BracketAction("retry", Palette.accent, onClick = runtime::retryUpdates)
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
            BasicText(UiText.t(if (linux) "system updates" else "widgets"), Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item))
            when {
                busy -> {}
                // With one section pending, its own "update" is the same thing.
                linuxPending && ready.isNotEmpty() ->
                    BracketAction("update all", Palette.accent) {
                        runtime.updateSystem()
                        engine.updateAll()
                    }
                linuxPending || ready.isNotEmpty() -> {}
                // Not a composition scope: this branch leaves composition as soon as checking starts, which would cancel the check.
                else ->
                    BracketAction("check", Palette.muted) {
                        runtime.checkUpdates()
                        engine.checkUpdates()
                    }
            }
        }
        if (linux) LinuxUpdatesRows(runtime)
        if (linux && widgets) BasicText(UiText.t("widgets"), Modifier.padding(top = 8.dp), style = Mono.copy(fontSize = TextSize.Item))
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
            working -> listOf(UiText.t("updating…"))
            updates.checking -> listOf(UiText.t("checking…"))
            pending == null -> listOf(UiText.t("not checked yet"))
            pending.error != null -> listOf(pending.error)
            pending.count == 0 -> listOf(UiText.t("up to date · checked %s", ago(pending.checkedAt)))
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
            engine.updating -> listOf(UiText.t("updating…"))
            checking -> listOf(UiText.t("checking…"))
            ready.isNotEmpty() || held.isNotEmpty() -> ready.map { (m, e) -> "${e.name} ${m.installedVersion(e)?.version} → ${e.version}" }
            markets.any { it.error != null } -> listOf(UiText.t("couldn't reach the store"))
            markets.any { it.refreshedAt == 0L } -> listOf(UiText.t("not checked yet"))
            else -> listOf(UiText.t("up to date · checked %s", ago(markets.minOf { it.refreshedAt })))
        }
    UpdateLines(lines, ready.isNotEmpty() && !engine.updating && !checking, engine::updateAll)
    if (!engine.updating && !checking && held.isNotEmpty()) {
        val names = held.joinToString { (m, e) -> "${e.name} ${m.installedVersion(e)?.version} → ${e.version}" }
        BasicText(UiText.t("edited, so not updated here: %s · updating one in the store replaces your changes", names), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
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
    val shown = if (lines.size > MAX_LINES) lines.take(MAX_LINES - 1) + UiText.t("+%s more", lines.size - MAX_LINES + 1) else lines
    val style = Mono.copy(color = if (ready) Palette.accent else Palette.muted, fontSize = TextSize.Small)
    Row {
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            for (line in shown) BasicText(line, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (ready) BracketAction(UiText.t("update"), Palette.accent, onClick = onUpdate)
    }
}

/** Lines a section of the updates box lists before it says how many more there are. */
private const val MAX_LINES = 10

private fun ago(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    return when {
        minutes < 1 -> UiText.t("just now")
        minutes < 60 -> UiText.t("%sm ago", minutes)
        minutes < 48 * 60 -> UiText.t("%sh ago", minutes / 60)
        else -> UiText.t("%sd ago", minutes / (24 * 60))
    }
}
