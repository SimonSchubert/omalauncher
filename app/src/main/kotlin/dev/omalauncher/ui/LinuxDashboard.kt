package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.LinuxSessionState
import dev.omalauncher.linux.LinuxApp
import dev.omalauncher.widgets.lang.OwlKind
import dev.omalauncher.widgets.WidgetSpec

/** The dashboard's top-level sections. */
enum class DashboardSection(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector?,
) {
    Overview("overview", null),
    LinuxApps("linux apps", Icons.Folder),
    Terminal("terminal", Icons.TextFile),
    Widgets("widgets", Icons.More),
    Files("files", Icons.Folder),
    Themes("themes", Icons.Image),
    Settings("settings", Icons.Gear),
}

/** The Linux environment dashboard: environment status and controls, and the workspace sections. */
@Composable
fun LinuxDashboard(
    runtime: Runtime,
    sharedWidget: String?,
    onSharedCleared: () -> Unit,
) {
    val context = LocalContext.current
    var section: DashboardSection by remember { mutableStateOf(DashboardSection.Overview) }
    val state by runtime.sessionState.collectAsState()

    Column(Modifier.fillMaxSize().background(Palette.background)) {
        // The shared widget link, read once and cleared so it isn't acted on twice.
        sharedWidget?.let { link ->
            SharedWidgetNotice(
                link = link,
                onAdd = {
                    runtime.openSettings(dev.omalauncher.ui.SettingsPage.Store)
                    onSharedCleared()
                },
                onDismiss = { onSharedCleared() },
            )
        }

        // The sections' content fills the window above the bar.
        when (section) {
            DashboardSection.Overview -> OverviewScreen(runtime)
            DashboardSection.LinuxApps -> LinuxAppsScreen(runtime)
            DashboardSection.Terminal -> TerminalSection(runtime)
            DashboardSection.Widgets -> WidgetsScreen(runtime)
            DashboardSection.Files -> FilesSection(runtime, context)
            DashboardSection.Themes -> ThemesSection(runtime, context)
            DashboardSection.Settings -> SettingsSection(runtime, context)
        }

        // The bar: one tap per section, the open one in the accent color.
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(Palette.surface),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
        ) {
            for (s in DashboardSection.values()) {
                val selected = section == s
                Box(
                    Modifier
                        .weight(1f)
                        .clickable { section = s }
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        s.icon?.let { icon ->
                            ImageVectorItem(
                                icon = icon,
                                selected = selected,
                            )
                        }
                        BasicText(
                            s.label,
                            modifier = Modifier.padding(top = 2.dp),
                            style = Mono.copy(
                                fontSize = TextSize.Small,
                                color = if (selected) Palette.accent else Palette.muted,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** A tinted nav icon: accent when selected, muted otherwise. */
@Composable
private fun ImageVectorItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
) {
    androidx.compose.foundation.Image(
        imageVector = icon,
        contentDescription = if (selected) "selected" else null,
        modifier = Modifier.size(20.dp),
        colorFilter = ColorFilter.tint(if (selected) Palette.accent else Palette.muted),
    )
}

/** Overview: environment state, start and stop controls, and quick actions. */
@Composable
private fun OverviewScreen(runtime: Runtime) {
    val state = runtime.sessionState.collectAsState().value
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        SectionLabel("linux environment")
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .frame(2.dp, Palette.accent, Palette.surface, radius = 12.dp)
                .padding(14.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    when (state) {
                        LinuxSessionState.NotInstalled -> "not installed"
                        LinuxSessionState.Stopped -> "stopped"
                        LinuxSessionState.Starting -> "starting"
                        LinuxSessionState.Running -> "running"
                        LinuxSessionState.Stopping -> "stopping"
                        is LinuxSessionState.Failed -> "failed"
                        else -> "unknown"
                    },
                    style = Mono.copy(fontSize = TextSize.Title, color = Palette.foreground),
                )
                when (state) {
                    is LinuxSessionState.Failed -> BasicText(state.message, style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
                    LinuxSessionState.Stopping, LinuxSessionState.Starting -> BasicText("please wait…", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                    else -> {}
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row {
            val startEnabled =
                state == LinuxSessionState.Stopped || state == LinuxSessionState.NotInstalled || state is LinuxSessionState.Failed
            BracketAction(
                "start",
                if (startEnabled) Palette.green else Palette.muted,
                onClick = {
                    if (state == LinuxSessionState.NotInstalled) {
                        runtime.openSettings(dev.omalauncher.ui.SettingsPage.Linux)
                    } else {
                        runtime.startLinux()
                    }
                },
            )
            if (state == LinuxSessionState.Running) {
                BracketAction("stop", Palette.red, onClick = runtime::stopLinux)
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionLabel("quick actions")
        Spacer(Modifier.height(8.dp))
        QuickAction("open a terminal", Icons.TextFile) {
            runtime.terminal(null, "Terminal")
        }
        QuickAction("linux applications", Icons.Folder) { /* shown by the linux apps section */ }
        QuickAction("files", Icons.Folder) {
            runtime.openFiles()
        }
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
            ) {
        icon?.let { ImageVectorItem(icon = it, selected = false) }
        Spacer(Modifier.width(8.dp))
        BasicText(label, style = Mono.copy(color = Palette.foreground))
    }
}

/** Terminal section: opens a terminal window in the guest. */
@Composable
private fun TerminalSection(runtime: Runtime) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        SectionLabel("terminal")
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .frame(2.dp, Palette.accent, Palette.surface, radius = 12.dp)
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Symbol("\uDB80\uDD42", Palette.accent, Modifier.size(34.dp), TextSize.Title)
                Spacer(Modifier.height(8.dp))
                BasicText("open a terminal", style = Mono.copy(fontSize = TextSize.Item, color = Palette.foreground))
                Spacer(Modifier.height(4.dp))
                BasicText("a foot window in its own Android task", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
        Spacer(Modifier.height(16.dp))
        BracketAction("open terminal", Palette.accent, onClick = {
            runtime.terminal(null, "Terminal")
        })
    }
}

/** Linux applications: installed .desktop entries, searchable, each launchable. */
@Composable
fun LinuxAppsScreen(runtime: Runtime) {
    val apps = runtime.linuxApps.apps
    val listed = runtime.linuxApps.listed
    var query: String by remember { mutableStateOf("") }
    val filtered = if (query.isEmpty()) apps else apps.filter { it.name.lowercase().contains(query.lowercase()) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        SectionLabel("linux applications")
        Spacer(Modifier.height(8.dp))
        BasicText(
            if (!listed) "listing…" else if (apps.isEmpty()) "no linux applications installed" else "${apps.size} installed",
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().background(Palette.surface).clip(LauncherStyle.shape(8.dp)).padding(4.dp)) {
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                textStyle = Mono.copy(color = Palette.foreground),
            )
        }
        Spacer(Modifier.height(12.dp))
        if (filtered.isEmpty() && query.isNotEmpty()) {
            BasicText("no matches", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Item))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(filtered, key = { it.id }) { app ->
                    AppRow(app, runtime)
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: LinuxApp, runtime: Runtime) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = { runtime.launch(app) })
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        app.icon?.let { icon ->
            val file = runtime.proot.hostFile(icon)
            // Best effort: draw the icon if the host can read it; otherwise a silhouette placeholder.
            Box(Modifier.size(32.dp).clip(CircleShape).background(Palette.surface), contentAlignment = Alignment.Center) {
                BasicText(app.name.first().uppercase(), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
            }
        } ?: Box(Modifier.size(32.dp).clip(CircleShape).background(Palette.surface), contentAlignment = Alignment.Center) {
            BasicText(app.name.first().uppercase(), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            BasicText(app.name, style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Item))
            BasicText(app.exec.take(40), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
        Symbol("\uDB80\uDD42", Palette.muted, Modifier.size(16.dp))
    }
}

/** Widgets: installed widget specs, with a note when one needs Linux. */
@Composable
private fun WidgetsScreen(runtime: Runtime) {
    val specs = runtime.widgets.specs
    val widgets = specs.filter { it.kind == OwlKind.Widget }
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        SectionLabel("widgets")
        Spacer(Modifier.height(8.dp))
        BasicText(
            "${widgets.size} widget${if (widgets.size == 1) "" else "s"}",
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(widgets, key = { it.file.name }) { spec ->
                WidgetRow(spec, runtime)
            }
        }
    }
}

@Composable
private fun WidgetRow(spec: WidgetSpec, runtime: Runtime) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = { runtime.widgets.tap(spec) })
            .padding(vertical = 6.dp, horizontal = 4.dp),
        
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Palette.surface), contentAlignment = Alignment.Center) {
            BasicText(spec.name.first().uppercase(), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            BasicText(spec.name, style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Item))
            if (spec.error != null) {
                BasicText("error: ${spec.error}", style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
            } else if (spec.program == null) {
                BasicText("does not compile", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            } else if (spec.program.needsLinux && !runtime.isRunning) {
                BasicText("needs linux to run", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            } else {
                BasicText("tap to open", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
        Symbol("\uDB80\uDD42", Palette.muted, Modifier.size(16.dp))
    }
}

/** Files: opens the Files activity. */
@Composable
private fun FilesSection(runtime: Runtime, context: android.content.Context) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        SectionLabel("files")
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .frame(2.dp, Palette.accent, Palette.surface, radius = 12.dp)
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ImageVectorItem(icon = Icons.Image, selected = false)
                Spacer(Modifier.height(8.dp))
                BasicText("linux filesystem", style = Mono.copy(fontSize = TextSize.Item, color = Palette.foreground))
                Spacer(Modifier.height(4.dp))
                BasicText("the guest's files, in its own task", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
        Spacer(Modifier.height(16.dp))
        BracketAction("open files", Palette.accent, onClick = {
            runtime.openFiles()
        })
    }
}

/** Themes: opens the settings activity at the themes screen. */
@Composable
private fun ThemesSection(runtime: Runtime, context: android.content.Context) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        SectionLabel("themes")
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .frame(2.dp, Palette.accent, Palette.surface, radius = 12.dp)
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ImageVectorItem(icon = Icons.Image, selected = false)
                Spacer(Modifier.height(8.dp))
                BasicText("omarchy themes", style = Mono.copy(fontSize = TextSize.Item, color = Palette.foreground))
                Spacer(Modifier.height(4.dp))
                BasicText("wallpapers and appearance", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
        Spacer(Modifier.height(16.dp))
        BracketAction("open themes", Palette.accent, onClick = {
            runtime.openSettings(dev.omalauncher.ui.SettingsPage.Themes)
        })
    }
}

/** Settings: opens the settings activity. */
@Composable
private fun SettingsSection(runtime: Runtime, context: android.content.Context) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        SectionLabel("settings")
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .frame(2.dp, Palette.accent, Palette.surface, radius = 12.dp)
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ImageVectorItem(icon = Icons.Gear, selected = false)
                Spacer(Modifier.height(8.dp))
                BasicText("environment and app settings", style = Mono.copy(fontSize = TextSize.Item, color = Palette.foreground))
                Spacer(Modifier.height(4.dp))
                BasicText("linux, updates, agents, packages", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
        Spacer(Modifier.height(16.dp))
        BracketAction("open settings", Palette.accent, onClick = {
            runtime.openSettings(dev.omalauncher.ui.SettingsPage.Settings)
        })
    }
}

/** A shared widget link waiting to be read and added, over the dashboard. */
@Composable
private fun SharedWidgetNotice(
    link: String,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface)
            .padding(10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row {
            Column(Modifier.weight(1f)) {
                BasicText("shared widget", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
                BasicText(link.take(60), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 2)
            }
            Row {
                BracketAction("add", Palette.accent, onClick = onAdd)
                BracketAction("dismiss", Palette.muted, onClick = onDismiss)
            }
        }
    }
}
