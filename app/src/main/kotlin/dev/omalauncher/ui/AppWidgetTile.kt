package dev.omalauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.AppWidgetCatalog
import dev.omalauncher.sizeWidget
import dev.omalauncher.widgets.AndroidWidget

/**
 * One of the phone's apps' widgets on home, drawn by its app. Taps reach it; a long press lifts it like any tile,
 * and the touch it had is cancelled. Once its app is gone, its name says so, until it's removed.
 */
@Composable
internal fun AppWidgetTile(
    widget: AndroidWidget,
    catalog: AppWidgetCatalog,
    focused: Boolean,
    modifier: Modifier,
) {
    // Asked again when the apps' widgets change: an update may bring new layouts, and an app removed takes its widget.
    val info = remember(widget.id, catalog.providers) { catalog.info(widget.id) }
    val outline =
        Modifier.drawWithContent {
            drawContent()
            if (focused) {
                val w = LauncherStyle.focusWidth.toPx()
                drawRect(LauncherStyle.focusBorder, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
            }
        }
    if (info == null) {
        Box(modifier.tile(focused).padding(8.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText(widget.label, style = Mono.copy(textAlign = TextAlign.Center))
                BasicText("its app is gone", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small, textAlign = TextAlign.Center))
            }
        }
        return
    }
    key(info) {
        AndroidView(
            factory = { context ->
                catalog.createView(context, widget.id, info).apply {
                    // Its size in dp as last told, so it's told again only when the tile's size changes.
                    var told = 0f to 0f
                    addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                        val d = v.resources.displayMetrics.density
                        val size = (r - l) / d to (b - t) / d
                        if (size.first > 0f && size.second > 0f && size != told) {
                            told = size
                            sizeWidget(this, size.first, size.second)
                        }
                    }
                }
            },
            modifier = modifier.then(outline),
        )
    }
}
