package dev.omalauncher.ui

import android.content.Intent
import android.os.Build
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.taskbar.TaskbarMode
import dev.omalauncher.taskbar.TaskbarStatus
import dev.omalauncher.ui.splitdrag.SplitDrag

/**
 * Taskbar settings: on or off, a warning while its accessibility service is off (Android lets only the user turn it
 * on), what it lists, and which apps are pinned to it.
 */
@Composable
fun TaskbarScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val taskbar = runtime.taskbar
    val supported = Build.VERSION.SDK_INT >= 35
    OverlayPanel("taskbar", onDismiss, contentWidth = ReadingWidth) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "on") {
                SettingRow(
                    "taskbar",
                    if (!supported) "needs Android 15" else "a handle at the bottom of other apps: switch apps, drag one to split the screen",
                    if (taskbar.enabled) "on" else "off",
                ) { if (supported) taskbar.enabled = !taskbar.enabled }
            }
            if (taskbar.enabled && !TaskbarStatus.serviceOn) {
                item(key = "service") {
                    SettingRow("turn on Omalauncher taskbar in accessibility", "Android lets only you turn it on; it reads which apps are on screen, no text", "open", warning = true) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }
            item(key = "mode") {
                SettingRow("shows", taskbar.mode.label, "change") {
                    taskbar.mode = if (taskbar.mode == TaskbarMode.Recent) TaskbarMode.PinnedAndRecent else TaskbarMode.Recent
                }
            }
            if (taskbar.mode == TaskbarMode.PinnedAndRecent) {
                val apps = runtime.androidApps.apps.filter { SplitDrag.canDrag(it) }.sortedBy { it.label.lowercase() }
                items(apps, key = { it.key }) { app ->
                    val pinned = app.key in taskbar.pinned
                    SettingRow(app.label, if (pinned) "pinned #${taskbar.pinned.indexOf(app.key) + 1}" else "not pinned", if (pinned) "●" else "○") {
                        taskbar.togglePin(app.key)
                    }
                }
            }
        }
    }
}

/** A settings row: [title] and [subtitle] on the left, [value] in the accent on the right; tapping runs [onClick]. */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    value: String,
    warning: Boolean = false,
    onClick: () -> Unit,
) {
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
            BasicText(title, style = Mono.copy(color = if (warning) Palette.red else Palette.foreground, fontSize = TextSize.Item))
            BasicText(subtitle, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
        BasicText(value, Modifier.padding(start = 12.dp), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
    }
}
