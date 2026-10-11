package dev.omalauncher.ui

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.omalauncher.AppWidgetSetupActivity
import dev.omalauncher.widgets.AppWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A widget an app offers, as the picker lists it. */
private class Offer(
    val info: AppWidgetProviderInfo,
    val app: String,
    val label: String,
    val preview: ImageBitmap?,
    val cells: Pair<Int, Int>,
)

/**
 * The Android widgets the phone's apps offer, by app: each with its preview, name and size in cells.
 * Picking one sets it up (Android may ask to allow the app first, the widget may want configuring)
 * and puts it on [page], or the next page with room; the picker closes once it's there.
 */
@Composable
fun AppWidgetPicker(
    widgets: AppWidgets,
    page: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val offers by produceState<List<Offer>?>(null) {
        // Left by earlier setups; none is under way while the picker opens.
        widgets.forgetStrays()
        value = withContext(Dispatchers.IO) { offers(context, widgets) }
    }
    // Set up and placed: back to home, to see it.
    val before = remember { widgets.items.size }
    LaunchedEffect(widgets.items.size) { if (widgets.items.size > before) onDismiss() }

    OverlayPanel("android widgets", onDismiss, contentWidth = ReadingWidth) {
        SearchField(query, onChange = { query = it }, onGo = {}, placeholder = "search widgets")
        val q = query.trim()
        val shown = offers.orEmpty().filter { q.isEmpty() || it.app.contains(q, ignoreCase = true) || it.label.contains(q, ignoreCase = true) }
        val status =
            when {
                offers == null -> "loading…"
                shown.isEmpty() -> if (q.isEmpty()) "no app offers widgets" else "no widgets match"
                else -> "${shown.size} widgets"
            }
        BasicText(status, Modifier.padding(vertical = 8.dp), style = Mono.copy(color = Palette.muted))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((app, group) in shown.groupBy { it.app }) {
                item(key = "app:$app") { SectionLabel(app) }
                items(group, key = { it.info.provider.flattenToString() }) { offer ->
                    OfferRow(offer) { context.startActivity(AppWidgetSetupActivity.add(context, offer.info.provider, page)) }
                }
            }
        }
    }
}

@Composable
private fun OfferRow(
    offer: Offer,
    onPick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .frame(1.dp, Palette.border, Palette.surface, radius = 12.dp)
            .clickable(onClick = onPick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        offer.preview?.let {
            Image(it, offer.label, Modifier.heightIn(max = PreviewHeight).align(Alignment.CenterHorizontally), contentScale = ContentScale.Fit)
        }
        BasicText("${offer.label} · ${offer.cells.first}x${offer.cells.second}", style = Mono.copy(fontSize = TextSize.Item))
    }
}

/** The tallest a preview shows in the list. */
private val PreviewHeight = 140.dp

/** The phone's own widgets, by app name and then their own; previews are drawn here, off the main thread. */
private fun offers(
    context: Context,
    widgets: AppWidgets,
): List<Offer> {
    val pm = context.packageManager
    val metrics = context.resources.displayMetrics
    val maxW = (ReadingWidth.value * metrics.density).roundToInt()
    val maxH = (PreviewHeight.value * metrics.density).roundToInt()
    return widgets
        .providers()
        .map { info ->
            // An app's broken resources cost it its preview, not the whole list.
            val preview = runCatching { info.loadPreviewImage(context, metrics.densityDpi) ?: info.loadIcon(context, metrics.densityDpi) }.getOrNull()
            Offer(
                info = info,
                app = appName(info, pm),
                label = info.loadLabel(pm),
                preview = preview?.let { draw(it, maxW, maxH) },
                cells = widgets.cellsFor(info, metrics),
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Offer::app).thenBy(String.CASE_INSENSITIVE_ORDER, Offer::label))
}

/** The name of the app offering [info]; its package name if that app can't be looked up (before Android 12, one we don't see). */
private fun appName(
    info: AppWidgetProviderInfo,
    pm: PackageManager,
): String {
    val app = if (Build.VERSION.SDK_INT >= 31) info.activityInfo.applicationInfo else runCatching { pm.getApplicationInfo(info.provider.packageName, 0) }.getOrNull()
    return app?.loadLabel(pm)?.toString() ?: info.provider.packageName
}

/** [drawable] scaled to fit [maxW] by [maxH], keeping its shape; one without a size of its own fills a square. */
private fun draw(
    drawable: Drawable,
    maxW: Int,
    maxH: Int,
): ImageBitmap {
    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: maxH
    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: maxH
    val scale = minOf(1f, maxW / w.toFloat(), maxH / h.toFloat())
    val bmp = Bitmap.createBitmap((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, bmp.width, bmp.height)
    drawable.draw(Canvas(bmp))
    return bmp.asImageBitmap()
}
