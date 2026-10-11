package dev.omalauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dev.omalauncher.widgets.Shortcut

/**
 * A pinned app: its icon (a silhouette in the accent color, or full color), framed
 * like a widget, or bare on the wallpaper like a stock launcher (LauncherStyle.bareAppIcons).
 * Icons only: a 1x1 cell is about a sixth of the screen, so a height cutoff showed the
 * name on some phones and hid it on others.
 */
@Composable
fun ShortcutTile(
    shortcut: Shortcut,
    /** From the app catalogs, as the drawer shows it: a silhouette when [LauncherStyle.monochromeIcons]; null for initials. */
    icon: ImageBitmap?,
    focused: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val mono = LauncherStyle.monochromeIcons
    val bare = LauncherStyle.bareAppIcons
    BoxWithConstraints(
        modifier
            .tileFrame(focused)
            .pointerInput(shortcut.key) { detectTapGestures(onTap = { onTap() }) }
            .padding(4.dp),
    ) {
        // Bare icons have no frame to fill, so they take nearly the whole cell.
        val iconSize = min(maxWidth, maxHeight) * if (bare) 0.95f else 0.85f
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val bmp = icon
            when {
                // Bare silhouettes and initials sit on a badge, as in the drawer, so they read on any wallpaper.
                bare && (mono || bmp == null) ->
                    Box(
                        Modifier.size(iconSize).clip(LauncherStyle.shape(iconSize / 2)).background(Palette.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bmp != null) {
                            Image(bmp, shortcut.label, Modifier.size(iconSize * 0.75f), colorFilter = ColorFilter.tint(Palette.accent))
                        } else {
                            BasicText(initials(shortcut.label), style = Mono.copy(color = Palette.accent, fontSize = (iconSize.value * 0.35f).sp))
                        }
                    }
                bmp != null ->
                    Image(bmp, shortcut.label, Modifier.size(iconSize), colorFilter = if (mono) ColorFilter.tint(Palette.accent) else null)
                else ->
                    // Apps without an icon get their initials.
                    Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
                        BasicText(initials(shortcut.label), style = Mono.copy(color = Palette.accent, fontSize = (iconSize.value * 0.45f).sp))
                    }
            }
        }
    }
}

/** An app tile's frame, or none for bare icons unless [focused]. */
internal fun Modifier.tileFrame(focused: Boolean) =
    when {
        !LauncherStyle.bareAppIcons -> tile(focused)
        // Bare icons still show which tile is focused or being edited.
        focused -> frame(2.dp, Palette.accent)
        else -> this
    }

internal fun initials(name: String): String {
    val words = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> "${words[0][0]}${words[1][0]}".uppercase()
        words.isNotEmpty() -> words[0].take(2).replaceFirstChar(Char::uppercase)
        else -> "?"
    }
}
