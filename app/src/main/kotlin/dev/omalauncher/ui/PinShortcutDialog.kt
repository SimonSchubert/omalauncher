package dev.omalauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asks before a shortcut an app made goes on the home screen: its icon and name, and the app that
 * asked, since that app is what opens it, whatever the shortcut calls itself.
 */
@Composable
internal fun PinShortcutDialog(
    label: String,
    /** The app that asked; null if the drawer doesn't list it (yet). */
    from: String?,
    /** Blocks; called off the main thread. */
    loadIcon: () -> ImageBitmap?,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val icon by produceState<ImageBitmap?>(null) { value = withContext(Dispatchers.IO) { loadIcon() } }
    DialogFrame(onDismiss) {
        BasicText("add to home screen?", style = Mono.copy(fontSize = TextSize.Title))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            icon?.let { Image(it, label, Modifier.size(52.dp)) }
                ?: IconBadge(52.dp, Palette.background) {
                    BasicText(initials(label), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title))
                }
            Column {
                BasicText(label, style = Mono.copy(fontSize = TextSize.Large), maxLines = 2, overflow = TextOverflow.Ellipsis)
                from?.let { BasicText("from $it", style = Mono.copy(color = Palette.muted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            BracketAction("cancel", Palette.muted, onClick = onDismiss)
            BracketAction("add", Palette.accent, onClick = onAdd)
        }
    }
}
