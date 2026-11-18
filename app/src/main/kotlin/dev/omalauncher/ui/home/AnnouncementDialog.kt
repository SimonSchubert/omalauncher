package dev.omalauncher.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import java.util.Locale
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.sp
import dev.omalauncher.net.Announcements
import dev.omalauncher.ui.frame
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.system.openUrl
import dev.omalauncher.ui.BracketAction
import dev.omalauncher.ui.DialogFrame
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.TextSize

/**
 * A message from the developer (see [dev.omalauncher.net.Announcements]), shown once over settings: its title, text and
 * buttons, a "don't show news again" box and close. However it's closed, it doesn't come back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnnouncementDialog(runtime: Runtime) {
    val news = runtime.announcements
    val message = news.current ?: return
    val context = LocalContext.current
    var off by remember(message.id) { mutableStateOf(false) }
    // However it's closed: with the box ticked, no message shows again; otherwise just this one doesn't.
    fun close() = if (off) news.turnOff() else news.dismiss(message)
    DialogFrame(::close) {
        // The message scrolls, the box and close stay: a long text, a large font or landscape can't push them off screen.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicText(message.title, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title))
                message.countdown?.let { CountdownBlock(it) }
                if (message.body.isNotEmpty()) BasicText(message.body, style = Mono.copy(fontSize = TextSize.Large))
                if (message.buttons.isNotEmpty()) {
                    FlowRow {
                        for (b in message.buttons) {
                            BracketAction(b.label, Palette.accent) {
                                close()
                                context.openUrl(b.url)
                            }
                        }
                    }
                }
            }
            Row(
                Modifier
                    .toggleable(off, role = Role.Checkbox) { off = it }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(if (off) "[x] " else "[ ] ", style = Mono.copy(color = if (off) Palette.accent else Palette.muted))
                BasicText(stringResource(R.string.home_news_off), style = Mono.copy(color = if (off) Palette.foreground else Palette.muted))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BracketAction(stringResource(R.string.home_news_close), Palette.foreground, onClick = ::close)
            }
        }
    }
}

/** The time left until [countdown], ticking each second: big numbers in accent with their units under them. */
@Composable
private fun CountdownBlock(countdown: Announcements.Countdown) {
    val left by produceState(Duration.between(Instant.now(), countdown.to), countdown) {
        while (true) {
            value = Duration.between(Instant.now(), countdown.to)
            delay(1000 - System.currentTimeMillis() % 1000)
        }
    }
    val s = left.seconds.coerceAtLeast(0)
    // Grows with the phone's font size up to 1.3 times: four of them side by side must still fit a narrow phone.
    val fontScale = LocalDensity.current.fontScale
    val digits = 32.sp * (minOf(fontScale, 1.3f) / fontScale)
    Column(
        Modifier.fillMaxWidth().frame(1.dp, Palette.accent).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (countdown.label.isNotEmpty()) BasicText(countdown.label, style = Mono.copy(color = Palette.muted))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for ((n, unit) in listOf(
                s / 86400 to R.string.home_news_days,
                s / 3600 % 24 to R.string.home_news_hours,
                s / 60 % 60 to R.string.home_news_minutes,
                s % 60 to R.string.home_news_seconds,
            )) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(String.format(Locale.ROOT, "%02d", n), style = Mono.copy(color = Palette.accent, fontSize = digits))
                    BasicText(stringResource(unit), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                }
            }
        }
    }
}
