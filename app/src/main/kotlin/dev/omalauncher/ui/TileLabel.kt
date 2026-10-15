package dev.omalauncher.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.omalauncher.theme.Style

enum class LabelTone { Light, Dark }

/** The name under an app tile's icon. */
@Composable
internal fun TileLabel(text: String) {
    val style = Mono.copy(fontSize = TextSize.Small, textAlign = TextAlign.Center)
    BasicText(
        text,
        style = if (LauncherStyle.bareAppIcons) style.inked(if (LauncherStyle.style[Style.LABELS] == "dark") LabelTone.Dark else LabelTone.Light) else style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun TextStyle.inked(tone: LabelTone): TextStyle {
    val (ink, halo) =
        when (tone) {
            LabelTone.Light -> Color.White to Color.Black
            LabelTone.Dark -> minOf(Palette.background, Palette.foreground, compareBy { it.luminance() }) to Color.White
        }
    return copy(color = ink, shadow = Shadow(halo.copy(alpha = 0.7f), blurRadius = 6f))
}
