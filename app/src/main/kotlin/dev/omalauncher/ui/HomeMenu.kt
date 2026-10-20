package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.widgets.lang.OwlKind
import dev.omalauncher.widgets.newOwlCommand
import kotlinx.coroutines.launch

@Composable
internal fun EditBar(content: @Composable () -> Unit) {
    Row(
        Modifier
            .frame(2.dp, Palette.accent, Palette.background)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
internal fun EditAction(
    label: String,
    color: androidx.compose.ui.graphics.Color = Palette.foreground,
    onClick: () -> Unit,
) {
    Box(Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        BasicText(label, style = Mono.copy(color = color, fontSize = TextSize.Body))
    }
}

/**
 * The long-press menu for empty home space. Drawn by the launcher over the whole
 * screen (not inside the home page's system-bar padding) so its scrim covers the
 * status and navigation bars too.
 */
@Composable
fun HomeMenu(
    runtime: Runtime,
    state: LauncherState,
) {
    if (!state.homeMenuOpen) return
    val engine = runtime.widgets
    val agent = runtime.agents.default
    val agentName = agent?.name?.lowercase() ?: "an agent"
    // These need Linux; without it they open its install sheet.
    val linux = runtime.bootstrap.state.collectAsState().value == LinuxState.Installed
    val scope = rememberCoroutineScope()

    /** Recolors right away, then applies the full theme: its first background, like omarchy-theme-set, and the guest's configs. */
    fun pickTheme(theme: dev.omalauncher.theme.BundledTheme) {
        runtime.themes.pick(theme)
        // Until all themes are downloaded, one that isn't there yet is fetched on its own.
        scope.launch { if (runtime.themes.complete) runtime.themes.applyPicked() else runtime.themes.fetchPicked() }
    }
    Menu(onDismiss = { state.homeMenuOpen = false }) {
        // What to create sits on the row itself: one tap starts the agent, each kind with its own prompt.
        // Settings at the top right, as in the drawer.
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp, top = 10.dp)) {
                BasicText("create with $agentName", style = Mono.copy(fontSize = TextSize.Item))
                // Pulled left by the buttons' own padding, so the first bracket lines up with the label.
                Row(Modifier.offset(x = (-8).dp)) {
                    val kinds: List<Pair<String, (String) -> String>> =
                        listOf(
                            "widget" to { newOwlCommand(it, OwlKind.Widget) },
                            // Faces are made from the empty page left of them, which says where the new one goes.
                            "app" to { newOwlCommand(it, OwlKind.App) },
                            "panel" to { newOwlCommand(it, OwlKind.Panel) },
                        )
                    for ((label, command) in kinds) {
                        BracketAction(label, Palette.accent) {
                            state.homeMenuOpen = false
                            if (linux) state.withAgent(runtime, "New $label", command) else state.show(SettingsPage.Linux)
                        }
                    }
                }
            }
            SettingsButton(runtime) {
                state.homeMenuOpen = false
                state.show()
            }
        }
        MenuItem("browse store") {
            state.homeMenuOpen = false
            state.show(SettingsPage.Store)
        }
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            ThemeStrip(
                runtime.themes,
                gutter = 20.dp,
                onPick = ::pickTheme,
                onBrowse = {
                    state.homeMenuOpen = false
                    state.show(SettingsPage.Themes)
                },
            )
        }
        if (runtime.wm.appWindows.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                BasicText("running windows", style = Mono.copy(fontSize = TextSize.Item))
                BasicText(
                    "Return to an existing window, not a new app launch.",
                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 240.dp)) {
                items(runtime.wm.appWindows) { window ->
                    val handle = runtime.wm.handleOf(window) ?: return@items
                    val name = runtime.displayName(window.appId, window.title)
                    val number = handle.substringAfterLast('-')
                    val title = window.title.takeIf { it.isNotEmpty() && it != name }
                    MenuItem(
                        label = "#$number · $name",
                        detail = title,
                        clickLabel = "Return to $name${title?.let { ", $it" } ?: ""}, window $number",
                    ) {
                        if (runtime.openWindow(window)) state.homeMenuOpen = false
                    }
                }
            }
        }
    }
}

@Composable
private fun Menu(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.overlay)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(min = 240.dp, max = 360.dp)
                .frame(2.dp, Palette.accent, Palette.background, radius = 20.dp)
                .clickable(enabled = false) {}
                .padding(8.dp),
        ) { content() }
    }
}

@Composable
private fun MenuItem(
    label: String,
    detail: String? = null,
    clickLabel: String? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = clickLabel, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column {
            BasicText(
                label,
                style = Mono.copy(fontSize = TextSize.Item),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                BasicText(
                    detail,
                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
