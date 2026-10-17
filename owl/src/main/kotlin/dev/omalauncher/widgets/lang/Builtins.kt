package dev.omalauncher.widgets.lang

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToLong

class Args(
    val args: List<Value>,
    val host: Host?,
) {
    val size get() = args.size

    operator fun get(i: Int): Value = args.getOrElse(i) { Null }

    fun num(i: Int): Double = get(i).let { if (it is Num) it.v else it.num() ?: 0.0 }

    fun num(
        i: Int,
        default: Double,
    ): Double = if (i < args.size) get(i).let { if (it is Num) it.v else it.num() ?: default } else default

    fun int(
        i: Int,
        default: Int,
    ): Int = if (i < args.size) get(i).num()?.toInt() ?: default else default

    fun str(i: Int): String = get(i).let { if (it == Null) "" else it.toString() }

    fun list(i: Int): List<Value> =
        when (val v = get(i)) {
            is Lst -> v.items
            is Obj -> v.fields.values.toList()
            Null -> emptyList()
            else -> listOf(v)
        }

    fun fn(i: Int): Fn = get(i) as? Fn ?: throw WidgetError("argument ${i + 1} must be a function like x => x.name")

    fun tick(n: Int) = host?.budget?.tick(n)
}

class Builtin(
    val min: Int,
    val max: Int,
    /** Can be folded at compile time when all arguments are constant. */
    val pure: Boolean = true,
    val impl: (Args) -> Value,
) {
    /** Null in, null out: formatting a value that hasn't loaded yet shows "…", not "0". */
    fun strict() = Builtin(min, max, pure) { a -> if (a[0] == Null) Null else impl(a) }
}

object Builtins {
    /** Lists built by widgets are capped; commands can print a lot. */
    const val MAX_LIST = 1000

    val theme =
        setOf(
            "accent", "background", "foreground", "muted", "surface", "border",
            "red", "green", "yellow", "blue", "magenta", "cyan", "orange", "purple", "brown", "white", "black",
            "bright_red", "bright_green", "bright_yellow", "bright_blue", "bright_magenta", "bright_cyan",
            "bright_foreground", "dark_foreground", "lighter_background", "dark_background", "selection", "cursor",
        ) + (0..15).map { "color$it" }

    val constants = mapOf("pi" to Num(PI), "transparent" to Col(null, 0))

    /** Handler-only calls; they reach [Host.act]. */
    val actions = mapOf("terminal" to 1..2, "app" to 1..1, "run" to 1..1, "post" to 1..3, "url" to 1..1, "launcher" to 1..1, "refresh" to 0..1, "media" to 1..1, "androidApp" to 1..1, "copy" to 1..1, "notify" to 1..2, "volume" to 1..1, "notification" to 2..2, "screen" to 0..0, "close" to 0..0, "torch" to 1..1, "share" to 1..1, "haptic" to 0..0, "sound" to 1..1)

    private fun lst(items: List<Value>) = Lst(capped(items))

    /** subList, not take(): that would copy every list, every frame. */
    private fun capped(items: List<Value>) = if (items.size > MAX_LIST) items.subList(0, MAX_LIST) else items

    private fun entriesOf(o: Obj): List<Value> = o.fields.map { (k, v) -> Obj(mapOf("key" to Str(k), "value" to v)) }

    /** What `for` and list properties walk: a list's items, a map's {key, value} entries, at most [MAX_LIST]; nothing for anything else. */
    fun iterable(v: Value): List<Value> =
        when (v) {
            is Lst -> capped(v.items)
            is Obj -> capped(entriesOf(v))
            else -> emptyList()
        }

    private fun n(d: Double) = Num(d)

    private fun math(f: (Double) -> Double) = Builtin(1, 1) { a -> n(f(a.num(0))) }.strict()

    private fun compare(
        a: Value,
        b: Value,
    ): Int {
        // Numeric text compares as numbers: command output is always text.
        val x = a.num()
        val y = b.num()
        return if (x != null && y != null) x.compareTo(y) else a.toString().compareTo(b.toString())
    }

    /** ARGB of any color-like value: theme names, #hex strings, colors. */
    fun argb(
        v: Value,
        host: Host?,
    ): Int? =
        when (v) {
            is Col -> (v.theme?.let { host?.theme(it) } ?: v.argb).let { withAlpha(it, v.alpha) }
            is Str -> if (v.v.startsWith("#")) parseHex(v.v) else host?.theme(v.v)
            else -> null
        }

    fun withAlpha(
        argb: Int,
        alpha: Float,
    ): Int = if (alpha >= 1f) argb else ((((argb ushr 24) and 0xff) * alpha).toInt() shl 24) or (argb and 0xffffff)

    fun color(
        v: Value,
        host: Host?,
    ): Col? =
        when (v) {
            is Col -> v
            is Str -> if (v.v.startsWith("#")) parseHex(v.v)?.let { Col(null, it) } else if (v.v in theme) Col(v.v, 0xff888888.toInt()) else null
            else -> null
        }

    private fun rgb(
        r: Double,
        g: Double,
        b: Double,
        a: Double,
    ): Col {
        fun c(x: Double) = (x.coerceIn(0.0, 255.0) + 0.5).toInt()
        return Col(null, (c(a * 255) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b))
    }

    private fun hsl(
        h: Double,
        s: Double,
        l: Double,
        a: Double,
    ): Col {
        val hh = ((h % 360) + 360) % 360 / 60
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val x = c * (1 - kotlin.math.abs(hh % 2 - 1))
        val (r, g, b) =
            when (hh.toInt()) {
                0 -> Triple(c, x, 0.0)
                1 -> Triple(x, c, 0.0)
                2 -> Triple(0.0, c, x)
                3 -> Triple(0.0, x, c)
                4 -> Triple(x, 0.0, c)
                else -> Triple(c, 0.0, x)
            }
        val m = l - c / 2
        return rgb((r + m) * 255, (g + m) * 255, (b + m) * 255, a)
    }

    private fun gradient(
        radial: Boolean,
        a: Args,
        coords: Int,
    ): Gradient {
        val colors = a.list(coords).mapNotNull { color(it, a.host) }
        if (colors.size < 2) throw WidgetError("a gradient needs at least two colors")
        return Gradient(radial, (0 until coords).map { a.num(it).toFloat() }, colors)
    }

    private val regexCache = HashMap<String, Regex>()

    private fun regex(p: String) = synchronized(regexCache) { regexCache.getOrPut(p) { Regex(p) } }

    private val timeFormats = HashMap<String, DateTimeFormatter>()

    /** The language [timeFormats] name days and months in: the phone's can change while widgets run. */
    private var timeFormatsLocale: Locale? = null

    private fun fmtTime(
        seconds: Double,
        pattern: String,
    ): String {
        val locale = Locale.getDefault()
        val f =
            synchronized(timeFormats) {
                if (locale != timeFormatsLocale) {
                    timeFormats.clear()
                    timeFormatsLocale = locale
                }
                timeFormats.getOrPut(pattern) { DateTimeFormatter.ofPattern(pattern, locale) }
            }
        return f.format(Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(ZoneId.systemDefault()))
    }

    /**
     * Omarchy's weather glyphs (Nerd Font nf-weather, drawn with `font: icon`) for a
     * wttr.in weatherCode. Same table as omarchy's weather panel Model.js.
     */
    private fun weatherIcon(
        code: Int,
        night: Boolean,
    ): String =
        when (code) {
            113 -> if (night) "\ue32b" else "\ue30d"
            116 -> if (night) "\ue32e" else "\ue302"
            143, 248, 260 -> if (night) "\ue346" else "\ue313"
            176, 263, 353 -> if (night) "\ue333" else "\ue308"
            179, 227, 230, 323, 326, 368 -> if (night) "\ue327" else "\ue30a"
            182, 185, 281, 284, 311, 314, 317, 320, 350, 362, 365, 374, 377 -> "\ue3ad"
            200, 386, 389, 392, 395 -> "\ue31d"
            266, 293, 296, 299, 302, 305, 308, 356, 359 -> "\ue318"
            329, 332, 335, 338, 371 -> "\ue31a"
            else -> "\ue33d"
        }

    private fun fixed(
        x: Double,
        digits: Int,
    ) = String.format(Locale.ROOT, "%.${digits.coerceIn(0, 10)}f", x)

    fun interface Num1 {
        fun of(x: Double): Double
    }

    fun interface Num2 {
        fun of(
            x: Double,
            y: Double,
        ): Double
    }

    fun interface Num3 {
        fun of(
            x: Double,
            y: Double,
            z: Double,
        ): Double
    }

    /*
     * Number built-ins as functions of doubles, by arity, for the compiler's
     * fast path: for number arguments each gives exactly what its entry in
     * [all] gives, or NaN, and NaN in always gives NaN out (see Ev.fast).
     * min and max of several numbers the compiler does itself.
     */
    val numeric1: Map<String, Num1> =
        mapOf(
            "floor" to Num1 { floor(it) },
            "ceil" to Num1 { Math.ceil(it) },
            "abs" to Num1 { Math.abs(it) },
            "sqrt" to Num1 { Math.sqrt(it) },
            "sin" to Num1 { Math.sin(it) },
            "cos" to Num1 { Math.cos(it) },
            "tan" to Num1 { Math.tan(it) },
            "rad" to Num1 { Math.toRadians(it) },
            "deg" to Num1 { Math.toDegrees(it) },
            "sign" to Num1 { Math.signum(it) },
            // NaN can't round: the general path reports it.
            "round" to Num1 { if (it.isNaN()) Double.NaN else it.roundToLong().toDouble() },
        )

    val numeric2: Map<String, Num2> =
        mapOf(
            // pow(NaN, 0) is 1.
            "pow" to Num2 { x, y -> if (x.isNaN() || y.isNaN()) Double.NaN else Math.pow(x, y) },
            "atan2" to Num2 { x, y -> Math.atan2(x, y) },
            "round" to
                Num2 { x, d ->
                    val p = Math.pow(10.0, d.toInt().toDouble())
                    val v = x * p
                    if (v.isNaN() || d.isNaN()) Double.NaN else v.roundToLong() / p
                },
        )

    val numeric3: Map<String, Num3> =
        mapOf(
            "clamp" to Num3 { x, lo, hi -> if (x.isNaN() || lo.isNaN() || hi.isNaN()) Double.NaN else x.coerceIn(lo, hi.coerceAtLeast(lo)) },
            "lerp" to Num3 { x, y, t -> x + (y - x) * t },
        )

    val all: Map<String, Builtin> =
        mapOf(
            // numbers
            "min" to Builtin(1, 16) { a -> if (a.size == 1) a.list(0).mapNotNull { it.num() }.minOrNull()?.let(::n) ?: Null else n(a.args.minOf { it.num() ?: Double.MAX_VALUE }) },
            "max" to Builtin(1, 16) { a -> if (a.size == 1) a.list(0).mapNotNull { it.num() }.maxOrNull()?.let(::n) ?: Null else n(a.args.maxOf { it.num() ?: -Double.MAX_VALUE }) },
            "clamp" to Builtin(3, 3) { a -> n(a.num(0).coerceIn(a.num(1), a.num(2).coerceAtLeast(a.num(1)))) },
            "lerp" to Builtin(3, 3) { a -> n(a.num(0) + (a.num(1) - a.num(0)) * a.num(2)) },
            "round" to
                Builtin(1, 2) { a ->
                    val d = a.int(1, 0)
                    val p = Math.pow(10.0, d.toDouble())
                    n((a.num(0) * p).roundToLong() / p)
                }.strict(),
            "floor" to math(::floor),
            "ceil" to math(Math::ceil),
            "abs" to math(Math::abs),
            "sqrt" to math(Math::sqrt),
            "sin" to math(Math::sin),
            "cos" to math(Math::cos),
            "tan" to math(Math::tan),
            "rad" to math(Math::toRadians),
            "deg" to math(Math::toDegrees),
            "sign" to math(Math::signum),
            "pow" to Builtin(2, 2) { a -> n(Math.pow(a.num(0), a.num(1))) }.strict(),
            "atan2" to Builtin(2, 2) { a -> n(Math.atan2(a.num(0), a.num(1))) },
            "num" to Builtin(1, 2) { a -> a[0].num()?.let(::n) ?: if (a.size > 1) a[1] else Null },
            "int" to Builtin(1, 1) { a -> a[0].num()?.let { n(it.toLong().toDouble()) } ?: Null }.strict(),
            // text
            "str" to Builtin(1, 1) { a -> Str(a.str(0)) },
            "len" to
                Builtin(1, 1) { a ->
                    n(
                        when (val v = a[0]) {
                            is Lst -> v.items.size
                            is Obj -> v.fields.size
                            is Str -> v.v.length
                            else -> 0
                        }.toDouble(),
                    )
                },
            "trim" to Builtin(1, 1) { a -> Str(a.str(0).trim()) },
            "upper" to Builtin(1, 1) { a -> Str(a.str(0).uppercase()) },
            "lower" to Builtin(1, 1) { a -> Str(a.str(0).lowercase()) },
            "lines" to Builtin(1, 1) { a -> lst(a.str(0).lines().filter { it.isNotBlank() }.map(::Str)) },
            "words" to Builtin(1, 1) { a -> lst(a.str(0).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map(::Str)) },
            "split" to Builtin(2, 2) { a -> lst(a.str(0).split(a.str(1)).map(::Str)) },
            "join" to Builtin(1, 2) { a -> Str(a.list(0).joinToString(if (a.size > 1) a.str(1) else "") { if (it is Str) it.v else it.toString() }) },
            "replace" to Builtin(3, 3) { a -> Str(a.str(0).replace(a.str(1), a.str(2))) },
            // owl 6: a value as one shell word, for commands put together while the widget runs.
            "sh" to Builtin(1, 1) { a -> Str(shQuote(a[0].let { if (it == Null) "" else it.text() })) },
            // owl 8: a value as one URL component, for http(...) URLs put together while the widget runs.
            "enc" to Builtin(1, 1) { a -> Str(URLEncoder.encode(a[0].let { if (it == Null) "" else it.text() }, "UTF-8").replace("+", "%20")) },
            "startsWith" to Builtin(2, 2) { a -> bool(a.str(0).startsWith(a.str(1))) },
            "endsWith" to Builtin(2, 2) { a -> bool(a.str(0).endsWith(a.str(1))) },
            "contains" to
                Builtin(2, 2) { a ->
                    bool(
                        when (val v = a[0]) {
                            is Lst -> a[1] in v.items
                            is Obj -> a.str(1) in v.fields
                            else -> a.str(0).contains(a.str(1))
                        },
                    )
                },
            "sub" to
                Builtin(2, 3) { a ->
                    val s = a.str(0)
                    val from = a.int(1, 0).let { if (it < 0) s.length + it else it }.coerceIn(0, s.length)
                    val to = a.int(2, s.length).let { if (it < 0) s.length + it else it }.coerceIn(from, s.length)
                    Str(s.substring(from, to))
                },
            // pad(s, n): left-pad to n characters; a negative n pads on the right.
            "pad" to
                Builtin(2, 3) { a ->
                    // Capped: a width like a timestamp would allocate gigabytes.
                    val w = a.int(1, 0).coerceIn(-MAX_LIST, MAX_LIST)
                    val c = a.str(2).firstOrNull() ?: ' '
                    Str(if (w >= 0) a.str(0).padStart(w, c) else a.str(0).padEnd(-w, c))
                }.strict(),
            "fixed" to Builtin(2, 2) { a -> Str(fixed(a.num(0), a.int(1, 0))) }.strict(),
            "fmtBytes" to
                Builtin(1, 1) { a ->
                    var v = a.num(0)
                    var unit = 0
                    val units = listOf("B", "K", "M", "G", "T", "P")
                    while (kotlin.math.abs(v) >= 1024 && unit < units.size - 1) {
                        v /= 1024
                        unit++
                    }
                    Str(if (unit == 0) "${v.toLong()}B" else fixed(v, if (v < 10) 1 else 0) + units[unit])
                }.strict(),
            "fmtDuration" to
                Builtin(1, 1) { a ->
                    val s = a.num(0).toLong().coerceAtLeast(0)
                    val d = s / 86400
                    val h = s % 86400 / 3600
                    val m = s % 3600 / 60
                    Str(
                        when {
                            d > 0 -> "${d}d ${h}h"
                            h > 0 -> "${h}h ${m}m"
                            m > 0 -> "${m}m ${s % 60}s"
                            else -> "${s}s"
                        },
                    )
                }.strict(),
            // fmtTime(epochSeconds, "HH:mm"): java.time patterns in the phone's time zone.
            "fmtTime" to Builtin(2, 2) { a -> Str(runCatching { fmtTime(a.num(0), a.str(1)) }.getOrElse { throw WidgetError("bad time pattern '${a.str(1)}'") }) }.strict(),
            // weatherIcon(wttrCode, night?): omarchy's weather glyph; show it with font: icon.
            "weatherIcon" to Builtin(1, 2) { a -> Str(weatherIcon(a.int(0, 0), a[1].truthy)) },
            // match(s, regex): the groups of the first match (or the whole match), null if none.
            "match" to
                Builtin(2, 2) { a ->
                    regex(a.str(1)).find(a.str(0))?.let { m -> if (m.groupValues.size > 1) lst(m.groupValues.drop(1).map(::Str)) else Str(m.value) } ?: Null
                },
            "matchAll" to Builtin(2, 2) { a -> lst(regex(a.str(1)).findAll(a.str(0)).take(MAX_LIST).map { m -> if (m.groupValues.size > 1) Lst(m.groupValues.drop(1).map(::Str)) else Str(m.value) }.toList()) },
            "parseJson" to Builtin(1, 1) { a -> Json.parseOrText(a.str(0)) },
            // lists
            "range" to
                Builtin(1, 3) { a ->
                    val (from, to) = if (a.size == 1) 0.0 to a.num(0) else a.num(0) to a.num(1)
                    val step = a.num(2, 1.0).takeIf { it != 0.0 } ?: 1.0
                    val out = ArrayList<Value>()
                    var x = from
                    while ((step > 0 && x < to || step < 0 && x > to) && out.size < MAX_LIST) {
                        out += n(x)
                        x += step
                    }
                    Lst(out)
                },
            "map" to
                Builtin(2, 2) { a ->
                    val f = a.fn(1)
                    val l = a.list(0)
                    a.tick(l.size)
                    lst(l.mapIndexed { i, v -> f.call(listOf(v, n(i.toDouble()))) })
                },
            "filter" to
                Builtin(2, 2) { a ->
                    val f = a.fn(1)
                    val l = a.list(0)
                    a.tick(l.size)
                    lst(l.filterIndexed { i, v -> f.call(listOf(v, n(i.toDouble()))).truthy })
                },
            "find" to Builtin(2, 2) { a -> a.fn(1).let { f -> a.list(0).firstOrNull { f.call(listOf(it)).truthy } ?: Null } },
            "any" to Builtin(2, 2) { a -> a.fn(1).let { f -> bool(a.list(0).any { f.call(listOf(it)).truthy }) } },
            "all" to Builtin(2, 2) { a -> a.fn(1).let { f -> bool(a.list(0).all { f.call(listOf(it)).truthy }) } },
            "count" to Builtin(1, 2) { a -> n(if (a.size > 1) a.fn(1).let { f -> a.list(0).count { f.call(listOf(it)).truthy } }.toDouble() else a.list(0).size.toDouble()) },
            "sum" to Builtin(1, 2) { a -> n(if (a.size > 1) a.fn(1).let { f -> a.list(0).sumOf { f.call(listOf(it)).num() ?: 0.0 } } else a.list(0).sumOf { it.num() ?: 0.0 }) },
            "avg" to
                Builtin(1, 2) { a ->
                    val l = a.list(0)
                    val f = if (a.size > 1) a.fn(1) else null
                    if (l.isEmpty()) Null else n(l.sumOf { (f?.call(listOf(it)) ?: it).num() ?: 0.0 } / l.size)
                },
            "sort" to Builtin(1, 1) { a -> lst(a.list(0).sortedWith(::compare)) },
            "sortBy" to
                Builtin(2, 2) { a ->
                    val f = a.fn(1)
                    val l = a.list(0)
                    a.tick(l.size * 4)
                    lst(l.map { it to f.call(listOf(it)) }.sortedWith { x, y -> compare(x.second, y.second) }.map { it.first })
                },
            "reverse" to Builtin(1, 1) { a -> lst(a.list(0).reversed()) },
            "take" to Builtin(2, 2) { a -> a.int(1, 0).let { k -> if (a[0] is Str) Str(a.str(0).take(k.coerceAtLeast(0))) else lst(a.list(0).take(k.coerceAtLeast(0))) } },
            "drop" to Builtin(2, 2) { a -> a.int(1, 0).let { k -> if (a[0] is Str) Str(a.str(0).drop(k.coerceAtLeast(0))) else lst(a.list(0).drop(k.coerceAtLeast(0))) } },
            "first" to Builtin(1, 1) { a -> a.list(0).firstOrNull() ?: Null },
            "last" to Builtin(1, 1) { a -> a.list(0).lastOrNull() ?: Null },
            "indexOf" to Builtin(2, 2) { a -> n(a.list(0).indexOf(a[1]).toDouble()) },
            "unique" to Builtin(1, 1) { a -> lst(a.list(0).distinct()) },
            "flatten" to Builtin(1, 1) { a -> lst(a.list(0).flatMap { if (it is Lst) it.items else listOf(it) }) },
            "keys" to Builtin(1, 1) { a -> (a[0] as? Obj)?.let { o -> lst(o.fields.keys.map(::Str)) } ?: Lst(emptyList()) },
            "values" to Builtin(1, 1) { a -> (a[0] as? Obj)?.let { o -> lst(o.fields.values.toList()) } ?: Lst(emptyList()) },
            "entries" to Builtin(1, 1) { a -> (a[0] as? Obj)?.let { lst(entriesOf(it)) } ?: Lst(emptyList()) },
            "get" to Builtin(2, 3) { a -> member(a[0], a.str(1)).takeIf { it != Null } ?: a[2] },
            // colors
            "rgb" to Builtin(3, 4) { a -> rgb(a.num(0), a.num(1), a.num(2), a.num(3, 1.0)) },
            "hsl" to Builtin(3, 4) { a -> hsl(a.num(0), a.num(1), a.num(2), a.num(3, 1.0)) },
            "alpha" to
                Builtin(2, 2) { a ->
                    val c = color(a[0], a.host) ?: throw WidgetError("alpha() needs a color")
                    c.copy(alpha = c.alpha * a.num(1).toFloat().coerceIn(0f, 1f))
                },
            "mix" to
                Builtin(3, 3, pure = false) { a ->
                    val x = argb(a[0], a.host) ?: throw WidgetError("mix() needs colors")
                    val y = argb(a[1], a.host) ?: throw WidgetError("mix() needs colors")
                    val t = a.num(2).coerceIn(0.0, 1.0)
                    fun ch(shift: Int) = ((x ushr shift and 0xff) * (1 - t) + (y ushr shift and 0xff) * t + 0.5).toInt() shl shift
                    Col(null, ch(24) or ch(16) or ch(8) or ch(0))
                },
            // animation specs, for `animate prop: spring()`
            "spring" to Builtin(0, 2) { a -> Obj(mapOf("type" to Str("spring"), "damping" to n(a.num(0, 0.6)), "stiffness" to n(a.num(1, 300.0)))) },
            "tween" to Builtin(0, 2) { a -> Obj(mapOf("type" to Str("tween"), "duration" to n(a.num(0, 0.3)), "easing" to Str(if (a.size > 1) a.str(1) else "ease"))) },
            "linear" to Builtin(5, 5) { a -> gradient(false, a, 4) },
            "radial" to Builtin(4, 4) { a -> gradient(true, a, 3) },
        )

    /** `a.b` on objects, `list.0` on lists, `len` on anything. Missing is null, never an error. */
    fun member(
        v: Value,
        name: String,
    ): Value =
        when (v) {
            is Obj -> v.fields[name] ?: Null
            is Lst -> name.toIntOrNull()?.let { v.items.getOrNull(if (it < 0) v.items.size + it else it) } ?: if (name == "length") Num(v.items.size.toDouble()) else Null
            is Str -> if (name == "length") Num(v.v.length.toDouble()) else Null
            else -> Null
        }

    fun index(
        v: Value,
        i: Value,
    ): Value =
        when (v) {
            is Lst -> i.num()?.toInt()?.let { v.items.getOrNull(if (it < 0) v.items.size + it else it) } ?: Null
            is Obj -> v.fields[i.toString()] ?: Null
            is Str -> i.num()?.toInt()?.let { v.v.getOrNull(if (it < 0) v.v.length + it else it)?.toString()?.let(::Str) } ?: Null
            else -> Null
        }

    fun binary(
        op: String,
        a: Value,
        b: Value,
    ): Value = operator(op)(a, b)

    /**
     * The function for a binary operator, looked up once at compile time
     * rather than by name on every evaluation. Two numbers take a fast path
     * that doesn't box through [num]'s Double?.
     */
    fun operator(op: String): (Value, Value) -> Value =
        when (op) {
            "+" -> { a, b ->
                when {
                    a is Num && b is Num -> Num(a.v + b.v)
                    a is Lst && b is Lst -> lst(a.items + b.items)
                    // Strings join as written ("- " + x keeps its space); only other values are formatted.
                    a is Str || b is Str -> Str((if (a is Str) a.v else a.text()) + (if (b is Str) b.v else b.text()))
                    else -> {
                        val x = a.num()
                        val y = b.num()
                        if (x != null && y != null) Num(x + y) else Null
                    }
                }
            }
            "-" -> arithmetic { x, y -> x - y }
            "*" -> arithmetic { x, y -> x * y }
            "/" -> arithmetic { x, y -> if (y == 0.0) 0.0 else x / y }
            "%" -> arithmetic { x, y -> if (y == 0.0) 0.0 else ((x % y) + y) % y }
            "==" -> { a, b -> bool(equal(a, b)) }
            "!=" -> { a, b -> bool(!equal(a, b)) }
            "<" -> comparison { it < 0 }
            "<=" -> comparison { it <= 0 }
            ">" -> comparison { it > 0 }
            ">=" -> comparison { it >= 0 }
            else -> throw WidgetError("unknown operator $op")
        }

    private inline fun arithmetic(crossinline f: (Double, Double) -> Double): (Value, Value) -> Value =
        { a, b ->
            if (a is Num && b is Num) {
                Num(f(a.v, b.v))
            } else {
                val x = a.num()
                val y = b.num()
                if (x == null || y == null) Null else Num(f(x, y))
            }
        }

    private inline fun comparison(crossinline f: (Int) -> Boolean): (Value, Value) -> Value =
        { a, b ->
            when {
                a is Num && b is Num -> bool(f(a.v.compareTo(b.v)))
                a == Null || b == Null -> FALSE
                else -> bool(f(compare(a, b)))
            }
        }

    /** "5" == 5 is true: command output is text, and widgets compare it with numbers all the time. */
    private fun equal(
        a: Value,
        b: Value,
    ): Boolean {
        if (a == b) return true
        if (a is Num && b is Str || a is Str && b is Num) return a.num() != null && a.num() == b.num()
        return false
    }
}

/** [s] in single quotes, each `'` in it closed, escaped and reopened: bash reads it back as one word, exactly [s]. */
fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
