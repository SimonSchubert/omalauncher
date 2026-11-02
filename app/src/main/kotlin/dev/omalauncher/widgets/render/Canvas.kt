package dev.omalauncher.widgets.render

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.Palette
import dev.omalauncher.widgets.lang.Builtins
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Env
import dev.omalauncher.widgets.lang.Gradient
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.WNode
import dev.omalauncher.widgets.lang.WidgetError
import dev.omalauncher.widgets.lang.num
import dev.omalauncher.widgets.lang.text
import dev.omalauncher.widgets.runtime.WidgetImage
import dev.omalauncher.widgets.runtime.WidgetInstance
import dev.omalauncher.widgets.runtime.drawPicture
import androidx.compose.ui.graphics.nativeCanvas
import java.util.IdentityHashMap

/** Drawing calls per frame; beyond this a Canvas stops and reports. */
private const val MAX_OPS = 2000

/** The most arguments a drawing call takes. */
private const val MAX_ARGS = 8

/** Plain-color brushes a Canvas keeps between frames. */
private const val MAX_SOLIDS = 256

/** Paths and sprite palettes a Canvas keeps between frames. */
private const val MAX_KEPT = 256

/**
 * What a Canvas keeps between frames. Paths and palettes are kept by the identity of the
 * list or map they're made from: a top-level let's art is the same value until what it
 * reads changes, and comparing its contents would cost as much as building it again.
 */
private class Kept {
    /** Plain-color brushes by ARGB: most ops fill with one of a few colors. */
    val solids = HashMap<Int, SolidColor>()
    val paths = IdentityHashMap<Value, Path>()
    val closedPaths = IdentityHashMap<Value, Path>()
    /** A sprite palette's brushes by letter; they follow the theme, so they're dropped when it changes. */
    val palettes = IdentityHashMap<Obj, HashMap<Char, Brush?>>()
    var theme: Any? = null
}

/** How many frames an op that read `time` is drawn as it is before it's tried in a run again. */
private const val MOVING_FRAMES = 60L

/** A run recorded again on this many frames in a row is drawn as it is for a while: what it reads changes every frame. */
private const val CHURN_FRAMES = 3

/**
 * Ops of an animated Canvas that don't read `time`, side by side in the same list,
 * recorded into a layer once and replayed every frame until something they read
 * changes. A scene that never moves under its sprites costs nothing to draw again.
 */
private class Run(
    val layer: GraphicsLayer,
) {
    /** The ops it holds: from its start up to here. */
    var end = -1

    /** How many drawing calls it holds; a replay counts them toward MAX_OPS as drawing them would. */
    var ops = 0

    @Volatile var stale = true

    /** The frame it was last recorded on, and on how many frames in a row before that. */
    var recorded = -1L
    var streak = 0

    /** The frame it was last drawn on; one that wasn't drawn on a frame is let go. */
    var used = -1L

    /** True while its layer records it; a layer can also run its block again by itself, after the system trims memory. */
    var recording = false
}

/** A Canvas's runs, by the list their ops are in and where they start in it; see [Run]. */
private class Runs(
    val graphics: GraphicsContext,
) {
    /** Bumped when a run goes stale, so the canvas draws again even if nothing it read itself changed. */
    val version = mutableIntStateOf(0)

    val onStale: (Run) -> Unit = { run ->
        run.stale = true
        version.intValue++
    }

    /** What each run read as it was recorded; runs straight away, on whichever thread a change is applied. */
    val observer = SnapshotStateObserver { it() }
    private val runs = IdentityHashMap<List<WNode>, HashMap<Int, Run>>()

    /** Ops that read `time`, and the frame they're tried in a run again on. */
    private val moving = IdentityHashMap<WNode, Long>()
    var frame = 0L

    /** Whether the last frame read `time`. A Canvas that doesn't only draws when what it reads changes, so runs would only add work. */
    var animating = false
    private var props: Props? = null
    private var size = Size.Zero
    private var density = 0f

    /** Starts a frame; runs recorded for another size, density or node are let go. */
    fun begin(
        p: Props,
        size: Size,
        density: Float,
    ) {
        frame++
        if (p !== props || size != this.size || density != this.density) {
            clear()
            props = p
            this.size = size
            this.density = density
        }
    }

    /** Lets go of the runs this frame didn't draw: the other side of an if, or ops that started reading time. */
    fun end() {
        val lists = runs.values.iterator()
        while (lists.hasNext()) {
            val starts = lists.next()
            val each = starts.values.iterator()
            while (each.hasNext()) {
                val run = each.next()
                if (run.used != frame) {
                    release(run)
                    each.remove()
                }
            }
            if (starts.isEmpty()) lists.remove()
        }
    }

    fun isMoving(n: WNode): Boolean {
        val until = moving[n] ?: return false
        if (until > frame) return true
        moving.remove(n)
        return false
    }

    fun moved(n: WNode) {
        moving[n] = frame + MOVING_FRAMES
    }

    fun run(
        nodes: List<WNode>,
        start: Int,
    ): Run = runs.getOrPut(nodes) { HashMap() }.getOrPut(start) { Run(graphics.createGraphicsLayer()) }.also { it.used = frame }

    private fun release(run: Run) {
        observer.clear(run)
        graphics.releaseGraphicsLayer(run.layer)
    }

    fun clear() {
        for (starts in runs.values) for (run in starts.values) release(run)
        runs.clear()
        moving.clear()
        animating = false
    }
}

/**
 * Runs a Canvas's drawing calls in the draw phase only: values they read
 * trigger a redraw, never a recomposition. Coordinates are in dp.
 */
@Composable
internal fun CanvasNode(
    p: Props,
    modifier: Modifier,
) {
    // Room for every glyph of an animated text effect (a matrix rain cycles through ~60): a smaller cache misses each frame.
    val measurer = rememberTextMeasurer(256)
    val inst = p.inst
    val kept = remember { Kept() }
    val graphics = LocalGraphicsContext.current
    val runs = remember(graphics) { Runs(graphics) }
    DisposableEffect(runs) {
        runs.observer.start()
        onDispose {
            runs.observer.stop()
            runs.clear()
        }
    }
    // Its own layer: the drawing is replayed when something around it redraws, and only re-run when what it reads changes.
    Canvas(modifier.graphicsLayer()) {
        // A run gone stale draws the canvas again.
        runs.version.intValue
        val d = density
        val env = p.env.child(Num((size.width / d).toDouble()), Num((size.height / d).toDouble()))
        runs.begin(p, size, d)
        // Only an animated canvas keeps runs: one that isn't is only drawn when something it reads changes.
        val keeping = runs.animating
        val painter = Painter(inst, measurer, this, d, kept, if (keeping) runs else null)
        val reads = inst.timeReads
        val started = System.nanoTime()
        inst.drawing {
            inst.guarded("Canvas") {
                inst.budget.run {
                    scale(d, d, pivot = Offset.Zero) {
                        with(painter) { if (keeping) paintKept(p.el.children, env) { env } else paint(p.el.children, env) }
                    }
                }
            }
        }
        runs.animating = inst.timeReads != reads
        if (keeping) runs.end() else if (!runs.animating) runs.clear()
        inst.recordDraw(System.nanoTime() - started)
    }
}

private class Painter(
    val inst: WidgetInstance,
    val measurer: TextMeasurer,
    val outer: DrawScope,
    val density: Float,
    val kept: Kept,
    /** Null for a canvas that doesn't animate; see [paintKept]. */
    val runs: Runs?,
) {
    var ops = 0

    /** Set while a moving op with ops inside it is drawn where runs are kept: its ops are painted the same way; see [inside]. */
    private var keptInside: (() -> Env)? = null

    /** What a lone op inside layer(alpha) { } is drawn at instead of in a layer of its own; see "layer". */
    private var alpha = 1f

    /** An op's number arguments; see draw(). */
    private val numbers = FloatArray(MAX_ARGS)

    fun eval(
        ev: dev.omalauncher.widgets.lang.Ev,
        env: Env,
    ): Value = inst.budget.eval(ev, env)

    fun DrawScope.paint(
        nodes: List<WNode>,
        env: Env,
    ) {
        // By index: an iterator per call adds up, this runs for every node of every frame.
        for (j in nodes.indices) paintNode(nodes[j], env)
    }

    private fun DrawScope.paintNode(
        n: WNode,
        env: Env,
    ) {
        when (n) {
            is WNode.Draw -> draw(n, env)
            is WNode.If -> paint(if (eval(n.cond, env).truthy) n.then else n.orElse, env)
            is WNode.For -> {
                val list = Builtins.iterable(eval(n.list, env))
                for (i in list.indices) paint(n.body, env.child(list[i], Num(i.toDouble())))
            }
            // Each let is computed once per draw, on first use.
            is WNode.Lets -> paint(n.children, env.lets(n.values))
            else -> {}
        }
    }

    /**
     * Paints [nodes] of an animated canvas, keeping runs of the ops that don't read `time`
     * (see [Run]): the canvas's own ops, and those inside an if, a let or a transform that
     * does read it, as their env is the same from frame to frame. The ops inside a for are
     * painted as they are, as a whole: each pass has an env of its own.
     *
     * [fresh] makes [env] again with none of its lets read yet, for recording a run: a let
     * that an op before it already read wouldn't otherwise show the run what it reads.
     */
    fun DrawScope.paintKept(
        nodes: List<WNode>,
        env: Env,
        fresh: () -> Env,
    ) {
        val runs = runs!!
        var j = 0
        while (j < nodes.size) {
            val n = nodes[j]
            if (runs.isMoving(n)) {
                paintMoving(n, env, fresh)
                j++
                continue
            }
            var end = j + 1
            while (end < nodes.size && !runs.isMoving(nodes[end])) end++
            paintRun(nodes, j, end, env, fresh)
            j = end
        }
    }

    private fun DrawScope.paintMoving(
        n: WNode,
        env: Env,
        fresh: () -> Env,
    ) {
        when (n) {
            is WNode.If -> paintKept(if (eval(n.cond, env).truthy) n.then else n.orElse, env, fresh)
            is WNode.Lets -> {
                val values = n.values
                paintKept(n.children, env.lets(values)) { fresh().lets(values) }
            }
            is WNode.Draw ->
                if (n.children == null) {
                    draw(n, env)
                } else {
                    keptInside = fresh
                    try {
                        draw(n, env)
                    } finally {
                        keptInside = null
                    }
                }
            else -> paintNode(n, env)
        }
    }

    /** The ops inside a transform, clip or layer: kept in runs when it's a moving op where runs are kept, see [paintMoving]. */
    private fun DrawScope.inside(
        children: List<WNode>,
        env: Env,
    ) {
        val fresh = keptInside ?: return paint(children, env)
        keptInside = null
        paintKept(children, env, fresh)
    }

    /** Draws [nodes] from [start] to [end] from their run, recording it first if it's new or stale. */
    private fun DrawScope.paintRun(
        nodes: List<WNode>,
        start: Int,
        end: Int,
        env: Env,
        fresh: () -> Env,
    ) {
        val runs = runs!!
        val run = runs.run(nodes, start)
        if (!run.stale && run.end == end) {
            // Over the limit, drawn as they are, to stop at the same op.
            if (ops + run.ops > MAX_OPS) return paintEach(nodes, start, end, env)
            ops += run.ops
            drawLayer(run.layer)
            return
        }
        // Recorded again frame after frame: what it reads changes as often as time does.
        run.streak = if (run.end == end && run.recorded == runs.frame - 1) run.streak + 1 else 0
        if (run.streak >= CHURN_FRAMES) {
            for (k in start until end) runs.moved(nodes[k])
            return paintEach(nodes, start, end, env)
        }
        run.end = end
        run.stale = false
        run.recorded = runs.frame
        val recordEnv = fresh()
        val before = ops
        var failed: Throwable? = null
        run.recording = true
        try {
            runs.observer.observeReads(run, runs.onStale) {
                run.layer.record(drawContext.density, layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) {
                    // Run again by the layer itself: what it would draw is out of date, so draw it again next frame.
                    if (!run.recording) {
                        runs.onStale(run)
                        return@record
                    }
                    for (k in start until end) {
                        val reads = inst.timeReads
                        try {
                            paintNode(nodes[k], recordEnv)
                        } catch (e: Throwable) {
                            failed = e
                            break
                        }
                        if (inst.timeReads != reads) runs.moved(nodes[k])
                    }
                }
            }
        } finally {
            run.recording = false
        }
        run.ops = ops - before
        drawLayer(run.layer)
        // An op that read time is drawn as it is from the next frame on, and the run is recorded again without it.
        if (failed != null || (start until end).any { runs.isMoving(nodes[it]) }) run.stale = true
        failed?.let { throw it }
    }

    private fun DrawScope.paintEach(
        nodes: List<WNode>,
        start: Int,
        end: Int,
        env: Env,
    ) {
        for (k in start until end) paintNode(nodes[k], env)
    }

    // Numbers first: num() returns a boxed Double?, and this runs for every coordinate of every op.
    private fun f(v: Value) = if (v is Num) v.v.toFloat() else v.num()?.toFloat() ?: 0f

    private fun solid(argb: Int): SolidColor {
        val solids = kept.solids
        solids[argb]?.let { return it }
        // Colors animated over time would otherwise pile up.
        if (solids.size >= MAX_SOLIDS) solids.clear()
        return SolidColor(androidx.compose.ui.graphics.Color(argb)).also { solids[argb] = it }
    }

    /** [points] as a Path, kept for the next frame; see [Kept]. */
    private fun path(
        points: Value,
        closed: Boolean,
    ): Path? {
        val paths = if (closed) kept.closedPaths else kept.paths
        paths[points]?.let { return it }
        val offsets = Builtins.iterable(points).mapNotNull(::point)
        if (offsets.size < 2) return null
        val path = Path()
        offsets.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        if (closed) path.close()
        // Points built anew each frame would otherwise pile up.
        if (paths.size >= MAX_KEPT) paths.clear()
        paths[points] = path
        return path
    }

    /** A sprite palette's brushes, filled in letter by letter as the sprites using it need them. */
    private fun palette(palette: Obj): HashMap<Char, Brush?> {
        val palettes = kept.palettes
        // Theme names in a palette are colors of the theme they were looked up in.
        val theme = Palette.current
        if (kept.theme !== theme) {
            palettes.clear()
            kept.theme = theme
        }
        palettes[palette]?.let { return it }
        if (palettes.size >= MAX_KEPT) palettes.clear()
        return HashMap<Char, Brush?>().also { palettes[palette] = it }
    }

    /** Named arguments, evaluated when read: an op only pays for the ones it uses. */
    private inner class NamedArgs(
        private val evs: Map<String, dev.omalauncher.widgets.lang.Ev>,
        private val env: Env,
    ) {
        operator fun get(key: String): Value? = evs[key]?.let { eval(it, env) }
    }

    private val fillOnly = ArrayList<Pair<Brush, DrawStyle>>(2)
    private var stroke: Stroke? = null

    /** Fill, then stroke, as present. Reuses its list and the stroke style across ops; valid until the next call. */
    private fun styles(
        fill: Brush?,
        brush: Brush?,
        width: Float,
        cap: StrokeCap,
    ): List<Pair<Brush, DrawStyle>> {
        fillOnly.clear()
        fill?.let { fillOnly += it to Fill }
        if (brush != null) {
            val s = stroke?.takeIf { it.width == width && it.cap == cap } ?: Stroke(width, cap = cap).also { stroke = it }
            fillOnly += brush to s
        }
        return fillOnly
    }

    private fun brush(v: Value?): Brush? =
        when (v) {
            null -> null
            // Its coordinates are already the shape's own, so as on a node of size 1.
            is Gradient -> backgroundBrush(v, Size(1f, 1f), inst)
            else -> Builtins.argb(v, inst)?.let(::solid)
        }

    private fun DrawScope.draw(
        n: WNode.Draw,
        env: Env,
    ) {
        if (++ops > MAX_OPS) throw WidgetError("more than $MAX_OPS drawing calls in one frame")
        // The first argument of these is a value (points, text, a src, art); every other one is a number,
        // read straight off the numeric fast path into a buffer every op shares. That's safe: an op reads
        // its numbers before drawing the ops nested in it.
        val values = if (n.op == "path" || n.op == "text" || n.op == "image" || n.op == "sprite") 1 else 0
        val first = if (values > 0) eval(n.args[0], env) else Null
        val v = numbers
        for (i in values until n.args.size) v[i] = n.args[i].num(env, 0.0).toFloat()
        // Transforms take no paint: skip evaluating it (this runs per op, per frame).
        when (n.op) {
            "translate" -> return translate(v[0], v[1]) { inside(n.children!!, env) }
            "rotate" -> return rotate(v[0], Offset(v.opt(n, 1), v.opt(n, 2))) { inside(n.children!!, env) }
            "scale" -> {
                val sx = v[0]
                val sy = if (n.args.size > 1) v[1] else sx
                return scale(sx, sy, Offset(v.opt(n, 2), v.opt(n, 3))) { inside(n.children!!, env) }
            }
        }
        val named = NamedArgs(n.named, env)
        val width = named["width"]?.num()?.toFloat() ?: 1f
        val cap =
            when (named["cap"]?.toString()) {
                "round" -> StrokeCap.Round
                "square" -> StrokeCap.Square
                else -> StrokeCap.Butt
            }
        val stroke = brush(named["stroke"])
        val fill = brush(named["fill"]) ?: if (stroke == null) brush(named["color"]) ?: solid(Palette.foreground.toArgb()) else null
        val styles = styles(fill, stroke, width, cap)

        when (n.op) {
            "rect" -> {
                val r = CornerRadius(named["radius"]?.num()?.toFloat() ?: 0f)
                for ((b, s) in styles) drawRoundRect(b, Offset(v[0], v[1]), Size(v[2], v[3]), r, alpha = alpha, style = s)
            }
            "circle" -> for ((b, s) in styles) drawCircle(b, v[2], Offset(v[0], v[1]), alpha = alpha, style = s)
            "arc" -> {
                val r = v[2]
                val topLeft = Offset(v[0] - r, v[1] - r)
                // 0 degrees is 12 o'clock, clockwise, like a clock or a gauge.
                for ((b, s) in styles) drawArc(b, v[3] - 90f, v[4], s is Fill, topLeft, Size(2 * r, 2 * r), alpha = alpha, style = s)
            }
            "line" -> drawLine(stroke ?: fill!!, Offset(v[0], v[1]), Offset(v[2], v[3]), width, cap, alpha = alpha)
            "path" -> {
                val path = path(first, named["closed"]?.truthy == true) ?: return
                for ((b, s) in styles) {
                    // An unclosed path is a line: only fill it when asked to.
                    if (s is Fill && named["fill"] == null) continue
                    drawPath(path, b, alpha = alpha, style = s)
                }
            }
            "text" -> {
                val color = Builtins.argb(named["color"] ?: named["fill"] ?: Num(0.0), inst)?.let { androidx.compose.ui.graphics.Color(it) } ?: Palette.foreground
                // Since owl 10 styled like a Text: its font, weight, slant and spacing, and a list is spans.
                val styled = inst.program.level >= 10
                val layout =
                    measurer.measure(
                        if (styled && first is Lst) spans(first, inst) else AnnotatedString(first.text()),
                        TextStyle(
                            color = color,
                            fontFamily = if (styled) fontFamily(named["font"], inst) else LauncherStyle.font,
                            fontSize = (named["size"]?.num() ?: 13.0).sp,
                            fontWeight = (if (styled) weight(named["fontWeight"]) else null) ?: if (named["bold"]?.truthy == true) FontWeight.Bold else null,
                            fontStyle = if (styled && named["italic"]?.truthy == true) FontStyle.Italic else null,
                            letterSpacing = (if (styled) textUnit(named["letterSpacing"]) else null) ?: TextUnit.Unspecified,
                        ),
                    )
                val w = layout.size.width / density
                val h = layout.size.height / density
                val x =
                    when (named["align"]?.toString()) {
                        "center" -> v[1] - w / 2
                        "end" -> v[1] - w
                        else -> v[1]
                    }
                // Text is measured in pixels; undo the dp scale while drawing it.
                val y = v[2] - h / 2
                scale(1 / density, 1 / density, pivot = Offset.Zero) { drawText(layout, topLeft = Offset(x * density, y * density)) }
            }
            "image" -> {
                val color = named["color"]?.let { Builtins.argb(it, inst) }
                // A src still loading draws nothing yet, rather than asking for an image named "null".
                if (first == Null) return
                when (val image = inst.image(first.toString()) ?: return) {
                    is WidgetImage.Raster -> {
                        val bitmap = image.bitmap
                        drawImage(
                            bitmap,
                            dstOffset = IntOffset(v[1].toInt(), v[2].toInt()),
                            dstSize = IntSize(v[3].toInt(), v[4].toInt()),
                            // An alpha-only image (an app icon's silhouette, with monochrome icons) is a shape: color it like one.
                            colorFilter =
                                if (bitmap.config == ImageBitmapConfig.Alpha8) {
                                    ColorFilter.tint(androidx.compose.ui.graphics.Color(color ?: Palette.foreground.toArgb()))
                                } else {
                                    null
                                },
                        )
                    }
                    // Drawn as vectors, so it stays sharp under the canvas's density scale.
                    is WidgetImage.Vector ->
                        drawIntoCanvas {
                            val tint = color?.let { c -> android.graphics.PorterDuffColorFilter(c, android.graphics.PorterDuff.Mode.SRC_IN) }
                            drawPicture(it.nativeCanvas, image.picture, v[1].toFloat(), v[2].toFloat(), v[3].toFloat(), v[4].toFloat(), tint)
                        }
                }
            }
            "sprite" -> {
                val sprite = inst.sprite(first)
                ops += sprite.size - 1
                if (ops > MAX_OPS) throw WidgetError("more than $MAX_OPS drawing calls in one frame (a sprite run counts as one)")
                val x = v[1]
                val y = v[2]
                val px = v[3]
                // Without a palette every letter takes the fill; with one, letters it doesn't name aren't drawn.
                val paletteObj = named["palette"] as? Obj
                val palette = paletteObj?.fields
                val brushes = paletteObj?.let(::palette)
                for (i in 0 until sprite.size) {
                    val letter = sprite.letters[i]
                    val b = if (palette == null || brushes == null) fill else brushes.getOrPut(letter) { brush(palette[letter.toString()]) }
                    if (b == null) continue
                    val col = sprite.runs[3 * i]
                    val row = sprite.runs[3 * i + 1]
                    // Edges on whole device pixels: neighbouring runs meet exactly, with no seam and no overlap.
                    val left = snap(x + col * px)
                    val top = snap(y + row * px)
                    drawRect(b, Offset(left, top), Size(snap(x + (col + sprite.runs[3 * i + 2]) * px) - left, snap(y + (row + 1) * px) - top), alpha = alpha)
                }
            }
            "clip" -> clipRect(v[0], v[1], v[0] + v[2], v[1] + v[3]) { inside(n.children!!, env) }
            "layer" -> {
                val alpha = v[0].coerceIn(0f, 1f)
                val children = n.children!!
                // Fully opaque, a layer looks the same as no layer; fully clear, nothing inside shows.
                if (alpha >= 1f) return inside(children, env)
                if (alpha <= 0f) return
                // A layer is an offscreen buffer the size of the canvas. One op that doesn't overlap itself looks
                // the same drawn at the layer's alpha, as a coin fading out does, and that needs no buffer.
                val only = children.singleOrNull() as? WNode.Draw
                if (only != null && fadesAlone(only)) {
                    val before = this@Painter.alpha
                    this@Painter.alpha = before * alpha
                    try {
                        return draw(only, env)
                    } finally {
                        this@Painter.alpha = before
                    }
                }
                drawIntoCanvas { c ->
                    c.saveLayer(Rect(Offset.Zero, size), Paint().apply { this.alpha = alpha })
                    // Restored even when a nested op throws (too many ops), or the rest of the screen draws into the layer.
                    try {
                        inside(children, env)
                    } finally {
                        c.restore()
                    }
                }
            }
        }
    }

    /**
     * Whether [n] looks the same drawn at an alpha as in a layer at that alpha: it has no ops
     * inside it and paints each pixel once. A shape with both a fill and a stroke paints its
     * edge twice, so the fill would show through the stroke, and text's letters can overlap
     * (a script font joins them).
     */
    private fun fadesAlone(n: WNode.Draw) =
        n.children == null &&
            when (n.op) {
                "sprite", "line" -> true
                "rect", "circle", "arc", "path" -> !("fill" in n.named && "stroke" in n.named)
                else -> false
            }

    /** An optional number argument: 0 when it's left out. */
    private fun FloatArray.opt(
        n: WNode.Draw,
        i: Int,
    ) = if (n.args.size > i) this[i] else 0f

    /** A dp coordinate rounded to the nearest device pixel. */
    private fun snap(v: Float) = Math.round(v * density) / density

    private fun point(v: Value): Offset? =
        when (v) {
            is Lst -> if (v.items.size >= 2) Offset(f(v.items[0]), f(v.items[1])) else null
            is Obj -> Offset(f(v.fields["x"] ?: return null), f(v.fields["y"] ?: return null))
            else -> null
        }
}

/** Declared for every shader; widget code must not declare them again. */
private const val SHADER_HEADER = "uniform float2 size;\nuniform float time;\n"

/** A full-bleed AGSL fragment shader: `half4 main(float2 fragCoord)`, redrawn every frame it reads time. */
@RequiresApi(33)
@Composable
internal fun ShaderNode(
    p: Props,
    modifier: Modifier,
) {
    val inst = p.inst
    val code = p.observe("code")?.toString().orEmpty()
    val shader = remember(code) { runCatching { RuntimeShader(SHADER_HEADER + code) }.onFailure { inst.report("Shader: ${it.message}") }.getOrNull() }
    val fallback = p.color("fallback") { Palette.surface }
    if (shader == null) {
        Spacer(modifier.drawBehind { drawRect(fallback()) })
        return
    }
    val brush = remember(shader) { ShaderBrush(shader) }
    // Reading time keeps the widget's frame clock running, so a shader that never uses it isn't redrawn every frame.
    val readsTime = remember(code) { Compiler.shaderReadsTime(code) }
    // One that doesn't is kept in its own layer, drawn again only when its uniforms change: otherwise every frame of
    // anything on the page, a swipe too, ran it over every pixel again (manga on a Galaxy Tab A8: 125 ms of GPU a frame).
    Spacer(
        (if (readsTime) modifier else modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }).drawBehind {
            val started = System.nanoTime()
            inst.drawing {
                shader.setFloatUniform("size", size.width, size.height)
                if (readsTime) shader.setFloatUniform("time", inst.time().toFloat())
                (p.now("uniforms") as? Obj)?.fields?.forEach { (name, v) ->
                    try {
                        when {
                            v is Lst -> shader.setFloatUniform(name, v.items.map { f -> f.num()?.toFloat() ?: 0f }.toFloatArray())
                            v.num() != null -> shader.setFloatUniform(name, v.num()!!.toFloat())
                            else ->
                                Builtins.argb(v, inst)?.let { c ->
                                    val col = androidx.compose.ui.graphics.Color(c)
                                    shader.setFloatUniform(name, col.red, col.green, col.blue, col.alpha)
                                }
                        }
                    } catch (e: IllegalArgumentException) {
                        inst.report("Shader uniform '$name': ${e.message}")
                    }
                }
            }
            drawRect(brush)
            inst.recordDraw(System.nanoTime() - started)
        },
    )
}
