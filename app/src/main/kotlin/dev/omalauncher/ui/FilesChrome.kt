package dev.omalauncher.ui

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.omalauncher.linux.GuestEntry
import java.io.File

@Composable
internal fun IconButton(
    icon: ImageVector,
    description: String,
    tint: Color = Palette.foreground,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(icon, description, Modifier.size(24.dp), colorFilter = ColorFilter.tint(tint))
    }
}

@Composable
internal fun Fab(
    icon: ImageVector,
    description: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(56.dp).clip(LauncherStyle.shape(16.dp)).background(Palette.accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(icon, description, Modifier.size(24.dp), colorFilter = ColorFilter.tint(Palette.background))
    }
}

/** The path as tappable segments: / › root › .config. */
@Composable
internal fun Breadcrumbs(
    path: String,
    onNavigate: (String) -> Unit,
) {
    val parts = path.split('/').filter { it.isNotEmpty() }
    val crumbs = listOf("/" to "/") + parts.indices.map { i -> parts[i] to "/" + parts.take(i + 1).joinToString("/") }
    val state = rememberLazyListState()
    LaunchedEffect(path) { state.scrollToItem(crumbs.lastIndex) }
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        items(crumbs.size) { i ->
            val (label, target) = crumbs[i]
            val last = i == crumbs.lastIndex
            if (i > 1) Symbol(Glyph.ChevronRight, Palette.muted, Modifier.padding(horizontal = 2.dp))
            BasicText(
                label,
                Modifier.clip(LauncherStyle.shape(8.dp)).clickable(enabled = !last) { onNavigate(target) }.padding(horizontal = 6.dp, vertical = 6.dp),
                style = Mono.copy(color = if (last) Palette.accent else Palette.muted),
            )
        }
    }
}

/** Quick jumps, like stock Files' drawer: home, config, the whole system. */
@Composable
internal fun Places(
    path: String,
    exists: (String) -> Boolean,
    onNavigate: (String) -> Unit,
) {
    val places =
        remember(path) {
            listOf("home" to "/root", "config" to "/root/.config", "downloads" to "/root/Downloads", "system" to "/")
                .filter { exists(it.second) }
        }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(places) { (label, target) ->
            val active = path == target
            BasicText(
                label,
                Modifier
                    .clip(LauncherStyle.shape(16.dp))
                    .background(if (active) Palette.accent else Palette.surface)
                    .clickable { onNavigate(target) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                style = Mono.copy(color = if (active) Palette.background else Palette.foreground),
            )
        }
    }
}

@Composable
internal fun FileRow(
    entry: GuestEntry,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Palette.accent.copy(alpha = 0.18f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(LauncherStyle.shape(22.dp)).background(if (selected) Palette.accent else Palette.surface),
            contentAlignment = Alignment.Center,
        ) {
            val icon =
                when {
                    selected -> Icons.Check
                    entry.isDir -> Icons.Folder
                    isImage(entry.name) -> Icons.Image
                    extension(entry.name) in TEXT_EXTENSIONS -> Icons.TextFile
                    else -> Icons.File
                }
            val tint = if (selected) Palette.background else if (entry.isDir) Palette.accent else Palette.foreground
            Image(icon, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(tint))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                entry.name + if (entry.isLink) " ↗" else "",
                style = Mono.copy(fontSize = TextSize.Item),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            val detail =
                if (entry.isDir) {
                    entry.children?.let { if (it == 1) "1 item" else "$it items" } ?: "folder"
                } else {
                    Formatter.formatShortFileSize(context, entry.size) + " · " + formatDate(context, entry.modified)
                }
            BasicText(detail, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 1)
        }
        if (selecting && !selected) {
            Box(Modifier.size(22.dp).border(2.dp, Palette.border, LauncherStyle.shape(11.dp)))
        }
    }
}

@Composable
internal fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onRename: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onOpenWith: (() -> Unit)?,
    onEdit: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(Palette.surface).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(Icons.Close, "clear selection", onClick = onClose)
        BasicText("$count selected", Modifier.weight(1f).padding(horizontal = 8.dp), style = Mono.copy(fontSize = TextSize.Large))
        IconButton(Icons.Copy, "copy", onClick = onCopy)
        IconButton(Icons.Cut, "cut", onClick = onCut)
        IconButton(Icons.Delete, "delete", tint = Palette.red, onClick = onDelete)
        Box {
            IconButton(Icons.More, "more") { menu = true }
            if (menu) {
                MenuPopup(
                    listOfNotNull(
                        "select all" to onSelectAll,
                        onRename?.let { "rename" to it },
                        onEdit?.let { "edit in micro" to it },
                        onOpenWith?.let { "open with…" to it },
                        onShare?.let { "share" to it },
                    ),
                    onDismiss = { menu = false },
                )
            }
        }
    }
}

@Composable
internal fun PasteBar(
    count: Int,
    cut: Boolean,
    onCancel: () -> Unit,
    onPaste: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Palette.surface).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            (if (count == 1) "1 item" else "$count items") + if (cut) " to move" else " to copy",
            Modifier.weight(1f),
            style = Mono.copy(color = Palette.muted),
        )
        TextButton("cancel", Palette.muted, onCancel)
        TextButton(if (cut) "move here" else "paste here", Palette.accent, onPaste)
    }
}

@Composable
internal fun TextButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    BasicText(
        label,
        Modifier.clip(LauncherStyle.shape(20.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        style = Mono.copy(color = color, fontSize = TextSize.Item),
    )
}

@Composable
internal fun MenuPopup(
    items: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    Popup(alignment = Alignment.TopEnd, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        MenuItems(items, onDismiss, minWidth = 200.dp, itemPadding = 13.dp, fontSize = TextSize.Item)
    }
}
