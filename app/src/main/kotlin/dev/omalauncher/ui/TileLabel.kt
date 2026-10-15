package dev.omalauncher.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import dev.omalauncher.theme.Style
import kotlin.math.ceil
import kotlin.math.floor

enum class LabelTone { Light, Dark }

/** A bare tile name's ink, with a tighter, more opaque halo where neither tone stands out well from what's behind it. */
data class LabelInk(
    val tone: LabelTone,
    val strongHalo: Boolean = false,
)

/** WCAG's contrast ratio of two relative luminances: 1 for the same, 21 for black on white. */
fun contrast(
    a: Float,
    b: Float,
): Float = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)

/** WCAG's AA minimum contrast for body text. */
private const val READABLE = 4.5f

/**
 * The ink for a name over a background whose luminance runs over [behind], with white light text and
 * [dark] the dark ink's luminance: the tone whose worst contrast across [behind] is higher, light on a tie.
 */
fun pickInk(
    behind: ClosedFloatingPointRange<Float>,
    dark: Float,
): LabelInk {
    val light = contrast(1f, behind.endInclusive)
    val darkest = contrast(dark, behind.start)
    return LabelInk(if (light >= darkest) LabelTone.Light else LabelTone.Dark, strongHalo = maxOf(light, darkest) < READABLE)
}

/**
 * A background's relative luminance in cells [cols] across, downsampled from an [imageWidth]×[imageHeight] image.
 * Home draws that image center-cropped and slid with the pages (see [wallpaperCrop]), which [behind] follows.
 */
class LumaGrid(
    private val cols: Int,
    private val luma: FloatArray,
    private val imageWidth: Int,
    private val imageHeight: Int,
) {
    private val rows = luma.size / cols

    /** The darkest to the brightest cell under [rect], in [screen] px, with the background slid to [offset]; null for an empty rect or screen. */
    fun behind(
        rect: Rect,
        screen: IntSize,
        offset: Float,
    ): ClosedFloatingPointRange<Float>? {
        if (rect.isEmpty || screen.width <= 0 || screen.height <= 0) return null
        val crop = wallpaperCrop(imageWidth, imageHeight, screen.width, screen.height, offset)
        val xs = cells(rect.left, rect.right, crop.left, crop.width, screen.width, imageWidth, cols)
        val ys = cells(rect.top, rect.bottom, crop.top, crop.height, screen.height, imageHeight, rows)
        var lo = 1f
        var hi = 0f
        for (y in ys) {
            for (x in xs) {
                val l = luma[y * cols + x]
                lo = minOf(lo, l)
                hi = maxOf(hi, l)
            }
        }
        return lo..hi
    }

    private fun cells(
        from: Float,
        to: Float,
        cropStart: Int,
        cropSize: Int,
        screenSize: Int,
        imageSize: Int,
        count: Int,
    ): IntRange {
        val start = cropStart.toFloat() * count / imageSize
        val perPx = cropSize.toFloat() / screenSize * count / imageSize
        val first = floor(start + from * perPx).toInt().coerceIn(0, count - 1)
        return first..(ceil(start + to * perPx).toInt() - 1).coerceIn(first, count - 1)
    }
}

/** The luminance behind a name at bounds in window px, for "auto" labels; none provided, or null, draws them light. */
fun interface LabelBackdrop {
    fun behind(bounds: Rect): ClosedFloatingPointRange<Float>?
}

val LocalLabelBackdrop = compositionLocalOf<LabelBackdrop?> { null }

/** The wallpaper home draws (or Android draws for our live wallpaper), slid to [offset] on a [screen]. */
fun wallpaperBackdrop(
    grid: LumaGrid,
    screen: IntSize,
    offset: () -> Float,
) = LabelBackdrop { grid.behind(it, screen, offset()) }

fun solidBackdrop(color: Color): LabelBackdrop {
    val l = color.luminance()
    return LabelBackdrop { l..l }
}

/** The name under an app tile's icon. */
@Composable
internal fun TileLabel(text: String) {
    val backdrop = LocalLabelBackdrop.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val ink by remember(backdrop) { derivedStateOf { labelInk(backdrop, bounds) } }
    val bare = LauncherStyle.bareAppIcons
    val style = Mono.copy(fontSize = TextSize.Small, textAlign = TextAlign.Center)
    BasicText(
        text,
        modifier = if (bare && LauncherStyle.style[Style.LABELS] == "auto") Modifier.onGloballyPositioned { bounds = it.boundsInWindow() } else Modifier,
        style = if (bare) style.inked(ink) else style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun labelInk(
    backdrop: LabelBackdrop?,
    bounds: Rect,
): LabelInk =
    when (LauncherStyle.style[Style.LABELS]) {
        "dark" -> LabelInk(LabelTone.Dark)
        "auto" -> backdrop?.behind(bounds)?.let { pickInk(it, darkInk().luminance()) } ?: LabelInk(LabelTone.Light)
        else -> LabelInk(LabelTone.Light)
    }

private fun darkInk() = minOf(Palette.background, Palette.foreground, compareBy { it.luminance() })

private fun TextStyle.inked(ink: LabelInk): TextStyle {
    val (text, halo) =
        when (ink.tone) {
            LabelTone.Light -> Color.White to Color.Black
            LabelTone.Dark -> darkInk() to Color.White
        }
    val shadow = if (ink.strongHalo) Shadow(halo.copy(alpha = 0.95f), blurRadius = 3f) else Shadow(halo.copy(alpha = 0.7f), blurRadius = 6f)
    return copy(color = text, shadow = shadow)
}
