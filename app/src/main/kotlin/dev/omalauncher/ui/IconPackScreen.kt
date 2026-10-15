package dev.omalauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The icon packs on the phone, as other launchers that share them list them: each row shows the pack's own icon,
 * picked with one tap. "the apps' own icons" keeps what Android gives each app; picking a pack redraws every icon,
 * which takes a moment. Installing or removing a pack happens in its own app, like any other launcher's.
 */
@Composable
fun IconPackScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val packs = runtime.iconPacks
    // Packs come and go as any other app does; home refreshes them, but opening the picker should be current.
    LaunchedEffect(Unit) { packs.refresh() }
    val chosen = packs.chosen
    val list = packs.installed
    val context = LocalContext.current
    OverlayPanel("icon pack", onDismiss, contentWidth = ReadingWidth) {
        if (list.isEmpty()) {
            BasicText(
                "no icon packs installed · install one from its own app, then come back",
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Item),
                modifier = Modifier.padding(vertical = 14.dp, horizontal = 4.dp),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                PackRow(
                    label = "the apps' own icons",
                    subtitle = "each app's icon as it ships",
                    preview = "A",
                    icon = null,
                    picked = chosen == null,
                    onPick = {
                        packs.choose(null)
                        runtime.androidApps.redrawIcons()
                    },
                )
            }
            items(list, key = { it.packageName }) { pack ->
                val icon by produceState<ImageBitmap?>(null, pack.packageName) {
                    value = withContext(Dispatchers.IO) { packAppIcon(context, pack.packageName) }
                }
                PackRow(
                    label = pack.label,
                    subtitle = pack.packageName,
                    preview = null,
                    icon = icon,
                    picked = chosen?.packageName == pack.packageName,
                    onPick = {
                        packs.choose(pack)
                        runtime.androidApps.redrawIcons()
                    },
                )
            }
        }
    }
}

/** A pack or the apps' own icons: [preview] in a tile, or [icon]'s bitmap; [picked] marks the one in use. */
@Composable
private fun PackRow(
    label: String,
    subtitle: String,
    preview: String?,
    icon: ImageBitmap?,
    picked: Boolean,
    onPick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LauncherStyle.shape(12.dp))
            .background(if (picked) Palette.accent.copy(alpha = 0.12f) else Palette.surface)
            .clickable(onClick = onPick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(LauncherStyle.shape(10.dp))
                .background(Palette.background),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(icon, label, Modifier.size(40.dp))
            } else if (preview != null) {
                BasicText(preview, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Item))
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            BasicText(label, style = Mono.copy(color = if (picked) Palette.accent else Palette.foreground, fontSize = TextSize.Item))
            BasicText(subtitle, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 1)
        }
        BasicText(if (picked) "● " else "  ", style = Mono.copy(color = Palette.accent))
    }
}

/** [packageName]'s own icon, drawn at a fixed size off the main thread; null if it can't be read. */
private fun packAppIcon(
    context: Context,
    packageName: String,
): ImageBitmap? =
    runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrNull()?.let { drawable ->
        if (drawable is BitmapDrawable) drawable.bitmap.asImageBitmap() else render(drawable)
    }

private fun render(drawable: Drawable): ImageBitmap {
    val size = 96
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(Canvas(bmp))
    return bmp.asImageBitmap()
}
