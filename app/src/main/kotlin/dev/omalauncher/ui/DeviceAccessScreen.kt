package dev.omalauncher.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime

/** The explicit opt-in for unattended, agent-requested APK installs. */
@Composable
fun DeviceAccessScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var confirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val installAllowed = runtime.packageInstalls.canRequestPackageInstalls()
    val yolo = runtime.packageInstalls.yoloEnabled

    fun setYolo(enabled: Boolean) {
        if (runtime.packageInstalls.setYoloEnabled(enabled)) {
            confirming = false
            error = null
        } else {
            error = "could not save the setting"
        }
    }

    OverlayPanel("agent device access", onDismiss, contentWidth = ReadingWidth) {
        BasicText(
            if (yolo) "YOLO mode is on" else "agent APK installs ask first",
            style = Mono.copy(color = if (yolo) Palette.red else Palette.accent, fontSize = TextSize.Title),
        )
        BasicText(
            "The guest can build an APK and request an install with omalauncher-install. " +
                "It can also request allowlisted Android actions such as opening an installed app or URL. " +
                if (yolo) "Validated requests skip Omalauncher's approval screen." else "Omalauncher shows the details before acting.",
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
        Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText("YOLO warning", style = Mono.copy(color = Palette.red, fontSize = TextSize.Item))
            BasicText(
                "Any agent process that can write a valid APK request can trigger an install attempt while this is on. " +
                    "The APK is still validated and snapshotted, but this is not a trust boundary.",
                style = Mono.copy(fontSize = TextSize.Small),
            )
            BasicText(
                "This does not expose raw ADB, Android credentials, signing keys or a root shell. " +
                    "Android may still show its own installer confirmation.",
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
        }
        if (!installAllowed) {
            BasicText(
                "Android is not allowing Omalauncher to start installs yet.",
                style = Mono.copy(color = Palette.red, fontSize = TextSize.Small),
            )
            BracketAction("allow installs", Palette.accent) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            }
        }
        error?.let { BasicText(it, style = Mono.copy(color = Palette.red, fontSize = TextSize.Small)) }
        if (yolo) {
            BracketAction("turn off YOLO", Palette.red) { setYolo(false) }
        } else {
            BracketAction("enable YOLO", Palette.red) { confirming = true }
        }
        if (confirming && !yolo) {
            Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("Enable unattended installs?", style = Mono.copy(fontSize = TextSize.Item))
                BasicText(
                    "Only enable this while you trust every agent running in Linux. You can turn it off here at any time.",
                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    BracketAction("cancel", Palette.muted) { confirming = false }
                    BracketAction("enable", Palette.red) { setYolo(true) }
                }
            }
        }
        BasicText(
            "Raw ADB remains unavailable inside the guest. PackageInstaller and the allowlisted device-action bridge are the supported in-phone capabilities.",
            Modifier.padding(top = 8.dp),
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
    }
}
