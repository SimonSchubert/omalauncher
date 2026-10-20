package dev.omalauncher.ui

import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.omalauncher.widgets.Folder
import dev.omalauncher.widgets.HomeItem
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A folder on the home grid: its first four apps in a 2x2 grid the size of an app's icon, and its name under it like an app's. */
@Composable
fun FolderTile(
    folder: Folder,
    /** The first four apps' icons, as [ShortcutTile] gets them. */
    icons: List<ImageBitmap?>,
    /** Their names, as [ShortcutTile] gets them. */
    labels: List<String>,
    focused: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    BoxWithConstraints(
        modifier
            .tileFrame(focused)
            .clearAndSetSemantics { contentDescription = folder.name?.let { "$it folder" } ?: "folder" }
            .pointerInput(folder.key) { detectTapGestures(onTap = { onTap() }) }
            .padding(4.dp),
    ) {
        val showLabel = showsLabel()
        val badge = tileIconSize(showLabel, full = true)
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            // Straight on the tile, filling an app icon's room.
            val mini = badge * 0.47f
            Column(verticalArrangement = Arrangement.spacedBy(badge * 0.06f)) {
                for (row in 0 until 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(badge * 0.06f)) {
                        for (col in 0 until 2) {
                            val i = row * 2 + col
                            Box(Modifier.size(mini), contentAlignment = Alignment.Center) {
                                labels.getOrNull(i)?.let { AppGlyph(it, icons.getOrNull(i), mini) }
                            }
                        }
                    }
                }
            }
            if (showLabel) TileLabel(folder.name.orEmpty())
        }
    }
}

/** A small app icon: its picture (a silhouette in monochrome mode) or its initials. */
@Composable
private fun AppGlyph(
    label: String,
    icon: ImageBitmap?,
    size: Dp,
) {
    if (icon != null) {
        Image(icon, label, Modifier.size(size), colorFilter = if (LauncherStyle.monochromeIcons) ColorFilter.tint(Palette.accent) else null)
    } else {
        BasicText(initials(label), style = Mono.copy(color = Palette.accent, fontSize = (size.value * 0.45f).sp))
    }
}

/**
 * Where an open folder draws, in the home grid's pixels: a panel in the middle of the
 * grid with its name on top and the apps at home's cell size, smaller only
 * when that doesn't fit. The grid's gestures and the panel's drawing share it.
 */
internal class FolderGeometry(
    width: Int,
    height: Int,
    cellW: Float,
    cellH: Float,
    gap: Int,
    count: Int,
    density: Density,
) {
    val cols = ceil(sqrt(count.toFloat())).toInt().coerceIn(2, 4)
    private val rows = ((count + cols - 1) / cols).coerceAtLeast(1)
    private val pad = with(density) { 16.dp.roundToPx() }
    private val margin = with(density) { 16.dp.roundToPx() }
    private val head = with(density) { 40.dp.roundToPx() }
    private val scale =
        minOf(
            1f,
            (width - 2 * margin - 2 * pad) / (cols * cellW),
            (height - 2 * margin - 2 * pad - head) / (rows * cellH),
        )
    private val w = cellW * scale
    private val h = cellH * scale
    private val gap = gap

    val panel: IntRect =
        run {
            val pw = (cols * w).roundToInt() + 2 * pad
            val ph = (rows * h).roundToInt() + 2 * pad + head
            val left = (width - pw) / 2
            val top = (height - ph) / 2
            IntRect(left, top, left + pw, top + ph)
        }

    /** The folder's name, across the panel's top; a tap there renames it. */
    val header: IntRect get() = IntRect(panel.left, panel.top, panel.right, panel.top + pad + head)

    /** The [i]th app's tile, gapped like home's. */
    fun app(i: Int): IntRect {
        val left = panel.left + pad + (i % cols) * w
        val top = panel.top + pad + head + (i / cols) * h
        return IntRect((left + gap / 2f).roundToInt(), (top + gap / 2f).roundToInt(), (left + w - gap / 2f).roundToInt(), (top + h - gap / 2f).roundToInt())
    }
}

/**
 * An open folder over the home grid: a scrim, the panel with the folder's name and its apps drawn by [render].
 * Taps, long-presses and drags are [HomeGrid]'s, like every touch on home. While [naming], the name is a text
 * field in the same place, saved through [onName] as it's typed.
 */
@Composable
internal fun FolderPanel(
    folder: Folder,
    geometry: FolderGeometry,
    render: @Composable (HomeItem, Modifier, Boolean) -> Unit,
    /** The app a finger is dragging, if it's one of these: its place stays empty while the others sort around it. */
    carried: String? = null,
    naming: Boolean = false,
    onName: (String) -> Unit = {},
    /** Typing the name is over: Done, or the field went away. */
    onNamed: () -> Unit = {},
) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().zIndex(4f).background(Palette.overlay.copy(alpha = 0.6f))) {
        val p = geometry.panel
        Box(
            Modifier
                .offset { IntOffset(p.left, p.top) }
                .size(with(density) { p.width.toDp() }, with(density) { p.height.toDp() })
                .frame(2.dp, Palette.accent, Palette.background, radius = 20.dp),
        )
        val hd = geometry.header
        Box(
            Modifier
                .offset { IntOffset(hd.left, hd.top) }
                .size(with(density) { hd.width.toDp() }, with(density) { hd.height.toDp() })
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Item, textAlign = TextAlign.Center)
            if (naming) {
                // Same box, style and centre as the label it replaces, so nothing moves.
                var text by remember(folder.key) { mutableStateOf(folder.name.orEmpty().let { TextFieldValue(it, TextRange(it.length)) }) }
                val focus = remember { FocusRequester() }
                var focused by remember { mutableStateOf(false) }
                val focusManager = LocalFocusManager.current
                LaunchedEffect(Unit) { focus.requestFocus() }
                DisposableEffect(Unit) { onDispose(onNamed) }
                BasicTextField(
                    value = text,
                    onValueChange = {
                        if (it.text != text.text) onName(it.text)
                        text = it
                    },
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(Palette.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged {
                                if (focused && !it.isFocused) onNamed()
                                focused = it.isFocused
                            },
                )
            } else {
                BasicText(
                    folder.name ?: "tap to name",
                    style = if (folder.name == null) style.copy(color = Palette.muted) else style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        folder.apps.forEachIndexed { i, app ->
            if (app.key == carried) return@forEachIndexed
            val r = geometry.app(i)
            key(app.key) {
                // Apps making way for a dragged one slide to their new places.
                val at = animateIntOffsetAsState(IntOffset(r.left, r.top))
                render(
                    app,
                    Modifier
                        .offset { at.value }
                        .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() }),
                    false,
                )
            }
        }
    }
}
