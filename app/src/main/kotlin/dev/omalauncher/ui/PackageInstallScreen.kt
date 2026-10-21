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
import dev.omalauncher.packageinstall.PendingPackageInstall

/** The details shown before an agent-requested APK reaches Android's installer. */
@Composable
fun PackageInstallScreen(
    request: PendingPackageInstall,
    allowed: Boolean,
    error: String?,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onAllowSource: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText("install requested", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title))
        BasicText("A Linux agent asked Omalauncher to install this APK.", style = Mono.copy(color = Palette.muted))
        BasicText("${request.info.label} · ${request.info.packageName}", style = Mono.copy(fontSize = TextSize.Item))
        BasicText("version ${request.info.versionName.ifEmpty { "unknown" }} (${request.info.versionCode})", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        BasicText("guest path", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        CodeBlock(request.request.guestPath)
        BasicText("APK SHA-256", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        CodeBlock(request.info.sha256)
        BasicText("signing certificate SHA-256", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        CodeBlock(request.info.certificateSha256)
        BasicText("requested permissions", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        BasicText(request.info.permissions.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "none", style = Mono.copy(fontSize = TextSize.Small))
        request.request.reason.takeIf { it.isNotEmpty() }?.let {
            BasicText("agent reason", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            BasicText(it, style = Mono.copy(fontSize = TextSize.Small))
        }
        if (!allowed) {
            BasicText("Android is not allowing Omalauncher to start installs yet.", style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
            BracketAction("allow installs", Palette.accent, onAllowSource)
        }
        error?.let { BasicText(it, style = Mono.copy(color = Palette.red, fontSize = TextSize.Small)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            BracketAction("reject", Palette.red, onReject)
            if (allowed) BracketAction("approve", Palette.green, onApprove)
        }
    }
}
