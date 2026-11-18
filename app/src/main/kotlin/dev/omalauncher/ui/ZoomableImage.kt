package dev.omalauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize

/** How far a pinch zooms in a [ZoomableImage], and a double tap. */
private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * [bitmap] fitted into its bounds and kept inside them: a pinch or a double tap zooms in where the fingers are, a drag
 * looks around once zoomed, and a double tap on a zoomed one shows it whole again.
 */
@Composable
fun ZoomableImage(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(Size.Zero) }
    val picture = Size(bitmap.width.toFloat(), bitmap.height.toFloat())

    fun zoom(
        to: Float,
        at: Offset,
    ) {
        val next = to.coerceIn(1f, MAX_ZOOM)
        pan = keepInView(zoomAround(pan, at - box.center, scale, next), next, picture, box)
        scale = next
    }
    Image(
        bitmap,
        contentDescription,
        modifier
            .clipToBounds()
            .onSizeChanged { box = it.toSize() }
            // Before the graphicsLayer, so the fingers are where they are on screen, not on the zoomed picture.
            .pointerInput(bitmap) { detectTapGestures(onDoubleTap = { at -> zoom(if (scale > 1f) 1f else DOUBLE_TAP_ZOOM, at) }) }
            .pointerInput(bitmap) {
                detectTransformGestures { centroid, move, change, _ ->
                    zoom(scale * change, centroid)
                    pan = keepInView(pan + move, scale, picture, box)
                }
            }.graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = pan.x
                translationY = pan.y
            },
        contentScale = ContentScale.Fit,
    )
}

/**
 * Where a [picture] fitted into [box] and zoomed [scale] times can be slid, from the center, to [pan]: no further than
 * keeps its edges at the box's, and not at all along a side it doesn't fill.
 */
internal fun keepInView(
    pan: Offset,
    scale: Float,
    picture: Size,
    box: Size,
): Offset {
    if (picture.width <= 0f || picture.height <= 0f) return Offset.Zero
    val fit = minOf(box.width / picture.width, box.height / picture.height)
    val x = maxOf(0f, (picture.width * fit * scale - box.width) / 2)
    val y = maxOf(0f, (picture.height * fit * scale - box.height) / 2)
    return Offset(pan.x.coerceIn(-x, x), pan.y.coerceIn(-y, y))
}

/** [pan] after zooming [from] to [to] times around [at] (from the center), so what's under the fingers stays there. */
internal fun zoomAround(
    pan: Offset,
    at: Offset,
    from: Float,
    to: Float,
): Offset = at - (at - pan) * (to / from)
