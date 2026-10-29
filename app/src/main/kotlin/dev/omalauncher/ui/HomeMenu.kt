package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.omalauncher.R
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
                BasicText(createWithLabel(agent?.name), style = Mono.copy(fontSize = TextSize.Item))
                // Pulled left by the buttons' own padding, so the first bracket lines up with the label.
                Row(Modifier.offset(x = (-8).dp)) {
                    // The English name also names the agent's session.
                    val kinds: List<Triple<Int, String, (String) -> String>> =
                        listOf(
                            Triple(R.string.home_create_widget, "widget") { newOwlCommand(it, OwlKind.Widget) },
                            // Faces are made from the empty page left of them, which says where the new one goes.
                            Triple(R.string.home_create_app, "app") { newOwlCommand(it, OwlKind.App) },
                            Triple(R.string.home_create_panel, "panel") { newOwlCommand(it, OwlKind.Panel) },
                        )
                    for ((title, label, command) in kinds) {
                        BracketAction(stringResource(title), Palette.accent) {
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
        MenuItem(stringResource(R.string.home_browse_store)) {
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
    }
}

@Composable
private fun Menu(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.overlay)
            // A tap on the scrim closes the menu; keys move between what's in it, not to the scrim.
            .focusProperties { canFocus = false }
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(min = 240.dp, max = 360.dp)
                .frame(2.dp, Palette.accent, Palette.background, radius = 20.dp)
                .clickable(enabled = false) {}
                .holdsKeys()
                .padding(8.dp),
        ) { content() }
    }
}

@Composable
private fun MenuItem(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) { BasicText(label, style = Mono.copy(fontSize = TextSize.Item)) }
}
