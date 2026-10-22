package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.omalauncher.packageinstall.PendingDeviceAction

/** Details shown before the host performs a guest-requested Android action. */
@Composable
fun DeviceActionScreen(
    request: PendingDeviceAction,
    error: String?,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText("device action requested", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title))
        BasicText("A Linux agent asked Omalauncher to act on the Android device.", style = Mono.copy(color = Palette.muted))
        BasicText(request.request.action, style = Mono.copy(fontSize = TextSize.Item))
        BasicText("target", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        CodeBlock(request.request.target)
        request.request.reason.takeIf { it.isNotEmpty() }?.let {
            BasicText("agent reason", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            BasicText(it, style = Mono.copy(fontSize = TextSize.Small))
        }
        BasicText(
            "This is an allowlisted Android action, not raw ADB or a shell command. Android may decline an adjacent split or the target app may ask for its own confirmation.",
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
        error?.let { BasicText(it, style = Mono.copy(color = Palette.red, fontSize = TextSize.Small)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            BracketAction("reject", Palette.red, onReject)
            BracketAction("approve", Palette.green, onApprove)
        }
    }
}
