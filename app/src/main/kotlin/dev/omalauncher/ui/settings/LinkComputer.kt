package dev.omalauncher.ui.settings

import android.view.WindowManager
import java.text.DateFormat
import java.util.Date
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.omalauncher.R
import dev.omalauncher.Runtime
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import dev.omalauncher.ui.store.QrCode
import dev.omalauncher.ui.store.qrCode
import dev.omalauncher.linux.ComputerLink
import dev.omalauncher.linux.DeviceSync
import dev.omalauncher.widgets.lang.OwlKind
import kotlinx.coroutines.launch
import dev.omalauncher.net.LocalNetwork
import dev.omalauncher.ui.BracketAction
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.OverlayPanel
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.ReadingWidth
import dev.omalauncher.ui.TextSize
import dev.omalauncher.ui.card
import dev.omalauncher.ui.frame
import dev.omalauncher.ui.home.LinuxPhase
import dev.omalauncher.ui.home.linuxPhase
import dev.omalauncher.ui.rememberAskPermission

/**
 * A computer's link code, scanned (see [ComputerLink]): who asks, what it may do once linked, and the code its screen
 * shows, to compare, since a link sent by someone else looks the same. Linked only on the user's tap.
 */
@Composable
fun LinkComputerScreen(
    runtime: Runtime,
    link: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val invite = remember(link) { ComputerLink.Invite.parse(link) }
    var done by remember(link) { mutableStateOf(false) }
    val linkIt = {
        invite?.let(runtime.computerLink::link)
        done = true
    }
    // Android 17 keeps incoming connections from the local network out without it; linked either way, it can be allowed later.
    val askLocalNetwork = rememberAskPermission(LocalNetwork.PERMISSION) { linkIt() }
    // Only an absent Linux stops it: one still starting, or stopped, runs sshd once it's up.
    val noLinux = linuxPhase(runtime) == LinuxPhase.Absent
    OverlayPanel(stringResource(R.string.link_title), onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                invite == null -> BasicText(stringResource(R.string.link_unreadable), style = Mono.copy(color = Palette.red, fontSize = TextSize.Body))
                done -> {
                    BasicText(stringResource(R.string.link_done, invite.name), style = Mono.copy(color = Palette.green, fontSize = TextSize.Body))
                    BracketAction(stringResource(R.string.link_close), Palette.accent, onClick = onDismiss)
                }
                noLinux -> BasicText(stringResource(R.string.link_needs_linux), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Body))
                else -> {
                    BasicText(stringResource(R.string.link_ask, invite.name), style = Mono.copy(fontSize = TextSize.Title))
                    BasicText(stringResource(R.string.link_what), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Body))
                    BasicText(stringResource(R.string.link_compare, invite.name), style = Mono.copy(color = Palette.red, fontSize = TextSize.Body))
                    BasicText(invite.code, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title))
                    Row {
                        BracketAction(stringResource(R.string.link_confirm), Palette.accent) {
                            if (LocalNetwork.missing(context)) askLocalNetwork() else linkIt()
                        }
                        BracketAction(stringResource(R.string.linux_cancel), Palette.muted, onClick = onDismiss)
                    }
                }
            }
        }
    }
}

/**
 * Settings › linked computers: whether the phone can be reached, how to link a computer (it starts there, with a QR code
 * this phone scans), and the linked ones, each with a way to unlink it.
 */
@Composable
fun LinkedComputersScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
    onLinux: () -> Unit,
) {
    val link = runtime.computerLink
    val computers = link.computers
    val phase = linuxPhase(runtime)
    OverlayPanel(stringResource(R.string.link_computers_title), onDismiss, contentWidth = ReadingWidth) {
        // Scrolls: a computer's widget list can be long.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(stringResource(R.string.link_computers_about), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Body))
            if (phase == LinuxPhase.Absent) {
                BasicText(stringResource(R.string.link_needs_linux), style = Mono.copy(color = Palette.red, fontSize = TextSize.Body))
                BracketAction(stringResource(R.string.settings_linux), Palette.accent, onClick = onLinux)
                return@Column
            }
            if (link.paused) {
                Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText(stringResource(R.string.link_paused), style = Mono.copy(color = Palette.red, fontSize = TextSize.Body))
                    BracketAction(stringResource(R.string.link_resume), Palette.accent) { link.pause(false) }
                }
            } else {
                Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText(stringResource(R.string.link_computers_how), style = Mono.copy(fontSize = TextSize.Item))
                    // Where the plugin shows its code; the command is for the plugin's README, not this screen.
                    BasicText(stringResource(R.string.link_computers_how_body), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Body))
                    ShowCode(runtime)
                }
            }
            val following = runtime.deviceSync.devices
            if (computers.isNotEmpty() || following.isNotEmpty()) {
                Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Whether devices can reach this one: only says something while some may (it follows the others itself).
                    // Beside it, the pause for all of them at once: they stay connected, and nothing reaches or leaves this device.
                    if (!link.paused) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicText(
                                if (computers.isEmpty()) "" else stringResource(if (link.listening) R.string.link_computers_listening else R.string.link_computers_waiting, ComputerLink.PORT),
                                Modifier.weight(1f),
                                style = Mono.copy(color = if (link.listening) Palette.green else Palette.muted, fontSize = TextSize.Small),
                            )
                            BracketAction(stringResource(R.string.link_pause), Palette.muted) { link.pause(true) }
                        }
                    }
                    for (c in computers) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BasicText(c.name, Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item))
                                BracketAction(stringResource(R.string.link_unlink), Palette.red) { link.unlink(c.id) }
                            }
                            // Both ways: it may switch the phone's theme (and background), and it follows the phone's.
                            SyncRow(R.string.link_sync_theme, c.syncTheme) { link.setSyncTheme(c.id, it) }
                            SyncRow(R.string.link_sync_background, c.syncBackground) { link.setSyncBackground(c.id, it) }
                            // Widgets kept the same on both, files and values: ticked here or in the computer's panel.
                            WidgetPicker(runtime, c.id, c.widgets) { file, on -> link.setSyncWidget(c.id, file, on) }
                        }
                    }
                    // Devices this phone follows (it showed the code): their settings are kept there, and asked for.
                    for (d in following) {
                        val sync = runtime.deviceSync
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BasicText(d.name, Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item))
                                BracketAction(stringResource(R.string.link_unlink), Palette.red) { sync.disconnect(d.id) }
                            }
                            val s = d.sync
                            if (s == null) {
                                // Paused, it isn't asked.
                                if (!link.paused) BasicText(stringResource(R.string.link_device_asking), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                            } else {
                                SyncRow(R.string.link_sync_theme, s.optBoolean("theme")) { sync.setSync(d.id, "theme", it) }
                                SyncRow(R.string.link_sync_background, s.optBoolean("background")) { sync.setSync(d.id, "background", it) }
                                WidgetPicker(runtime, "device-" + d.id, DeviceSync.widgetsOf(s)) { file, on -> sync.setWidget(d.id, file, on) }
                            }
                        }
                    }
                }
            }
            if (computers.isNotEmpty() || following.isNotEmpty() || link.activity.isNotEmpty()) ActivityList(link.activity)
        }
    }
}

/** What connected devices did here lately, newest first (ComputerLink.activity), folded away until asked for. */
@Composable
private fun ActivityList(activity: List<ComputerLink.Activity>) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            BasicText(stringResource(R.string.link_activity), Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item))
            BasicText(if (open) "▴" else "▾", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Body))
        }
        if (!open) return@Column
        if (activity.isEmpty()) BasicText(stringResource(R.string.link_activity_none), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Body))
        val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
        for (a in activity) {
            Column {
                BasicText(format.format(Date(a.at)) + " · " + a.device, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                // English, as the requests are named: a log's lines.
                BasicText(a.what, style = Mono.copy(fontSize = TextSize.Body))
            }
        }
    }
}

/**
 * Which widgets sync with [computer]: this phone's, plus any synced from the computer that the phone hasn't got yet,
 * each ticked or not. Folded away behind how many are synced, since a phone may have dozens.
 */
@Composable
private fun WidgetPicker(
    runtime: Runtime,
    key: String,
    synced: List<String>,
    onSet: (file: String, on: Boolean) -> Unit,
) {
    var open by remember(key) { mutableStateOf(false) }
    val specs = runtime.widgets.specs.filter { ComputerLink.validName(it.file.name) }.associateBy { it.file.name }
    fun title(file: String) = specs[file]?.name?.ifEmpty { null } ?: file.removeSuffix(".owl")
    // By the name shown, whatever its case; apps and faces say what they are.
    val names = (specs.keys + synced).distinct().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { title(it) })
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
        BasicText(stringResource(R.string.link_sync_widgets), Modifier.weight(1f), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Body))
        BasicText(
            pluralStringResource(R.plurals.link_sync_widgets_count, synced.size, synced.size) + if (open) "  ▴" else "  ▾",
            style = Mono.copy(color = Palette.accent, fontSize = TextSize.Body),
        )
    }
    if (!open) return
    for (file in names) {
        val on = file in synced
        Row(
            Modifier.fillMaxWidth().clickable { onSet(file, !on) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(if (on) "[x] " else "[ ] ", style = Mono.copy(color = if (on) Palette.accent else Palette.muted, fontSize = TextSize.Body))
            BasicText(title(file), Modifier.weight(1f, fill = false), style = Mono.copy(fontSize = TextSize.Body), maxLines = 1)
            // The store's names for them: an app, a full-screen widget.
            val kind =
                when (specs[file]?.kind) {
                    OwlKind.App -> stringResource(R.string.store_kind_app)
                    OwlKind.Face -> stringResource(R.string.store_kind_face)
                    else -> null
                }
            if (kind != null) BasicText("  [$kind]", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 1)
        }
    }
}

/**
 * This phone's code, for another device to scan (DeviceSync): made in Linux, shown with the digits the other device
 * shows to compare, while it waits for that device; this phone then follows it.
 */
@Composable
private fun ShowCode(runtime: Runtime) {
    val sync = runtime.deviceSync
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf<DeviceSync.Invite?>(null) }
    var connected by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { if (step == "waiting") sync.cancel() } }
    val context = LocalContext.current
    val start = {
        step = "making"
        scope.launch {
            val made = sync.invite()
            invite = made
            if (made == null) {
                step = "failed"
                return@launch
            }
            step = "waiting"
            connected = sync.await()
            step = if (connected != null) "done" else "failed"
        }
        Unit
    }
    // This phone connects out to the other one, which Android 17 keeps off the local network without it; tried either way.
    val askLocalNetwork = rememberAskPermission(LocalNetwork.PERMISSION) { start() }
    // An agent asked for it (`omalauncher devices connect`): as if the user had tapped show a code.
    LaunchedEffect(sync.showCode) {
        if (sync.showCode && step == "") {
            sync.showCode = false
            if (LocalNetwork.missing(context)) askLocalNetwork() else start()
        }
    }
    when (step) {
        "" ->
            BracketAction(stringResource(R.string.link_show_code), Palette.accent) {
                if (LocalNetwork.missing(context)) askLocalNetwork() else start()
            }
        "making" -> BasicText(stringResource(R.string.link_code_making), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Body))
        "waiting" -> {
            val code = invite ?: return
            val matrix = remember(code) { qrCode(code.link) }
            // The code holds the secret that proves this phone to the device that scans it: kept out of screenshots,
            // recordings and the recents picture while it shows.
            val window = LocalActivity.current?.window
            DisposableEffect(window) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            }
            // Black on white whatever the theme: what cameras read best.
            QrCode(matrix, Modifier.fillMaxWidth(0.8f).aspectRatio(1f))
            BasicText(stringResource(R.string.link_code_scan), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Body))
            BasicText(code.code, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(stringResource(R.string.link_code_waiting), Modifier.weight(1f), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                BracketAction(stringResource(R.string.linux_cancel), Palette.muted) {
                    sync.cancel()
                    step = ""
                }
            }
        }
        "done" -> {
            BasicText(stringResource(R.string.link_code_done, connected.orEmpty()), style = Mono.copy(color = Palette.green, fontSize = TextSize.Body))
            BracketAction(stringResource(R.string.link_close), Palette.accent) { step = "" }
        }
        else -> {
            BasicText(stringResource(R.string.link_code_failed), style = Mono.copy(color = Palette.red, fontSize = TextSize.Body))
            BracketAction(stringResource(R.string.link_show_code), Palette.accent) { step = "" }
        }
    }
}

/** One of a linked computer's switches, as the style screen's: [label], then sync or off, the one in effect filled in. */
@Composable
private fun SyncRow(
    label: Int,
    on: Boolean,
    set: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(stringResource(label), Modifier.weight(1f), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Body))
        Row(Modifier.frame(1.dp, Palette.border, radius = 12.dp)) {
            for ((value, text) in listOf(true to R.string.link_sync_on, false to R.string.link_sync_off)) {
                val active = value == on
                BasicText(
                    stringResource(text),
                    Modifier
                        .background(if (active) Palette.accent else Color.Transparent)
                        .clickable { if (!active) set(value) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    style = Mono.copy(color = if (active) Palette.background else Palette.foreground, fontSize = TextSize.Body),
                    softWrap = false,
                )
            }
        }
    }
}

/** What the settings row says: how many are linked, or that none is. */
@Composable
fun linkedComputersStatus(runtime: Runtime): String {
    val n = runtime.computerLink.computers.size
    return if (n == 0) stringResource(R.string.link_computers_none) else pluralStringResource(R.plurals.link_computers_count, n, n)
}
