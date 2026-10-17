package dev.omalauncher.widgets.render

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import dev.omalauncher.widgets.runtime.PicturePainter
import dev.omalauncher.widgets.runtime.WidgetImage
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.NerdSymbols
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.TerminalView
import dev.omalauncher.widgets.lang.Action
import dev.omalauncher.widgets.lang.Builtins
import dev.omalauncher.widgets.lang.Env
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.WNode
import dev.omalauncher.widgets.lang.num
import dev.omalauncher.widgets.lang.text
import dev.omalauncher.widgets.runtime.WidgetInstance
import kotlinx.coroutines.flow.first
import kotlin.math.min

/** Whether the home screen is hidden (drawer, full-screen panel); animated widgets stop ticking while it is. */
val LocalHomeCovered = staticCompositionLocalOf<() -> Boolean> { { false } }

/** Whether an Input's `focus` may take focus: only on a screen the user opened, never on a tile or in a preview. */
val LocalInputFocus = staticCompositionLocalOf { false }

/** The widget's content inside its tile: padding, optional background, and the node tree. */
@Composable
fun WidgetContent(
    inst: WidgetInstance,
    modifier: Modifier = Modifier,
    /** The tile's size in grid cells, for `cols` and `rows`. */
    cells: Pair<Int, Int>? = null,
) {
    val program = inst.program
    SideEffect { inst.setTile(cells?.first, cells?.second, null, null) }
    val density = LocalDensity.current.density
    if (program.animated) FrameClock(inst, LocalHomeCovered.current)
    val bg = program.background
    Column(
        modifier
            .onSizeChanged { inst.setTile(null, null, (it.width / density).toDouble(), (it.height / density).toDouble()) }
            .then(if (bg != null) Modifier.drawBehind { Builtins.argb(inst.eval(bg, inst.root, "background"), inst)?.let { drawRect(Color(it)) } } else Modifier)
            .padding(inset(program.padding)),
    ) {
        Nodes(program.root, inst.root, inst, this)
    }
}

/** Widget text, 13sp as widgets were made for, whatever size the launcher's own text is. */
internal val WidgetMono get() = Mono.copy(fontSize = 13.sp)

/** The widget's screen { } block, filling [modifier]; `screenWidth` and `screenHeight` follow its size. */
@Composable
fun WidgetScreen(
    inst: WidgetInstance,
    modifier: Modifier = Modifier,
) {
    val nodes = inst.program.screen ?: return
    if (inst.program.animated) FrameClock(inst) { false }
    BoxWithConstraints(modifier) {
        // Set before the nodes compose, so the first frame already has the size instead of null.
        inst.setScreen(maxWidth.value.toDouble(), maxHeight.value.toDouble())
        Column(Modifier.fillMaxSize()) {
            Nodes(nodes, inst.root, inst, this)
        }
    }
}

/**
 * A frame clock for widgets that read `time`, only while they're on screen ([covered] is false)
 * and something still reads it: a `time` under an `if` that's false, like a backdrop drawn only
 * while music plays, stops it after a few frames, and the next read starts it again.
 */
@Composable
private fun FrameClock(
    inst: WidgetInstance,
    covered: () -> Boolean,
) {
    LaunchedEffect(inst, covered) {
        val start = withFrameNanos { it }
        var frame = 0L
        var unread = 0
        while (true) {
            if (covered()) snapshotFlow { covered() }.first { !it }
            if (unread >= 3) {
                inst.awaitTimeRead(start)
                unread = 0
            }
            withFrameNanos { now ->
                // Widgets that draw slowly get half the frame rate instead of stalling the home screen.
                if (inst.slow && frame++ % 2 != 0L) return@withFrameNanos
                unread = if (inst.timeRead) 0 else unread + 1
                inst.timeRead = false
                inst.time = (now - start) / 1e9
            }
        }
    }
}

@Composable
private fun Nodes(
    nodes: List<WNode>,
    env: Env,
    inst: WidgetInstance,
    parent: Any?,
) {
    for (node in nodes) Node(node, env, inst, parent)
}

@Composable
private fun Node(
    node: WNode,
    env: Env,
    inst: WidgetInstance,
    parent: Any?,
) {
    when (node) {
        is WNode.Element -> Element(node, env, inst, parent)
        is WNode.If -> {
            val cond by remember(node, env) { derivedStateOf { inst.eval(node.cond, env, "if").truthy } }
            Nodes(if (cond) node.then else node.orElse, env, inst, parent)
        }
        is WNode.For -> {
            val list by remember(node, env) { derivedStateOf(structuralEqualityPolicy()) { inst.eval(node.list, env, "for") } }
            val items = Builtins.iterable(list)
            items.forEachIndexed { i, v ->
                val k: Any = node.key?.let { inst.eval(it, env.child(v, Num(i.toDouble())), "for key") } ?: i
                key(k) {
                    // The row's frame stays the same as its item changes or it moves: only what
                    // reads the changed parts recomposes, instead of the row being built anew.
                    val item = rememberUpdatedState(v)
                    val index = rememberUpdatedState(i)
                    val child = remember(env) { env.childLazy(arrayOf<() -> Value>({ item.value }, { Num(index.value.toDouble()) })) }
                    Nodes(node.body, child, inst, parent)
                }
            }
        }
        is WNode.Lets -> {
            val frame =
                remember(node, env) {
                    lateinit var f: Env
                    val states = node.values.map { ev -> derivedStateOf(structuralEqualityPolicy()) { inst.eval(ev, f, "let") } }
                    f = env.childLazy(Array(states.size) { i -> { states[i].value } })
                    f
                }
            Nodes(node.children, frame, inst, parent)
        }
        is WNode.Use -> {
            // Parameters are evaluated in the caller, lazily, so they stay reactive.
            val inner =
                remember(node, env) {
                    val where = "${node.component} parameter"
                    Env(inst, inst.root, Array(node.args.size) { i -> { inst.eval(node.args[i], env, where) } })
                }
            if (node.wrapper.isEmpty()) {
                Nodes(node.body, inner, inst, parent)
            } else {
                val wrapper = remember(node) { WNode.Element("Box", node.wrapper, emptyMap(), emptyMap(), emptyList(), node.pos) }
                val p = remember(wrapper, env) { Props(wrapper, env, inst) }
                Box(common(p, parent)) { Nodes(node.body, inner, inst, this) }
            }
        }
        is WNode.Draw -> {} // only inside Canvas, which draws them itself
    }
}

private val SIZING = setOf("width", "height", "size", "fill", "weight")

/** Lengths past this, in dp, are clamped: in a scrolling Column a bigger one can't be represented and fails the layout. */
private const val MAX_DP = 4000.0

/** A padding in dp: Compose throws on a negative or NaN one. */
private fun inset(n: Double) = (if (n >= 0) n.coerceAtMost(MAX_DP) else 0.0).dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Element(
    el: WNode.Element,
    env: Env,
    inst: WidgetInstance,
    parent: Any?,
) {
    val p = remember(el, env) { Props(el, env, inst) }
    if (p.has("visible") && p.observe("visible")?.truthy == false) return
    val mod = common(p, parent)
    val sized = SIZING.any { p.has(it) }
    when (el.kind) {
        "Column" -> {
            val gap = (p.observe("gap")?.num() ?: 4.0).dp
            val align = p.observe("align")?.toString()
            Column(
                mod.then(if (p.observe("scroll")?.truthy == true) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = vertical(p.observe("justify")?.toString(), gap),
                horizontalAlignment =
                    when (align) {
                        "center" -> Alignment.CenterHorizontally
                        "end" -> Alignment.End
                        else -> Alignment.Start
                    },
            ) { Nodes(el.children, env, inst, this) }
        }
        "Row", "Flow" -> {
            val gap = (p.observe("gap")?.num() ?: 8.0).dp
            val vertical =
                when (p.observe("align")?.toString()) {
                    "top", "start" -> Alignment.Top
                    "bottom", "end" -> Alignment.Bottom
                    else -> Alignment.CenterVertically
                }
            val arr = horizontal(p.observe("justify")?.toString(), gap)
            if (el.kind == "Flow" || p.observe("wrap")?.truthy == true) {
                FlowRow(mod, horizontalArrangement = arr, verticalArrangement = Arrangement.spacedBy(gap), itemVerticalAlignment = vertical) {
                    Nodes(el.children, env, inst, this)
                }
            } else {
                Row(mod, horizontalArrangement = arr, verticalAlignment = vertical) { Nodes(el.children, env, inst, this) }
            }
        }
        "Box" -> Box(mod, contentAlignment = boxAlign(p.observe("align")?.toString())) { Nodes(el.children, env, inst, this) }
        "Grid" -> Grid(mod, (p.observe("columns")?.num() ?: 2.0).toInt().coerceIn(1, 12), (p.observe("gap")?.num() ?: 6.0).dp) { Nodes(el.children, env, inst, null) }
        "Text" -> TextNode(p, mod)
        "Button" -> {
            val color = p.color("color") { Palette.accent }
            val radius = p.float("radius", 8f * LauncherStyle.cornerScale)
            Box(
                mod
                    .drawBehind {
                        val w = 1.dp.toPx()
                        val r = CornerRadius(radius().dp.toPx())
                        drawRoundRect(color(), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), r, style = Stroke(w))
                    }.padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(p.observe("text")?.text().orEmpty(), style = WidgetMono, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ColorProducer { color() })
            }
        }
        "Progress" -> {
            val value = p.float("value", 0f)
            val max = p.float("max", 100f)
            val color = p.color("color") { Palette.accent }
            val track = p.color("track") { Palette.surface }
            Spacer(
                mod.then(if (sized) Modifier else Modifier.fillMaxWidth().height(8.dp)).drawBehind {
                    drawRect(track())
                    val f = (value() / max().coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
                    drawRect(color(), size = Size(size.width * f, size.height))
                },
            )
        }
        "Ring" -> {
            val value = p.float("value", 0f)
            val max = p.float("max", 1f)
            val thickness = p.float("thickness", 6f)
            val start = p.float("start", 0f)
            val sweep = p.float("sweep", 360f)
            val color = p.color("color") { Palette.accent }
            val track = p.color("track") { Palette.surface }
            Spacer(
                mod.then(if (sized) Modifier else Modifier.size(64.dp)).drawBehind {
                    val t = thickness().dp.toPx()
                    val d = min(size.width, size.height) - t
                    val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
                    val arc = Size(d, d)
                    drawArc(track(), start() - 90f, sweep(), false, topLeft, arc, style = Stroke(t, cap = StrokeCap.Round))
                    val f = (value() / max().coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
                    if (f > 0f) drawArc(color(), start() - 90f, sweep() * f, false, topLeft, arc, style = Stroke(t, cap = StrokeCap.Round))
                },
            )
        }
        "Sparkline" -> {
            val color = p.color("color") { Palette.accent }
            val thickness = p.float("thickness", 2f)
            Spacer(
                mod.then(if (sized) Modifier else Modifier.fillMaxWidth().height(48.dp)).drawBehind {
                    val points = Builtins.iterable(p.now("values") ?: return@drawBehind).mapNotNull { it.num()?.toFloat() }
                    if (points.size < 2) return@drawBehind
                    val lo = p.now("min")?.num()?.toFloat() ?: 0f
                    // Auto-scaled lines get headroom so the peak isn't glued to the top edge.
                    val hi = p.now("max")?.num()?.toFloat() ?: (lo + (points.max() - lo) * 1.15f).coerceAtLeast(lo + 1f)
                    val path = Path()
                    points.forEachIndexed { i, v ->
                        val x = size.width * i / (points.size - 1)
                        val y = size.height * (1 - ((v - lo) / (hi - lo)).coerceIn(0f, 1f))
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    val c = color()
                    val fill = p.now("area")
                    if (fill != null && fill.truthy) {
                        val area = Path().apply {
                            addPath(path)
                            lineTo(size.width, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(area, p.color(fill) ?: c.copy(alpha = 0.2f))
                    }
                    drawPath(path, c, style = Stroke(thickness().dp.toPx(), cap = StrokeCap.Round))
                },
            )
        }
        "Image" -> {
            val src = p.observe("src")?.toString().orEmpty()
            val image =
                if (src.startsWith("media:")) {
                    // Album art isn't a file to watch: look again whenever a source (the media one) updates,
                    // but recompose only when the art itself changed, not on every tick of every source.
                    remember(src) {
                        derivedStateOf(referentialEqualityPolicy()) {
                            inst.updates.intValue
                            inst.image(src)
                        }
                    }.value
                } else {
                    inst.image(src)
                }
            val scale =
                when (p.observe("fit")?.toString()) {
                    "cover" -> ContentScale.Crop
                    "fill" -> ContentScale.FillBounds
                    else -> ContentScale.Fit
                }
            when (image) {
                is WidgetImage.Raster -> {
                    val bitmap = image.bitmap
                    // An alpha-only image (an app icon's silhouette, with monochrome icons) needs a color to show.
                    val tint = p.observe("tint")?.let { p.color(it) } ?: Palette.foreground.takeIf { bitmap.config == ImageBitmapConfig.Alpha8 }
                    Image(bitmap, null, mod, contentScale = scale, colorFilter = tint?.let { ColorFilter.tint(it) })
                }
                is WidgetImage.Vector -> {
                    val tint = p.observe("tint")?.let { p.color(it) }
                    Image(remember(image) { PicturePainter(image.picture) }, null, mod, contentScale = scale, colorFilter = tint?.let { ColorFilter.tint(it) })
                }
                null -> {}
            }
        }
        "Canvas" -> CanvasNode(p, mod.then(if (sized) Modifier else Modifier.fillMaxSize()))
        "Shader" -> {
            val m = mod.then(if (sized) Modifier else Modifier.fillMaxSize())
            if (Build.VERSION.SDK_INT >= 33) {
                ShaderNode(p, m)
            } else {
                val fallback = p.color("fallback") { Palette.surface }
                Spacer(m.drawBehind { drawRect(fallback()) })
            }
        }
        "Spacer" -> Spacer(mod)
        "Slider" -> SliderNode(p, mod, sized)
        "Input" -> InputNode(p, mod, sized)
        "Terminal" -> {
            val command = p.observe("command")
            val fontSize = p.observe("fontSize")?.num()
            // A command built from a value still loading waits for it, rather than starting a shell and restarting.
            if (command != Null) {
                val term = remember(el, command, fontSize) { inst.terminal(el, command?.toString(), fontSize) }
                TerminalView(term, mod.then(if (sized) Modifier else Modifier.fillMaxSize()))
            }
        }
    }
}

private fun vertical(
    justify: String?,
    gap: androidx.compose.ui.unit.Dp,
): Arrangement.Vertical =
    when (justify) {
        "center" -> Arrangement.spacedBy(gap, Alignment.CenterVertically)
        "end" -> Arrangement.spacedBy(gap, Alignment.Bottom)
        "spaceBetween" -> Arrangement.SpaceBetween
        "spaceAround" -> Arrangement.SpaceAround
        "spaceEvenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(gap)
    }

private fun horizontal(
    justify: String?,
    gap: androidx.compose.ui.unit.Dp,
): Arrangement.Horizontal =
    when (justify) {
        "center" -> Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
        "end" -> Arrangement.spacedBy(gap, Alignment.End)
        "spaceBetween" -> Arrangement.SpaceBetween
        "spaceAround" -> Arrangement.SpaceAround
        "spaceEvenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(gap)
    }

private fun boxAlign(a: String?) =
    when (a) {
        "center" -> Alignment.Center
        "top" -> Alignment.TopCenter
        "bottom" -> Alignment.BottomCenter
        "start" -> Alignment.CenterStart
        "end" -> Alignment.CenterEnd
        "topEnd" -> Alignment.TopEnd
        "bottomStart" -> Alignment.BottomStart
        "bottomEnd" -> Alignment.BottomEnd
        else -> Alignment.TopStart
    }

private fun padding(v: Value?): PaddingValues? {
    val n = v?.num()
    if (n != null) return PaddingValues(inset(n))
    val l = (v as? Lst)?.items?.map { inset(it.num() ?: 0.0) } ?: return null
    return when (l.size) {
        2 -> PaddingValues(horizontal = l[0], vertical = l[1])
        4 -> PaddingValues(l[0], l[1], l[2], l[3])
        else -> null
    }
}

/**
 * Modifiers every node takes. Sizes are read in composition; offset in
 * placement; opacity, rotation, scale, background and border in drawing, so
 * changing them never recomposes.
 */
@Composable
private fun common(
    p: Props,
    parent: Any?,
): Modifier {
    var m: Modifier = Modifier
    p.observe("weight")?.num()?.toFloat()?.takeIf { it > 0f }?.let { w ->
        m =
            when (parent) {
                is RowScope -> with(parent) { m.weight(w) }
                is ColumnScope -> with(parent) { m.weight(w) }
                else -> m
            }
    }
    when (val fill = p.observe("fill")) {
        null -> {}
        is Str -> m = if (fill.v == "height") m.fillMaxHeight() else m.fillMaxWidth()
        else -> if (fill.truthy) m = m.fillMaxSize()
    }
    p.observe("size")?.num()?.let { m = m.size(it.coerceAtMost(MAX_DP).dp) }
    p.observe("width")?.num()?.let { m = m.width(it.coerceAtMost(MAX_DP).dp) }
    p.observe("height")?.num()?.let { m = m.height(it.coerceAtMost(MAX_DP).dp) }
    if (p.has("offset")) {
        m =
            m.offset {
                val o = p.now("offset") as? Lst
                IntOffset(((o?.items?.getOrNull(0)?.num() ?: 0.0) * density).toInt(), ((o?.items?.getOrNull(1)?.num() ?: 0.0) * density).toInt())
            }
    }
    val hasLayer = p.has("opacity") || p.has("rotation") || p.has("scale") || p.has("clip")
    if (hasLayer) {
        val opacity = p.float("opacity", 1f)
        val rotation = p.float("rotation", 0f)
        val scale = p.float("scale", 1f)
        val clip = p.observe("clip")?.truthy == true
        val radius = p.observe("radius")?.num() ?: 0.0
        m =
            m.graphicsLayer {
                alpha = opacity().coerceIn(0f, 1f)
                rotationZ = rotation()
                scale().let {
                    scaleX = it
                    scaleY = it
                }
                if (clip) {
                    this.clip = true
                    shape = RoundedCornerShape(radius.dp)
                }
            }
    }
    if (p.has("background") || p.has("border")) {
        val bg = p.color("background") { Color.Transparent }
        val borderColor = p.color("borderColor") { Palette.border }
        val border = p.float("border", 0f)
        // Bordered elements (buttons, frames) follow the launcher's corner style unless they set a radius.
        val radius = p.float("radius", if (p.has("border")) 8f * LauncherStyle.cornerScale else 0f)
        m =
            m.drawBehind {
                val r = CornerRadius(radius().dp.toPx())
                drawRoundRect(bg(), cornerRadius = r)
                val w = border().dp.toPx()
                if (w > 0f) drawRoundRect(borderColor(), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), r, style = Stroke(w))
            }
    }
    m = m.then(handlers(p))
    padding(p.observe("padding"))?.let { m = m.padding(it) }
    return m
}

@Composable
private fun handlers(p: Props): Modifier {
    val el = p.el
    if (el.handlers.isEmpty()) return Modifier
    val density = LocalDensity.current.density
    var m: Modifier = Modifier
    fun fire(
        a: Action,
        vararg v: Float,
    ) = a.run(p.env, v.map { Num((it / density).toDouble()) })
    el.handlers["onTap"]?.let { a ->
        m = m.pointerInput(el, p.env) { detectTapGestures { o -> fire(a, o.x, o.y) } }
    }
    el.handlers["onDrag"]?.let { a ->
        m = m.pointerInput(el, p.env) { detectDragGestures { change, drag -> change.consume(); fire(a, drag.x, drag.y, change.position.x, change.position.y) } }
    }
    return m
}

@Composable
private fun TextNode(
    p: Props,
    mod: Modifier,
) {
    val style = p.observe("style")?.toString()
    val base =
        when (style) {
            "huge" -> WidgetMono.copy(fontSize = 44.sp)
            "title" -> WidgetMono.copy(fontSize = 20.sp)
            "small", "label" -> WidgetMono.copy(fontSize = 11.sp)
            else -> WidgetMono
        }
    val defaultColor: () -> Color = if (style == "muted" || style == "label") ({ Palette.muted }) else ({ Palette.foreground })
    val color = p.color("color", defaultColor)
    val textStyle =
        TextStyle(
            fontFamily =
                when (p.observe("font")?.toString()) {
                    "sans" -> FontFamily.SansSerif
                    "serif" -> FontFamily.Serif
                    "icon" -> NerdSymbols
                    else -> FontFamily.Monospace
                },
            fontSize = p.observe("fontSize")?.num()?.sp ?: base.fontSize,
            fontWeight = if (p.observe("bold")?.truthy == true) FontWeight.Bold else null,
            fontStyle = if (p.observe("italic")?.truthy == true) FontStyle.Italic else null,
            textAlign =
                when (p.observe("align")?.toString()) {
                    "center" -> TextAlign.Center
                    "end" -> TextAlign.End
                    else -> TextAlign.Start
                },
        )
    BasicText(
        p.observe("text")?.text().orEmpty(),
        if (p.has("width") || p.has("weight") || p.has("fill")) mod else mod.fillMaxWidth(),
        style = textStyle,
        // Big text stays on one line in narrow tiles instead of breaking mid-word.
        maxLines = p.observe("maxLines")?.num()?.toInt()?.coerceAtLeast(1) ?: if (style == "huge" || style == "title") 1 else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
        color = ColorProducer { color() },
    )
}

/** Fixed-column grid: children fill cells left to right. */
@Composable
private fun Grid(
    modifier: Modifier,
    columns: Int,
    gap: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val g = gap.roundToPx()
        val cell = ((constraints.maxWidth - g * (columns - 1)) / columns).coerceAtLeast(0)
        val placeables = measurables.map { it.measure(Constraints(0, cell, 0, Constraints.Infinity)) }
        val rows = placeables.chunked(columns)
        val heights = rows.map { r -> r.maxOf { it.height } }
        val height = (heights.sum() + g * (rows.size - 1).coerceAtLeast(0)).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(constraints.maxWidth, height) {
            var y = 0
            rows.forEachIndexed { ri, r ->
                r.forEachIndexed { ci, pl -> pl.place(ci * (cell + g), y) }
                y += heights[ri] + g
            }
        }
    }
}
