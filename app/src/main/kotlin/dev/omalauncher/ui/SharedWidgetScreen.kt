package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.omalauncher.widgets.WidgetEngine
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.market.Paste
import dev.omalauncher.widgets.market.Share
import dev.omalauncher.widgets.market.ShareError
import dev.omalauncher.widgets.market.Shared
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A widget someone shared as a link or QR code (widgets/market/Share.kt). Nobody reviewed it, so this is
 * the store's widget page with that said first: what it is, what it installs, every command it runs and
 * what in them looks risky, the whole file a tap away, and an agent to read it for you. Only "add to page" puts it on the home screen.
 */
@Composable
fun SharedWidgetScreen(
    engine: WidgetEngine,
    link: String,
    page: Int,
    onReview: (path: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // A short link's widget is on the paste site; any other link holds it.
    val result by produceState<Result<Shared>?>(null, link) {
        value =
            withContext(Dispatchers.IO) {
                runCatching { Shared(Share.pasteId(link)?.let { Share.received(Paste.download(it, userAgent())) } ?: Share.decode(link)) }
            }
    }
    var showSource by remember(link) { mutableStateOf(false) }
    OverlayPanel("shared widget", onDismiss, contentWidth = ReadingWidth) {
        val result = result
        if (result == null) {
            if (Share.pasteId(link) != null) BasicText("fetching it from ${Paste.HOST}…", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Body))
            return@OverlayPanel
        }
        val shared = result.getOrNull()
        if (shared == null) {
            val why = (result.exceptionOrNull() as? ShareError)?.message ?: "it can't be read"
            BasicText("This link doesn't hold a widget: $why.", style = Mono.copy(color = Palette.red, fontSize = TextSize.Body))
            return@OverlayPanel
        }
        val p = shared.program
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(shared.name.ifEmpty { "a widget" }, style = Mono.copy(fontSize = TextSize.Title))
            if (p != null) {
                val size = p.size?.let { " · ${it.first}x${it.second}" } ?: ""
                val version = shared.version.takeIf { it.isNotEmpty() }?.let { " · v$it" } ?: ""
                BasicText("shared with you$version$size · ${"%.1f".format(shared.text.toByteArray(Charsets.UTF_8).size / 1024.0)} KB", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                if (shared.description.isNotEmpty()) BasicText(shared.description, style = Mono.copy(fontSize = TextSize.Body))
            }
            Spacer(Modifier.height(4.dp))
            BasicText(
                "Only add it if you trust whoever sent it. It runs the commands below in Arch as you, with your files and the network.",
                Modifier.fillMaxWidth().background(Palette.red.copy(alpha = 0.12f)).padding(8.dp),
                style = Mono.copy(color = Palette.red, fontSize = TextSize.Small),
            )

            when {
                shared.error != null -> BasicText(shared.error.orEmpty(), style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
                p != null && p.level > Compiler.LEVEL ->
                    BasicText("needs a newer launcher (owl ${p.level}); update the launcher to add it", style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
                else -> {
                    val same = engine.sharedWidgetIsSame(shared)
                    when (same) {
                        true -> BasicText("✓ on your home screen", style = Mono.copy(color = Palette.green))
                        false ->
                            BracketAction("replace the ${shared.name} shared before", Palette.accent) {
                                scope.launch {
                                    engine.addSharedWidget(shared, page)
                                    onDismiss()
                                }
                            }
                        null ->
                            BracketAction("add to page $page", Palette.accent) {
                                scope.launch {
                                    engine.addSharedWidget(shared, page)
                                    onDismiss()
                                }
                            }
                    }
                    if (!engine.linux && p?.needsLinux == true) {
                        BasicText(
                            "needs Linux: it runs commands in Arch, so it waits as a placeholder until you install Linux in settings",
                            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                        )
                    }
                }
            }

            if (p != null) {
                Spacer(Modifier.height(4.dp))
                for (risk in shared.risks) BasicText("⚠ $risk", style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
                val packages = shared.requires.entries.joinToString("; ") { (m, l) -> "$m: ${l.joinToString(", ")}" }.ifEmpty { "none" }
                BasicText("installs packages: $packages", style = Mono.copy(fontSize = TextSize.Small))
                BasicText(if (shared.commands.isEmpty()) "runs no commands" else "runs these commands in Arch:", style = Mono.copy(fontSize = TextSize.Small))
                for (c in shared.commands) {
                    BasicText(
                        c.trimEnd(),
                        Modifier.fillMaxWidth().background(Palette.surface).horizontalScroll(rememberScrollState()).padding(6.dp),
                        style = Mono.copy(fontSize = TextSize.Small),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            BasicText(
                if (showSource) "hide the file" else "show the whole file",
                Modifier.clickable { showSource = !showSource }.padding(vertical = 6.dp),
                style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small),
            )
            if (showSource) {
                BasicText(
                    shared.text,
                    Modifier.fillMaxWidth().background(Palette.surface).horizontalScroll(rememberScrollState()).padding(6.dp),
                    style = Mono.copy(fontSize = TextSize.Small),
                )
            }
            BasicText(
                "review with agent",
                Modifier.clickable { scope.launch { onReview(engine.saveForReview(shared)) } }.padding(vertical = 6.dp),
                style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small),
            )
        }
    }
}
