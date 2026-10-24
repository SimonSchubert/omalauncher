package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import dev.omalauncher.widgets.lang.OwlKind
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
 * what in them looks risky, the whole file a tap away, and an agent to read it for you. Only "add to page" puts it on the home screen
 * (for an app, "add to app drawer" puts it in the drawer).
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
    // An app goes in the drawer rather than on a page, and a face left of page 1; known from its first word, even before it compiles.
    val kind = result?.getOrNull()?.let { OwlKind.of(it.text) } ?: OwlKind.Widget
    val app = kind == OwlKind.App
    OverlayPanel(UiText.t("shared %s", UiText.t(kind.word)), onDismiss, contentWidth = ReadingWidth) {
        val result = result
        if (result == null) {
            if (Share.pasteId(link) != null) BasicText(UiText.t("fetching it from %s…", Paste.HOST), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Body))
            return@OverlayPanel
        }
        val shared = result.getOrNull()
        if (shared == null) {
            val why = (result.exceptionOrNull() as? ShareError)?.message ?: UiText.t("it can't be read")
            BasicText(UiText.t("This link doesn't hold a widget: %s.", why), style = Mono.copy(color = Palette.red, fontSize = TextSize.Body))
            return@OverlayPanel
        }
        val p = shared.program
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(shared.name.ifEmpty { if (app) UiText.t("an app") else UiText.t("a widget") }, style = Mono.copy(fontSize = TextSize.Title))
            if (p != null) {
                val size = p.size?.let { " · ${it.first}x${it.second}" } ?: ""
                val version = shared.version.takeIf { it.isNotEmpty() }?.let { " · v$it" } ?: ""
                BasicText(UiText.t("shared with you%s%s · %s KB", version, size, "%.1f".format(shared.text.toByteArray(Charsets.UTF_8).size / 1024.0)), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                if (shared.description.isNotEmpty()) BasicText(shared.description, style = Mono.copy(fontSize = TextSize.Body))
            }
            Spacer(Modifier.height(4.dp))
            BasicText(
                UiText.t("Only add it if you trust whoever sent it. It runs the commands below in Arch as you, with your files and the network."),
                Modifier.fillMaxWidth().background(Palette.red.copy(alpha = 0.12f)).padding(8.dp),
                style = Mono.copy(color = Palette.red, fontSize = TextSize.Small),
            )

            when {
                shared.error != null -> BasicText(shared.error.orEmpty(), style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
                p != null && p.level > Compiler.LEVEL ->
                    BasicText(UiText.t("needs a newer launcher (owl %s); update the launcher to add it", p.level), style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
                else -> {
                    val same = engine.sharedWidgetIsSame(shared)
                    when (same) {
                        true -> AddedMark(kind)
                        false ->
                            BracketAction(UiText.t("replace the %s shared before", shared.name), Palette.accent) {
                                scope.launch {
                                    engine.addSharedWidget(shared, page)
                                    onDismiss()
                                }
                            }
                        null ->
                            BracketAction(
                                when (kind) {
                                    OwlKind.App -> UiText.t("add to app drawer")
                                    OwlKind.Face -> UiText.t("add as a face")
                                    OwlKind.Widget -> UiText.t("add to page %s", page)
                                    OwlKind.Panel -> UiText.t("add to gesture panels")
                                    OwlKind.Keybar -> UiText.t("add to your widgets folder")
                                },
                                Palette.accent,
                            ) {
                                scope.launch {
                                    engine.addSharedWidget(shared, page)
                                    onDismiss()
                                }
                            }
                    }
                    if (!engine.linux && p?.needsLinux == true) NeedsLinuxNote(app)
                }
            }

            if (p != null) {
                Spacer(Modifier.height(4.dp))
                for (risk in shared.risks) BasicText("⚠ $risk", style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
                InstallDisclosure(shared.requires, shared.commands)
            }

            Spacer(Modifier.height(4.dp))
            BasicText(
                UiText.t(if (showSource) "hide the file" else "show the whole file"),
                Modifier.clickable { showSource = !showSource }.padding(vertical = 6.dp),
                style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small),
            )
            if (showSource) {
                CodeBlock(shared.text)
            }
            BasicText(
                UiText.t("review with agent"),
                Modifier.clickable { scope.launch { onReview(engine.saveForReview(shared)) } }.padding(vertical = 6.dp),
                style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small),
            )
        }
    }
}
