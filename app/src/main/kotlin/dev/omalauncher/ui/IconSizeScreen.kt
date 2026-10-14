package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

/**
 * How big app icons draw, as other launchers let you pick: each row previews the drawer's icon slot
 * at that size. Choosing one redraws every icon, which takes a moment.
 */
@Composable
fun IconSizeScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    OverlayPanel("icon size", onDismiss, contentWidth = ReadingWidth) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(IconSize.sizes, key = { it.first }) { (scale, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(LauncherStyle.shape(12.dp))
                        .background(if (IconSize.scale == scale) Palette.accent.copy(alpha = 0.12f) else Palette.surface)
                        .clickable {
                            IconSize.choose(context, scale)
                            runtime.androidApps.redrawIcons()
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(LauncherStyle.shape(10.dp))
                            .background(Palette.background),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(24.dp * scale)
                                .clip(LauncherStyle.shape(12.dp * scale))
                                .background(if (IconSize.scale == scale) Palette.accent else Palette.muted),
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        BasicText(
                            label,
                            style = Mono.copy(color = if (IconSize.scale == scale) Palette.accent else Palette.foreground, fontSize = TextSize.Item),
                        )
                        BasicText(
                            "${(52 * scale).toInt()} dp",
                            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                        )
                    }
                    BasicText(if (IconSize.scale == scale) "● " else "  ", style = Mono.copy(color = Palette.accent))
                }
            }
        }
    }
}
