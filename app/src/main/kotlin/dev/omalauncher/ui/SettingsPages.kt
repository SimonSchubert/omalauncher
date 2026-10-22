package dev.omalauncher.ui

import dev.omalauncher.widgets.lang.OwlKind
import dev.omalauncher.widgets.checkSecretsCommand
import dev.omalauncher.widgets.publishWidgetCommand
import dev.omalauncher.widgets.WidgetSpec
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState

/** The screens of the settings activity; each can also be opened on its own, from home or a widget. */
enum class SettingsPage {
    Settings,
    Style,

    /** Home's pages, to arrange, edit and remove. */
    Pages,

    /** The community themes, over the style screen or from the home menu's theme strip. */
    Themes,
    Agents,
    Store,

    /** The store on its faces, from the empty page left of page 1. */
    FaceStore,
    Packages,

    /** Install, progress, remove; opened by anything that needs Linux. */
    Linux,
    Licenses,
    Gestures,
}

/** Opens [page] over the ones in [stack]; one that's already open comes back to the top, closing what's over it. */
fun SnapshotStateList<SettingsPage>.push(page: SettingsPage) {
    val at = indexOf(page)
    if (at >= 0) removeRange(at + 1, size) else add(page)
}

/** Closes [page] and whatever was opened over it. */
fun SnapshotStateList<SettingsPage>.pop(page: SettingsPage) {
    val at = indexOf(page)
    if (at >= 0) removeRange(at, size)
}

/**
 * The open settings screens, each drawn over the one that opened it, as they were
 * over home. [homePage] is the home page the store puts widgets on. Closing the
 * first one calls [onClose] instead, so it stays drawn until the window goes.
 */
@Composable
fun SettingsPages(
    runtime: Runtime,
    stack: SnapshotStateList<SettingsPage>,
    homePage: Int,
    onClose: () -> Unit,
) {
    val close = { page: SettingsPage -> if (stack.indexOf(page) == 0) onClose() else stack.pop(page) }
    // The screens' own back handlers (a store page, a license) come later, so they go first.
    BackHandler(enabled = stack.isNotEmpty()) { close(stack.last()) }
    Box(Modifier.fillMaxSize().background(Palette.background)) {
        stack.toList().forEachIndexed { i, page ->
            key(page) {
                // One opened from another has a chevron back to it instead of the ✕.
                CompositionLocalProvider(LocalPanelBack provides if (i == 0) null else ({ close(page) })) {
                    SettingsPage(runtime, page, stack, homePage, onDismiss = { close(page) })
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    runtime: Runtime,
    page: SettingsPage,
    stack: SnapshotStateList<SettingsPage>,
    homePage: Int,
    onDismiss: () -> Unit,
) {
    val close = onDismiss
    val linuxInstalled = runtime.bootstrap.state.collectAsState().value == LinuxState.Installed

    // Settings has no home underneath: the agent opens in its own terminal, or the agents screen offers one.
    fun agent(
        title: String,
        command: (String) -> String,
    ) {
        val agent = runtime.agents.default ?: return stack.push(SettingsPage.Agents)
        runtime.terminal(command(agent.bin), title, agent)
    }

    when (page) {
        SettingsPage.Settings ->
            SettingsScreen(
                runtime,
                onDismiss = close,
                onStyle = { stack.push(SettingsPage.Style) },
                onPages = { stack.push(SettingsPage.Pages) },
                onAgents = { stack.push(SettingsPage.Agents) },
                onPackages = { stack.push(SettingsPage.Packages) },
                onLinux = { stack.push(SettingsPage.Linux) },
                onLicenses = { stack.push(SettingsPage.Licenses) },
                onGestures = { stack.push(SettingsPage.Gestures) },
            )
        SettingsPage.Style -> StyleScreen(runtime, onDismiss = close, onBrowse = { stack.push(SettingsPage.Themes) }, onAgent = ::agent)
        SettingsPage.Pages -> {
            var sharing by remember { mutableStateOf<WidgetSpec?>(null) }
            PagesScreen(runtime, onDismiss = close, onAgent = ::agent, onShare = { sharing = it })
            sharing?.let { spec ->
                ShareWidgetScreen(
                    spec,
                    // Only your own: one from the store or from someone else isn't yours to submit.
                    onPublish =
                        if (runtime.widgets.isOwnWidget(spec)) {
                            {
                                sharing = null
                                agent("Publish ${spec.name}") { publishWidgetCommand(it, spec) }
                            }
                        } else {
                            null
                        },
                    onCheck = {
                        sharing = null
                        agent("Check ${spec.name}") { checkSecretsCommand(it, spec) }
                    },
                    onDismiss = { sharing = null },
                )
            }
        }
        SettingsPage.Themes -> ThemeCatalogScreen(runtime.themes, onDismiss = close)
        // Agents install into Linux: every way here (a widget's edit, gestures, open("agents")) offers Linux first.
        SettingsPage.Agents ->
            if (!linuxInstalled) {
                LinuxScreen(runtime, onDismiss = close)
            } else {
                AgentsScreen(
                    runtime,
                    onDismiss = close,
                    onOpen = { agent ->
                        close()
                        runtime.terminal(agent.command, agent.name, agent)
                    },
                )
            }
        SettingsPage.Store -> StoreScreen(runtime.widgets, runtime.agents.installed, homePage, runtime.themes.picked, onDismiss = close, onOpenApp = runtime::openOwlApp)
        SettingsPage.FaceStore -> StoreScreen(runtime.widgets, runtime.agents.installed, homePage, runtime.themes.picked, onDismiss = close, initialKind = OwlKind.Face, onOpenApp = runtime::openOwlApp)
        SettingsPage.Packages -> PackagesScreen(runtime, onDismiss = close)
        SettingsPage.Linux -> LinuxScreen(runtime, onDismiss = close)
        SettingsPage.Licenses -> LicensesScreen(onDismiss = close)
        SettingsPage.Gestures -> GesturesScreen(runtime, onDismiss = close, onAgent = ::agent)
    }
}
