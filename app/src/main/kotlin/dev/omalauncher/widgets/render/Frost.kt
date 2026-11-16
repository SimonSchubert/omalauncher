package dev.omalauncher.widgets.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.omalauncher.widgets.lang.num
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a frosted node blurs: the launcher's background, and how far it has slid with the pages. Both are
 * read while drawing, so a node redraws as the pages turn. Null where nothing of it is behind, as in an
 * app's window or a preview.
 */
class Backdrop(
    val file: () -> File?,
    val offset: () -> Float,
)

val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** The background small and blurred, one per file and radius, shared by every frosted node. */
private object FrostCache {
    /** Blurred at an eighth of the screen: cheap to make and to draw, and the blur hides the scale. */
    const val SCALE = 8

    private val made = HashMap<Pair<String, Int>, CompletableDeferred<ImageBitmap?>>()

    suspend fun get(
        file: File,
        radius: Int,
        context: Context,
    ): ImageBitmap? {
        val key = "${file.path}:${file.lastModified()}" to radius
        val (job, mine) =
            synchronized(made) {
                made[key]?.let { it to false } ?: run {
                    // One theme's background at a time: the old ones go when it changes.
                    made.keys.removeAll { it.first != key.first }
                    CompletableDeferred<ImageBitmap?>().also { made[key] = it } to true
                }
            }
        if (mine) {
            try {
                job.complete(withContext(Dispatchers.Default) { runCatching { make(file, radius, context) }.getOrNull() })
            } finally {
                // Cancelled mid-way (its node left the screen): the next one to ask makes it again, rather than waiting forever.
                if (!job.isCompleted) {
                    synchronized(made) { if (made[key] === job) made.remove(key) }
                    job.complete(null)
                }
            }
        }
        return job.await()
    }

    private fun make(
        file: File,
        radius: Int,
        context: Context,
    ): ImageBitmap {
        val screen = context.resources.displayMetrics
        val side = min(screen.widthPixels, screen.heightPixels) / SCALE
        val bmp =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val scale = side.toFloat() / min(info.size.width, info.size.height)
                decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        // Three box blurs come close to a gaussian.
        val tmp = IntArray(w * h)
        repeat(3) {
            box(px, tmp, w, h, radius, 1, w)
            box(tmp, px, h, w, radius, w, 1)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /**
     * One pass of a box blur along lines of [len] pixels, [step] apart within a line and [stride] between
     * lines, from [src] into [dst]. A running sum per channel, with the edge pixels repeated past the ends.
     */
    private fun box(
        src: IntArray,
        dst: IntArray,
        len: Int,
        lines: Int,
        r: Int,
        step: Int,
        stride: Int,
    ) {
        val n = 2 * r + 1
        for (line in 0 until lines) {
            val base = line * stride
            var a = 0
            var red = 0
            var g = 0
            var b = 0
            for (i in -r..r) {
                val c = src[base + i.coerceIn(0, len - 1) * step]
                a += c ushr 24
                red += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
            for (i in 0 until len) {
                dst[base + i * step] = ((a / n) shl 24) or ((red / n) shl 16) or ((g / n) shl 8) or (b / n)
                val out = src[base + (i - r).coerceIn(0, len - 1) * step]
                val inn = src[base + (i + r + 1).coerceIn(0, len - 1) * step]
                a += (inn ushr 24) - (out ushr 24)
                red += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    fun radius(
        dp: Double,
        density: Float,
    ) = (dp * density / SCALE).roundToInt().coerceIn(1, 24)
}

/**
 * `frost` (owl 10): the background behind the node, blurred by that many dp, under the node's own
 * background, so `background: alpha(surface, 0.4)` tints it like frosted glass. It blurs the wallpaper,
 * not other nodes, and shows nothing where the launcher draws no wallpaper behind.
 */
@Composable
internal fun Modifier.frost(
    p: Props,
    corner: () -> Float,
): Modifier = frost(p.observe("frost")?.num() ?: 0.0, corner)

/**
 * The wallpaper behind, blurred by [dp] (none at 0), within [corner]'s radius in dp, under what's drawn
 * after it: owl's `frost`, and home's tiles with widgets.frost.
 */
@Composable
fun Modifier.frost(
    dp: Double,
    corner: () -> Float,
): Modifier {
    if (!(dp > 0)) return this
    val backdrop = LocalBackdrop.current ?: return this
    val file = backdrop.file() ?: return this
    val radius = FrostCache.radius(dp, LocalDensity.current.density)
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, file, radius) { value = FrostCache.get(file, radius, context) }
    return then(FrostElement(image ?: return this, backdrop, corner))
}

private data class FrostElement(
    val image: ImageBitmap,
    val backdrop: Backdrop,
    val corner: () -> Float,
) : ModifierNodeElement<FrostNode>() {
    override fun create() = FrostNode(image, backdrop, corner)

    override fun update(node: FrostNode) {
        node.image = image
        node.backdrop = backdrop
        node.corner = corner
        node.invalidateDraw()
    }
}

private class FrostNode(
    var image: ImageBitmap,
    var backdrop: Backdrop,
    var corner: () -> Float,
) : Modifier.Node(),
    DrawModifierNode,
    GlobalPositionAwareModifierNode {
    private val clip = Path()

    // Scrolling or a page sliding moves the node over the background: draw the part now behind it.
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) = invalidateDraw()

    override fun ContentDrawScope.draw() {
        val coords = requireLayoutCoordinates()
        val pos = coords.positionInWindow()
        val screen = coords.findRootCoordinates().size
        val img = image
        // As wallpaperCrop places the background on the screen, in floats: the small image would otherwise slide in steps.
        val bw = img.width.toFloat()
        val bh = img.height.toFloat()
        val scale = max(screen.width / bw, screen.height / bh)
        val srcW = screen.width / scale
        val srcH = screen.height / scale
        val travel = min(bw - srcW, srcW / 2)
        val left = (bw - srcW - travel) / 2 + travel * backdrop.offset().coerceIn(0f, 1f)
        val top = (bh - srcH) / 2
        val r = corner().dp.toPx()
        clip.reset()
        clip.addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r)))
        clipPath(clip) {
            drawImage(
                img,
                dstOffset = IntOffset((-left * scale - pos.x).roundToInt(), (-top * scale - pos.y).roundToInt()),
                dstSize = IntSize((bw * scale).roundToInt(), (bh * scale).roundToInt()),
                filterQuality = FilterQuality.Low,
            )
        }
        drawContent()
    }
}
