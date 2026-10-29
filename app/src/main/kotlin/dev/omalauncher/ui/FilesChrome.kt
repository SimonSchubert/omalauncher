package dev.omalauncher.ui

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.omalauncher.R
import dev.omalauncher.linux.GuestEntry

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
    LazyRow(
        Modifier.bleed(PanelPadding),
        state = state,
        contentPadding = PaddingValues(horizontal = PanelPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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

/** Quick jumps, like stock Files' drawer: home, config, the whole system; the one you're in is in the accent color. */
@Composable
internal fun Places(
    path: String,
    exists: (String) -> Boolean,
    onNavigate: (String) -> Unit,
) {
    val places =
        remember(path) {
            listOf(
                R.string.files_home to "/root",
                R.string.files_place_config to "/root/.config",
                R.string.files_place_downloads to "/root/Downloads",
                R.string.files_place_system to "/",
            )
                .filter { exists(it.second) }
        }
    LazyRow(Modifier.bleed(PanelPadding), contentPadding = PaddingValues(horizontal = PanelPadding - 8.dp)) {
        items(places) { (label, target) ->
            BracketAction(stringResource(label), if (path == target) Palette.accent else Palette.muted) { onNavigate(target) }
        }
    }
}

/** An entry as a settings-style row: folders end in a chevron, selected rows are framed in the accent color. */
@Composable
internal fun FileRow(
    entry: GuestEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier
            .fillMaxWidth()
            .frame(if (selected) 2.dp else 0.dp, Palette.accent, if (selected) Palette.accent.copy(alpha = 0.18f) else Palette.surface, radius = 12.dp)
            .menuLongPresses(onLongClick)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Symbol(Glyph.Check, Palette.accent, fontSize = TextSize.Title)
            } else {
                val icon =
                    when {
                        entry.isDir -> Icons.Folder
                        isImage(entry.name) -> Icons.Image
                        extension(entry.name) in TEXT_EXTENSIONS -> Icons.TextFile
                        else -> Icons.File
                    }
                Image(icon, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(if (entry.isDir) Palette.accent else Palette.muted))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                entry.name + if (entry.isLink) " ↗" else "",
                style = Mono.copy(fontSize = TextSize.Item),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            val detail =
                if (entry.isDir) {
                    entry.children?.let { pluralStringResource(R.plurals.files_items, it, it) } ?: stringResource(R.string.files_folder)
                } else {
                    Formatter.formatShortFileSize(context, entry.size) + " · " + formatDate(context, entry.modified)
                }
            BasicText(detail, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 1)
        }
        if (entry.isDir) Symbol(Glyph.ChevronRight, Palette.accent, fontSize = TextSize.Title)
    }
}

/** What to do with the selection, under the list; the count is the panel's title. */
@Composable
internal fun SelectionBar(
    /** Its buttons all at once, as they go. */
    modifier: Modifier,
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
    BottomBar(modifier) {
        BracketAction(stringResource(R.string.files_copy), Palette.accent, onClick = onCopy)
        BracketAction(stringResource(R.string.files_cut), Palette.accent, onClick = onCut)
        BracketAction(stringResource(R.string.files_delete), Palette.red, onClick = onDelete)
        Spacer(Modifier.weight(1f))
        Box {
            BracketAction(stringResource(R.string.files_more), Palette.muted) { menu = true }
            if (menu) {
                MenuPopup(
                    listOfNotNull(
                        stringResource(R.string.files_select_all) to onSelectAll,
                        onRename?.let { stringResource(R.string.files_rename) to it },
                        onEdit?.let { stringResource(R.string.files_edit) to it },
                        onOpenWith?.let { stringResource(R.string.files_open_with) to it },
                        onShare?.let { stringResource(R.string.files_share) to it },
                    ),
                    onDismiss = { menu = false },
                    alignment = Alignment.BottomEnd,
                )
            }
        }
    }
}

@Composable
internal fun PasteBar(
    modifier: Modifier,
    /** Gives keys paste here. */
    paste: FocusRequester,
    count: Int,
    cut: Boolean,
    onCancel: () -> Unit,
    onPaste: () -> Unit,
) {
    BottomBar(modifier) {
        BasicText(
            // The button says whether they're copied or moved.
            pluralStringResource(R.plurals.files_items, count, count),
            Modifier.weight(1f).padding(start = 6.dp),
            style = Mono.copy(color = Palette.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BracketAction(stringResource(R.string.files_cancel), Palette.muted, onClick = onCancel)
        BracketAction(stringResource(if (cut) R.string.files_move_here else R.string.files_paste_here), Palette.accent, Modifier.focusRequester(paste), onPaste)
    }
}

/** A surface row of [BracketAction]s under the list; ones that don't fit go under it whole, not a letter a line. */
@Composable
private fun BottomBar(
    modifier: Modifier,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier.padding(top = 8.dp).card(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun MenuPopup(
    items: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
    /** TopEnd drops down from a header; BottomEnd grows up from a bar at the bottom. */
    alignment: Alignment = Alignment.TopEnd,
) {
    Popup(alignment = alignment, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(Modifier.holdsKeys()) { MenuItems(items, onDismiss, minWidth = 200.dp, itemPadding = 13.dp) }
    }
}
