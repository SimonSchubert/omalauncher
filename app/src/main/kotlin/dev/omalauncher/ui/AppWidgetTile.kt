package dev.omalauncher.ui

import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.widgets.AppWidgetItem
import dev.omalauncher.widgets.AppWidgets

/**
 * An Android widget on home, drawn by its app at the size of its cells. No frame: widgets bring their own
 * background, so only an accent outline shows while it's [focused] (being arranged). One whose app is gone
 * leaves a note in its place, to remove it by.
 *
 * Touches reach the widget unless home takes them first: a long-press lifts it, a swipe turns the page or
 * opens the drawer, and the widget gets a cancel.
 */
@Composable
fun AppWidgetTile(
    item: AppWidgetItem,
    widgets: AppWidgets,
    focused: Boolean,
    modifier: Modifier,
) {
    val info = remember(item.id) { widgets.info(item.id) }
    BoxWithConstraints(modifier) {
        if (info == null) {
            Box(Modifier.fillMaxSize().tile(focused).padding(8.dp), contentAlignment = Alignment.Center) {
                BasicText("widget gone · its app was removed", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        } else {
            // The size in dp the app lays the widget out for. Sent only when it changes: each one reaches the app as a broadcast.
            val size = SizeF(maxWidth.value, maxHeight.value)
            val sent = remember(item.id) { arrayOfNulls<SizeF>(1) }
            key(item.id) {
                AndroidView(
                    factory = { widgets.host.createView(it, item.id, info) },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        if (sent[0] != size) {
                            sent[0] = size
                            if (Build.VERSION.SDK_INT >= 31) {
                                view.updateAppWidgetSize(Bundle(), listOf(size))
                            } else {
                                val (w, h) = size.width.toInt() to size.height.toInt()
                                @Suppress("DEPRECATION")
                                view.updateAppWidgetSize(null, w, h, w, h)
                            }
                        }
                    },
                )
            }
            if (focused) Box(Modifier.fillMaxSize().frame(2.dp, Palette.accent))
        }
    }
}
