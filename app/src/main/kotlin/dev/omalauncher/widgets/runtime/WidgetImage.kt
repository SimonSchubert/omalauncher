package dev.omalauncher.widgets.runtime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import com.caverock.androidsvg.SVG
import kotlin.math.ceil

/** What an `Image` src or `image(...)` decodes to. */
sealed interface WidgetImage {
    /** Pixels: a PNG, JPEG or WebP, an app icon or album art. */
    data class Raster(val bitmap: ImageBitmap) : WidgetImage

    /** An SVG, kept as drawing commands: sharp at any size, and a few KB however large it's drawn. */
    class Vector(val picture: Picture) : WidgetImage
}

/** Whether [bytes] are an SVG: XML starts with '<' (after a BOM or blank space), which no PNG, JPEG or WebP does. */
fun isSvg(bytes: ByteArray): Boolean {
    var i = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
    while (i < bytes.size && bytes[i].toInt().toChar().isWhitespace()) i++
    return i < bytes.size && bytes[i] == '<'.code.toByte()
}

/**
 * An SVG as a [Picture] with the viewBox's proportions, scaled so its longer side is [SVG_SIDE]; it's drawn
 * scaled to wherever it goes. A viewBox comes from width/height when the file has none; with neither, null.
 */
fun decodeSvg(bytes: ByteArray): Picture? =
    runCatching {
        val svg = SVG.getFromInputStream(bytes.inputStream())
        if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
            svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        }
        val box = svg.documentViewBox?.takeIf { it.width() > 0 && it.height() > 0 } ?: return null
        val scale = SVG_SIDE / maxOf(box.width(), box.height())
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        svg.renderToPicture(ceil(box.width() * scale).toInt(), ceil(box.height() * scale).toInt())
    }.getOrNull()

/** Draws [picture] stretched into the rectangle at [x], [y], [w] by [h], colored through [filter] if there is one. */
fun drawPicture(
    canvas: Canvas,
    picture: Picture,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    filter: android.graphics.ColorFilter? = null,
    alpha: Float = 1f,
) {
    if (picture.width <= 0 || picture.height <= 0 || w <= 0 || h <= 0) return
    val save =
        if (filter != null || alpha < 1f) {
            canvas.saveLayer(x, y, x + w, y + h, Paint().apply { colorFilter = filter; this.alpha = (alpha * 255).toInt() })
        } else {
            canvas.save()
        }
    canvas.translate(x, y)
    canvas.scale(w / picture.width, h / picture.height)
    canvas.drawPicture(picture)
    canvas.restoreToCount(save)
}

/** A [WidgetImage.Vector] for Compose's Image, which scales it by `contentScale` like a bitmap. */
class PicturePainter(private val picture: Picture) : Painter() {
    private var filter: ColorFilter? = null
    private var alpha = 1f

    override val intrinsicSize = Size(picture.width.toFloat(), picture.height.toFloat())

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        filter = colorFilter
        return true
    }

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }

    override fun DrawScope.onDraw() {
        drawIntoCanvas { drawPicture(it.nativeCanvas, picture, 0f, 0f, size.width, size.height, filter?.asAndroidColorFilter(), alpha) }
    }
}

/** The longer side of an SVG's [Picture]; only proportions matter, as it's drawn scaled. */
private const val SVG_SIDE = 1000f
