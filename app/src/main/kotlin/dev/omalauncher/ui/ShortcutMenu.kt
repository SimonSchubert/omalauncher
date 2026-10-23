package dev.omalauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.omalauncher.AndroidApp
import dev.omalauncher.OfferedShortcut
import dev.omalauncher.Runtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What an Android app's long-press menu does with the shortcuts it offers: lists them, opens one, pins one to home. */
internal class ShortcutMenu(
    /** Blocks; called off the main thread. */
    val load: () -> List<OfferedShortcut>,
    val open: (OfferedShortcut) -> Unit,
    val pin: (OfferedShortcut) -> Unit,
)

/**
 * [app]'s shortcuts: opening one runs [onOpen] first, pinning one puts it on home [page] (and in the drawer), then
 * runs [onPinned].
 */
internal fun shortcutMenu(
    runtime: Runtime,
    app: AndroidApp,
    page: () -> Int,
    onOpen: () -> Unit = {},
    onPinned: () -> Unit = {},
) = ShortcutMenu(
    load = { runtime.androidApps.offered(app) },
    open = {
        onOpen()
        runtime.androidApps.open(it)
    },
    pin = { offered ->
        runtime.androidApps.pin(offered) {
            runtime.widgets.shortcuts.addAndroidShortcut(it, page())
            onPinned()
        }
    },
)

/**
 * The shortcuts [menu] offers: null while they load (a few milliseconds), empty when [menu] is null. The menu waits
 * for them rather than showing without them, so it opens once, whole, rather than growing as they arrive.
 * Made once per menu shown, or per [key].
 */
@Composable
internal fun offeredShortcuts(
    key: Any?,
    menu: () -> ShortcutMenu?,
): Pair<ShortcutMenu?, List<OfferedShortcut>?> =
    // Keyed as a whole: produceState would otherwise keep the last tile's shortcuts until the next one's load.
    key(key) {
        val made = remember { menu() }
        val offered by produceState<List<OfferedShortcut>?>(if (made == null) emptyList() else null, made) {
            value = if (made == null) emptyList() else withContext(Dispatchers.IO) { made.load() }
        }
        made to offered
    }

/**
 * An app's shortcuts at the top of its long-press menu, as on stock launchers: tapping one opens it, its [+] pins it
 * to home. A [rule] below parts them from the app's own actions.
 */
@Composable
internal fun ShortcutRows(
    offered: List<OfferedShortcut>,
    menu: ShortcutMenu,
    onDismiss: () -> Unit,
    fontSize: TextUnit,
    rule: Boolean = true,
) {
    if (offered.isEmpty()) return
    Column {
        for (s in offered) {
            // The + beside the row rather than in it, so keys reach both: Right goes from one to the other.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable {
                            onDismiss()
                            menu.open(s)
                        }.padding(start = 14.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ShortcutIcon(s)
                    BasicText(s.label, Modifier.weight(1f), style = Mono.copy(fontSize = fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BracketAction("+", Palette.accent) {
                    onDismiss()
                    menu.pin(s)
                }
            }
        }
        if (rule) Box(Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(Palette.border))
    }
}

/** A shortcut's icon at menu size: its silhouette with monochrome icons, its initials without an icon. */
@Composable
private fun ShortcutIcon(s: OfferedShortcut) {
    val size = 24.dp
    val mono = LauncherStyle.monochromeIcons
    val bmp = if (mono) s.mask else s.icon
    when {
        bmp != null -> Image(bmp, null, Modifier.size(size), colorFilter = if (mono) ColorFilter.tint(Palette.accent) else null)
        else ->
            IconBadge(size, Palette.background) {
                BasicText(initials(s.label), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small))
            }
    }
}
