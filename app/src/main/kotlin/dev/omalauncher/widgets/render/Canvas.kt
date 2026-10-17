package dev.omalauncher.widgets.render

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import dev.omalauncher.ui.Palette
import dev.omalauncher.widgets.lang.Builtins
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

/** Drawing calls per frame; beyond this a Canvas stops and reports. */
private const val MAX_OPS = 2000

/** The most arguments a drawing call takes. */
private const val MAX_ARGS = 8

/** Plain-color brushes a Canvas keeps between frames. */
private const val MAX_SOLIDS = 256

/**
 * Runs a Canvas's drawing calls in the draw phase only: values they read
 * trigger a redraw, never a recomposition. Coordinates are in dp.
 */
@Composable
internal fun CanvasNode(
    p: Props,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer(32)
    val inst = p.inst
    val solids = remember { HashMap<Int, SolidColor>() }
    // Its own layer: the drawing is replayed when something around it redraws, and only re-run when what it reads changes.
    Canvas(modifier.graphicsLayer()) {
        val d = density
        val env = p.env.child(Num((size.width / d).toDouble()), Num((size.height / d).toDouble()))
        val painter = Painter(inst, measurer, this, d, solids)
        val started = System.nanoTime()
        try {
            inst.budget.run { scale(d, d, pivot = Offset.Zero) { with(painter) { paint(p.el.children, env) } } }
        } catch (e: WidgetError) {
            inst.report("Canvas: ${e.message}")
        } catch (e: RuntimeException) {
            inst.report("Canvas: $e")
        }
        inst.recordDraw(System.nanoTime() - started)
    }
}

private class Painter(
    val inst: WidgetInstance,
    val measurer: TextMeasurer,
    val outer: DrawScope,
    val density: Float,
    /** Plain-color brushes by ARGB, kept across frames: most ops fill with one of a few colors. */
    val solids: HashMap<Int, SolidColor>,
) {
    var ops = 0

    /** An op's number arguments; see draw(). */
    private val numbers = FloatArray(MAX_ARGS)

    fun eval(
        ev: dev.omalauncher.widgets.lang.Ev,
        env: Env,
    ): Value = inst.budget.run { ev(env) }

    fun DrawScope.paint(
        nodes: List<WNode>,
        env: Env,
    ) {
        // By index: an iterator per call adds up, this runs for every node of every frame.
        for (j in nodes.indices) {
            when (val n = nodes[j]) {
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
    }

    // Numbers first: num() returns a boxed Double?, and this runs for every coordinate of every op.
    private fun f(v: Value) = if (v is Num) v.v.toFloat() else v.num()?.toFloat() ?: 0f

    private fun solid(argb: Int): SolidColor {
        solids[argb]?.let { return it }
        // Colors animated over time would otherwise pile up.
        if (solids.size >= MAX_SOLIDS) solids.clear()
        return SolidColor(androidx.compose.ui.graphics.Color(argb)).also { solids[argb] = it }
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
            is Gradient -> {
                val colors = v.colors.map { androidx.compose.ui.graphics.Color(Builtins.argb(it, inst) ?: 0) }
                val c = v.coords
                if (v.radial) {
                    Brush.radialGradient(colors, Offset(c[0], c[1]), c[2].coerceAtLeast(0.01f))
                } else {
                    Brush.linearGradient(colors, Offset(c[0], c[1]), Offset(c[2], c[3]))
                }
            }
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
            "translate" -> return translate(v[0], v[1]) { paint(n.children!!, env) }
            "rotate" -> return rotate(v[0], Offset(v.opt(n, 1), v.opt(n, 2))) { paint(n.children!!, env) }
            "scale" -> {
                val sx = v[0]
                val sy = if (n.args.size > 1) v[1] else sx
                return scale(sx, sy, Offset(v.opt(n, 2), v.opt(n, 3))) { paint(n.children!!, env) }
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
                for ((b, s) in styles) drawRoundRect(b, Offset(v[0], v[1]), Size(v[2], v[3]), r, style = s)
            }
            "circle" -> for ((b, s) in styles) drawCircle(b, v[2], Offset(v[0], v[1]), style = s)
            "arc" -> {
                val r = v[2]
                val topLeft = Offset(v[0] - r, v[1] - r)
                // 0 degrees is 12 o'clock, clockwise, like a clock or a gauge.
                for ((b, s) in styles) drawArc(b, v[3] - 90f, v[4], s is Fill, topLeft, Size(2 * r, 2 * r), style = s)
            }
            "line" -> drawLine(stroke ?: fill!!, Offset(v[0], v[1]), Offset(v[2], v[3]), width, cap)
            "path" -> {
                val points = Builtins.iterable(first).mapNotNull(::point)
                if (points.size < 2) return
                val path = Path()
                points.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                if (named["closed"]?.truthy == true) path.close()
                for ((b, s) in styles) {
                    // An unclosed path is a line: only fill it when asked to.
                    if (s is Fill && named["fill"] == null) continue
                    drawPath(path, b, style = s)
                }
            }
            "text" -> {
                val color = Builtins.argb(named["color"] ?: named["fill"] ?: Num(0.0), inst)?.let { androidx.compose.ui.graphics.Color(it) } ?: Palette.foreground
                val layout =
                    measurer.measure(
                        first.text(),
                        TextStyle(
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = (named["size"]?.num() ?: 13.0).sp,
                            fontWeight = if (named["bold"]?.truthy == true) FontWeight.Bold else null,
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
                val palette = (named["palette"] as? Obj)?.fields
                val brushes = if (palette == null) null else HashMap<Char, Brush?>()
                for (i in 0 until sprite.size) {
                    val letter = sprite.letters[i]
                    val b = if (palette == null || brushes == null) fill else brushes.getOrPut(letter) { brush(palette[letter.toString()]) }
                    if (b == null) continue
                    val col = sprite.runs[3 * i]
                    val row = sprite.runs[3 * i + 1]
                    // Edges on whole device pixels: neighbouring runs meet exactly, with no seam and no overlap.
                    val left = snap(x + col * px)
                    val top = snap(y + row * px)
                    drawRect(b, Offset(left, top), Size(snap(x + (col + sprite.runs[3 * i + 2]) * px) - left, snap(y + (row + 1) * px) - top))
                }
            }
            "clip" -> clipRect(v[0], v[1], v[0] + v[2], v[1] + v[3]) { paint(n.children!!, env) }
            "layer" -> {
                val alpha = v[0].coerceIn(0f, 1f)
                drawIntoCanvas { c ->
                    c.saveLayer(Rect(Offset.Zero, size), Paint().apply { this.alpha = alpha })
                    // Restored even when a nested op throws (too many ops), or the rest of the screen draws into the layer.
                    try {
                        paint(n.children!!, env)
                    } finally {
                        c.restore()
                    }
                }
            }
        }
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
    Spacer(
        modifier.drawBehind {
            val started = System.nanoTime()
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("time", inst.time().toFloat())
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
            drawRect(brush)
            inst.recordDraw(System.nanoTime() - started)
        },
    )
}
