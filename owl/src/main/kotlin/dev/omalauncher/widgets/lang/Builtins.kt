package dev.omalauncher.widgets.lang

import java.net.URLEncoder
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.time.temporal.TemporalQueries
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.random.Random

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
    val actions = mapOf("terminal" to 1..2, "app" to 1..1, "run" to 1..1, "post" to 1..3, "url" to 1..1, "launcher" to 1..1, "refresh" to 0..1, "media" to 1..1, "androidApp" to 1..1, "copy" to 1..1, "notify" to 1..2, "volume" to 1..1, "notification" to 2..2, "screen" to 0..0, "close" to 0..0, "torch" to 1..1, "share" to 1..1, "haptic" to 0..0, "sound" to 1..1, "open" to 1..1, "ask" to 1..2, "appAction" to 2..2, "workApps" to 1..1, "shortcuts" to 1..1, "pressKey" to 1..1, "typeText" to 1..1, "latch" to 1..2)

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
        // + 0.0 makes -0.0 zero: Double.compareTo orders it below 0, so -(a - b) < 0 held for a == b.
        return if (x != null && y != null) (x + 0.0).compareTo(y + 0.0) else a.toString().compareTo(b.toString())
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

    private val WHITESPACE = Regex("\\s+")

    /** Patterns built from changing values would fill the cache without end. */
    private fun regex(p: String) =
        synchronized(regexCache) {
            if (regexCache.size >= 256) regexCache.clear()
            regexCache.getOrPut(p) { Regex(p) }
        }

    /**
     * Where match() and matchAll() run. A pattern like `(.*a){20}b` backtracks for minutes on 25 characters, and a
     * let runs while the screen is built, on the main thread: home froze. Android's regex engine (ICU, native) can't be
     * interrupted or given a deadline, so a match runs on one of these threads and the caller waits [REGEX_MS] at most;
     * one that runs over goes on, at the lowest priority, until it ends.
     */
    private val regexThreads =
        Executors.newCachedThreadPool { r ->
            Thread(r, "owl-regex").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

    private const val REGEX_MS = 500L

    /** Patterns that ran over [REGEX_MS]: refused from then on, so each leaves at most one thread behind. */
    private val slowPatterns = Collections.synchronizedSet(HashSet<String>())

    private fun <T> matching(
        p: String,
        block: (Regex) -> T,
    ): T {
        val slow = "the pattern \"$p\" takes too long: a repeated group with a repeat inside, like (a+)+ or (.*x){5}, or .* " +
            "several times over long text, tries ever more ways to match; it's off until the launcher restarts"
        if (p in slowPatterns) throw WidgetError(slow)
        val r = regex(p)
        val task = regexThreads.submit(Callable { block(r) })
        try {
            return task.get(REGEX_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            slowPatterns.add(p)
            throw WidgetError(slow)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** By pattern, and by language tag after a NUL when a widget names one. */
    private val timeFormats = HashMap<String, DateTimeFormatter>()

    /** The language [timeFormats] name days and months in: the phone's can change while widgets run. */
    private var timeFormatsLocale: Locale? = null

    private fun timeFormat(
        pattern: String,
        language: String,
    ): DateTimeFormatter {
        val default = Locale.getDefault()
        return synchronized(timeFormats) {
            if (default != timeFormatsLocale) {
                timeFormats.clear()
                timeFormatsLocale = default
            }
            timeFormats.getOrPut(if (language == "") pattern else "$pattern\u0000$language") {
                val locale = if (language == "") default else Locale.forLanguageTag(language)
                DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(locale)
            }
        }
    }

    /** A time zone by name ("Europe/Berlin", "UTC") or offset ("+02:00"); null or "" is the phone's. */
    private fun zone(v: Value): ZoneId =
        if (v == Null || v.toString() == "") {
            ZoneId.systemDefault()
        } else {
            runCatching { ZoneId.of(v.toString()) }.getOrElse { throw WidgetError("unknown time zone '$v'; use a name like \"Europe/Berlin\" or an offset like \"+02:00\"") }
        }

    private fun fmtTime(
        seconds: Double,
        pattern: String,
        zone: ZoneId,
        language: String,
    ): String {
        val at = Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(zone)
        return runCatching { timeFormat(pattern, language).format(at) }.getOrElse { throw WidgetError("bad time pattern '$pattern'") }
    }

    /** 2026-10-17, 2026-10-17T10:04, …:05.123, with Z, +02, +0200 or +02:00, or a space instead of the T. */
    private val isoTime = Regex("(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,9}))?)?)? ?(Z|[+-]\\d{2}(?::?\\d{2})?)?", RegexOption.IGNORE_CASE)

    /** iCalendar's basic form: 20261017, 20261017T1004, 20261017T100405Z. */
    private val basicTime = Regex("(\\d{4})(\\d{2})(\\d{2})(?:T(\\d{2})(\\d{2})(\\d{2})?)?(Z)?", RegexOption.IGNORE_CASE)

    /** The zone names RFC 822 dates (RSS feeds) use; RFC_1123_DATE_TIME only reads GMT and offsets. */
    private val rfc822Zones = mapOf("UT" to "+0000", "UTC" to "+0000", "Z" to "+0000", "EST" to "-0500", "EDT" to "-0400", "CST" to "-0600", "CDT" to "-0500", "MST" to "-0700", "MDT" to "-0600", "PST" to "-0800", "PDT" to "-0700")

    /**
     * Unix seconds for a date in text: ISO 8601, iCalendar's basic form or RFC 1123 (`Sat, 17 Oct 2026
     * 10:04:00 GMT`), or what a java.time [pattern] describes, in English. A date without a zone is a time in [zone];
     * a pattern without a date is today there, and one without a time is midnight. Null when it isn't one.
     */
    private fun parseTime(
        text: String,
        pattern: String,
        zone: ZoneId,
    ): Value {
        val s = text.trim()
        if (pattern != "") {
            // Dates come from APIs and feeds, which name months and days in English whatever the phone's language.
            val f = runCatching { timeFormat(pattern, "en") }.getOrElse { throw WidgetError("bad time pattern '$pattern'") }
            val t = runCatching { f.parse(s) }.getOrNull() ?: return Null
            val today = LocalDate.now(zone)
            val date =
                t.query(TemporalQueries.localDate())
                    ?: if (t.isSupported(ChronoField.MONTH_OF_YEAR) && t.isSupported(ChronoField.DAY_OF_MONTH)) {
                        runCatching { today.withMonth(t.get(ChronoField.MONTH_OF_YEAR)).withDayOfMonth(t.get(ChronoField.DAY_OF_MONTH)) }.getOrNull() ?: return Null
                    } else {
                        today
                    }
            val time = t.query(TemporalQueries.localTime()) ?: LocalTime.MIDNIGHT
            return epoch(ZonedDateTime.of(date, time, t.query(TemporalQueries.zone()) ?: zone))
        }
        val iso = isoTime.matchEntire(s)
        val m = iso ?: basicTime.matchEntire(s)
        if (m != null) {
            val g = m.groupValues
            fun part(i: Int) = g[i].toIntOrNull() ?: 0
            val nanos = if (iso != null && g[7] != "") g[7].padEnd(9, '0').toInt() else 0
            val offset = g.last().uppercase()
            val at = runCatching { LocalDateTime.of(part(1), part(2), part(3), part(4), part(5), part(6), nanos) }.getOrNull() ?: return Null
            // ZoneOffset reads Z, +02, +0200 and +02:00.
            // An offset past ±18:00 (+99:99) isn't one: null, like any text that isn't a date, rather than an error.
            return epoch(at.atZone(if (offset == "") zone else runCatching { ZoneOffset.of(offset) }.getOrNull() ?: return Null))
        }
        val named = s.substringAfterLast(' ')
        val rfc = rfc822Zones[named.uppercase()]?.let { s.dropLast(named.length) + it } ?: s
        return runCatching { epoch(ZonedDateTime.parse(rfc, DateTimeFormatter.RFC_1123_DATE_TIME)) }.getOrDefault(Null)
    }

    private fun epoch(t: ZonedDateTime) = n(t.toEpochSecond() + t.nano / 1e9)

    /** How long ago [seconds] were, short: now, 5m, 3h, 2d, 6w, 1y; a time still to come is "in 5m". */
    private fun fmtAgo(seconds: Double): String {
        val s = kotlin.math.abs(seconds)
        val short =
            when {
                s < 60 -> return "now"
                s < 3600 -> "${(s / 60).toLong()}m"
                s < 86400 -> "${(s / 3600).toLong()}h"
                s < 7 * 86400 -> "${(s / 86400).toLong()}d"
                s < 365 * 86400 -> "${(s / (7 * 86400)).toLong()}w"
                else -> "${(s / (365 * 86400)).toLong()}y"
            }
        return if (seconds < 0) "in $short" else short
    }

    private val compactUnits = listOf("", "k", "M", "B", "T")

    /**
     * [x] in the phone's language, its thousands grouped, with [digits] after the point. Compact
     * divides by thousands into k, M, B and T, with one digit under 10 and none above unless [digits]
     * says otherwise (a trailing .0 then goes); [sign] puts + before a number above zero.
     */
    private fun fmtNum(
        x: Double,
        digits: Int?,
        compact: Boolean,
        sign: Boolean,
    ): String {
        if (x.isNaN() || x.isInfinite()) return Num(x).toString()
        val locale = Locale.getDefault()
        fun digitsFor(v: Double) = digits?.coerceIn(0, 10) ?: if (compact && kotlin.math.abs(v) < 10) 1 else 0

        fun shown(
            v: Double,
            d: Int,
        ) = Math.round(kotlin.math.abs(v) * Math.pow(10.0, d.toDouble())) / Math.pow(10.0, d.toDouble())
        var v = x
        var unit = 0
        var d = digitsFor(v)
        // 999.97 shows as 1000 at one digit: that's 1k.
        while (compact && unit < compactUnits.size - 1 && shown(v, d) >= 1000) {
            v /= 1000
            unit++
            d = digitsFor(v)
        }
        // No "-0" for a number that rounds to zero.
        if (kotlin.math.abs(v) < 0.5 / Math.pow(10.0, d.toDouble())) v = 0.0
        var s = String.format(locale, if (compact) "%.${d}f" else "%,.${d}f", v)
        if (compact && digits == null && d > 0) {
            // The locale's zero: Arabic writes ١٫٠.
            val symbols = DecimalFormatSymbols.getInstance(locale)
            s = s.trimEnd(symbols.zeroDigit).trimEnd(symbols.decimalSeparator)
        }
        return (if (sign && v > 0) "+" else "") + s + compactUnits[unit]
    }

    /** FNV-1a over the text of [values], mixed to spread nearby inputs apart (murmur3's finish), in [0, 1). */
    private fun hash(values: List<Value>): Double {
        var h = 0x811c9dc5.toInt()
        values.forEachIndexed { i, v ->
            // A NUL between values: hash("ab", "c") isn't hash("a", "bc").
            if (i > 0) h *= 0x01000193
            for (c in v.toString()) h = (h xor c.code) * 0x01000193
        }
        h = h xor (h ushr 16)
        h *= 0x85ebca6b.toInt()
        h = h xor (h ushr 13)
        h *= 0xc2b2ae35.toInt()
        h = h xor (h ushr 16)
        return (h.toLong() and 0xffffffffL) / 4294967296.0
    }

    private fun flatten(
        items: List<Value>,
        depth: Int,
    ): List<Value> = if (depth <= 0) items else items.flatMap { if (it is Lst) flatten(it.items, depth - 1) else listOf(it) }

    /**
     * [v] with [key] set to [to] (or removed, for null): a list by index, from the end when it's
     * negative, or a map by name. A list index that isn't in it changes nothing; null is an empty map.
     */
    private fun edited(
        v: Value,
        key: Value,
        to: Value?,
    ): Value =
        when (v) {
            is Lst -> {
                val i = key.num()?.toInt()?.let { if (it < 0) v.items.size + it else it }
                if (i == null || i !in v.items.indices) {
                    v
                } else if (to == null) {
                    Lst(v.items.filterIndexed { j, _ -> j != i })
                } else {
                    Lst(v.items.toMutableList().also { it[i] = to })
                }
            }
            is Obj -> Obj(LinkedHashMap(v.fields).also { if (to == null) it.remove(key.toString()) else it[key.toString()] = to })
            Null -> if (to == null) Null else Obj(mapOf(key.toString() to to))
            else -> v
        }

    /** Fisher–Yates; [next] gives numbers in [0, 1). */
    private fun shuffle(
        items: List<Value>,
        next: () -> Double,
    ): List<Value> {
        val out = items.toMutableList()
        for (i in out.size - 1 downTo 1) {
            val j = (next() * (i + 1)).toInt()
            out[i] = out[j].also { out[j] = out[i] }
        }
        return out
    }

    /** mulberry32, seeded from [seed]'s [hash]: the same seed shuffles the same way, here and in the Quickshell runtime. */
    private fun seeded(seed: Value): () -> Double {
        var state = (hash(listOf(seed)) * 4294967296.0).toLong().toInt()
        return {
            state += 0x6d2b79f5
            var t = state
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + (t xor (t ushr 7)) * (t or 61))
            ((t xor (t ushr 14)).toLong() and 0xffffffffL) / 4294967296.0
        }
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

    // From 2^52 up a double has no fraction left to round, and roundToLong would
    // stop at Long.MAX_VALUE: round(999998000001, 8) gave 92233720368.54776.
    // NaN, also from 0 scaled by an infinite 10^digits, stays NaN.
    private fun roundTo(
        x: Double,
        digits: Int,
    ): Double {
        val p = Math.pow(10.0, digits.toDouble())
        val v = x * p
        return if (v.isNaN()) Double.NaN else if (Math.abs(v) >= 4503599627370496.0) x else v.roundToLong() / p
    }

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
            "asin" to Num1 { Math.asin(it) },
            "acos" to Num1 { Math.acos(it) },
            // NaN can't round: the general path reports it.
            "round" to Num1 { roundTo(it, 0) },
        )

    val numeric2: Map<String, Num2> =
        mapOf(
            // pow(NaN, 0) is 1.
            "pow" to Num2 { x, y -> if (x.isNaN() || y.isNaN()) Double.NaN else Math.pow(x, y) },
            "atan2" to Num2 { x, y -> Math.atan2(x, y) },
            "round" to
                Num2 { x, d -> if (x.isNaN() || d.isNaN()) Double.NaN else roundTo(x, d.toInt()) },
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
                Builtin(1, 2) { a -> n(roundTo(a.num(0), a.int(1, 0)).also { require(!it.isNaN()) { "Cannot round NaN value." } }) }.strict(),
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
            // owl 9
            "asin" to math(Math::asin),
            "acos" to math(Math::acos),
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
            // owl 9: the first letter in capitals, the rest as it is.
            "capitalize" to Builtin(1, 1) { a -> Str(a.str(0).replaceFirstChar { it.titlecase() }) }.strict(),
            "lower" to Builtin(1, 1) { a -> Str(a.str(0).lowercase()) },
            "lines" to Builtin(1, 1) { a -> lst(a.str(0).lines().filter { it.isNotBlank() }.map(::Str)) },
            "words" to Builtin(1, 1) { a -> lst(a.str(0).trim().split(WHITESPACE).filter { it.isNotEmpty() }.map(::Str)) },
            "split" to Builtin(2, 2) { a -> lst(a.str(0).split(a.str(1)).map(::Str)) },
            "join" to
                Builtin(1, 2) { a ->
                    val sep = if (a.size > 1) a.str(1) else ""
                    val out = StringBuilder()
                    a.list(0).forEachIndexed { i, x ->
                        if (i > 0) out.append(sep)
                        out.append(if (x is Str) x.v else x.toString())
                        checkText(out.length)
                    }
                    Str(out.toString())
                },
            "replace" to
                Builtin(3, 3) { a ->
                    val s = a.str(0)
                    val from = a.str(1)
                    val to = a.str(2)
                    // "a" for every character of a long text multiplies it.
                    if (to.length > from.length && from.isNotEmpty()) checkText(s.length / from.length * to.length + s.length)
                    Str(s.replace(from, to))
                },
            // owl 6: a value as one shell word, for commands put together while the widget runs.
            "sh" to Builtin(1, 1) { a -> Str(shQuote(a[0].let { if (it == Null) "" else it.text() })) },
            // owl 8: a value as one URL component, for http(...) URLs put together while the widget runs.
            "enc" to Builtin(1, 1) { a -> Str(urlEncode(a[0].let { if (it == Null) "" else it.text() })) },
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
            // fmtTime(epochSeconds, "HH:mm"): java.time patterns in the phone's time zone; owl 9 takes a zone and a language too.
            // NaN and infinity aren't times: null, as for a value still loading, rather than 1970's midnight.
            "fmtTime" to Builtin(2, 4) { a -> if (!a.num(0).isFinite()) Null else Str(fmtTime(a.num(0), a.str(1), zone(a[2]), a.str(3))) }.strict(),
            // owl 9: parseTime("2026-10-17T10:04:00Z") is Unix seconds, null if the text isn't a date.
            // Not folded: a time without a date is today's, and one without a zone the phone's.
            "parseTime" to Builtin(1, 3, pure = false) { a -> parseTime(a.str(0), a.str(1), zone(a[2])) }.strict(),
            // owl 9: fmtAgo(t, now) is "5m", "3h", "2d"; now comes from a clock source, so it stays current.
            "fmtAgo" to Builtin(2, 2) { a -> if (a[1] == Null) Null else Str(fmtAgo(a.num(1) - a.num(0))) }.strict(),
            // owl 9: fmtNum(x, digits) or fmtNum(x, {digits, compact, sign}). Not folded: it's in the phone's language.
            "fmtNum" to
                Builtin(1, 2, pure = false) { a ->
                    // NaN and infinity showed as 0.
                    if (!a.num(0).isFinite()) return@Builtin Null
                    when (val o = a[1]) {
                        is Obj -> Str(fmtNum(a.num(0), o.fields["digits"]?.num()?.toInt(), o.fields["compact"]?.truthy == true, o.fields["sign"]?.truthy == true))
                        Null -> Str(fmtNum(a.num(0), null, false, false))
                        else -> Str(fmtNum(a.num(0), o.num()?.toInt() ?: throw WidgetError("fmtNum takes a number of digits or a map like {compact: true}"), false, false))
                    }
                }.strict(),
            // weatherIcon(wttrCode, night?): omarchy's weather glyph; show it with font: icon.
            "weatherIcon" to Builtin(1, 2) { a -> Str(weatherIcon(a.int(0, 0), a[1].truthy)) },
            // match(s, regex): the groups of the first match (or the whole match), null if none.
            "match" to
                Builtin(2, 2) { a ->
                    val text = a.str(0)
                    matching(a.str(1)) { r -> r.find(text)?.let { m -> if (m.groupValues.size > 1) lst(m.groupValues.drop(1).map(::Str)) else Str(m.value) } } ?: Null
                },
            "matchAll" to
                Builtin(2, 2) { a ->
                    val text = a.str(0)
                    lst(matching(a.str(1)) { r -> r.findAll(text).take(MAX_LIST).map { m -> if (m.groupValues.size > 1) Lst(m.groupValues.drop(1).map(::Str)) else Str(m.value) }.toList() })
                },
            "parseJson" to Builtin(1, 1) { a -> Json.parseOrText(a.str(0)) },
            // Text as base64 and back; fromBase64 also reads base64url, with or without padding, and gives null for anything else.
            "toBase64" to Builtin(1, 1) { a -> Str(Base64.getEncoder().encodeToString(a.str(0).toByteArray())) },
            "fromBase64" to
                Builtin(1, 1) { a ->
                    val s = a.str(0).filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/').trimEnd('=')
                    runCatching { Str(String(Base64.getDecoder().decode(s.padEnd((s.length + 3) / 4 * 4, '=')), Charsets.UTF_8)) }.getOrDefault(Null)
                },
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
            // owl 11: chunk(list, n), the list in lists of n, the last one shorter: rows of a grid built by hand.
            "chunk" to
                Builtin(2, 2) { a ->
                    val l = a.list(0)
                    a.tick(l.size)
                    lst(l.chunked(a.int(1, 1).coerceAtLeast(1)) { Lst(it.toList()) })
                },
            // owl 11: the matches of a query best first, as the bar ranks apps: by a map's name (and alias), with its score as the boost.
            "rank" to
                Builtin(2, 2) { a ->
                    val l = a.list(0)
                    a.tick(l.size * 4)
                    val ranked =
                        rankMatches(
                            l,
                            a.str(1),
                            name = { rankName(it) },
                            boost = { ((rankField(it, "score")?.num() ?: 0.0) * 10).toInt() },
                            alias = { rankAlias(it) },
                        )
                    lst(ranked.map { it.first })
                },
            // owl 11: whether Enter in the bar would open this match rather than ask the agent; the tiers stay the launcher's.
            "strongMatch" to
                Builtin(2, 2) { a ->
                    val item = a.args[0]
                    val q = a.str(1)
                    val s = maxOf(matchScore(rankName(item), q), rankAlias(item)?.let { matchScore(it, q) } ?: 0)
                    bool(isStrongMatch(s, q))
                },
            // flatten(list): one level of lists in lists; owl 9 takes how many levels.
            "flatten" to
                Builtin(1, 2) { a ->
                    val l = a.list(0)
                    a.tick(l.size)
                    lst(flatten(l, a.int(1, 1).coerceAtMost(64)))
                },
            // owl 9: shuffle(list, seed) shuffles the same way for the same seed; shuffle(list) differently each time, so only in handlers.
            "shuffle" to
                Builtin(1, 2, pure = false) { a ->
                    val l = a.list(0)
                    a.tick(l.size)
                    lst(shuffle(l, if (a.size > 1) seeded(a[1]) else Random.Default::nextDouble))
                },
            // random() in [0, 1), random(n) a whole number from 0 to n - 1, random(a, b) one from a to b. Only in handlers and a state's first value.
            "random" to
                Builtin(0, 2, pure = false) { a ->
                    val r = Random.Default.nextDouble()
                    when (a.size) {
                        0 -> n(r)
                        1 -> n(floor(r * floor(a.num(0)).coerceAtLeast(0.0)))
                        else -> {
                            val lo = Math.ceil(minOf(a.num(0), a.num(1)))
                            val hi = floor(maxOf(a.num(0), a.num(1)))
                            n(if (hi < lo) lo else lo + floor(r * (hi - lo + 1)))
                        }
                    }
                },
            // hash(values...): a number in [0, 1) that's always the same for the same values, for noise in lets and canvases.
            "hash" to Builtin(1, 16) { a -> n(hash(a.args)) },
            "keys" to Builtin(1, 1) { a -> (a[0] as? Obj)?.let { o -> lst(o.fields.keys.map(::Str)) } ?: Lst(emptyList()) },
            "values" to Builtin(1, 1) { a -> (a[0] as? Obj)?.let { o -> lst(o.fields.values.toList()) } ?: Lst(emptyList()) },
            "entries" to Builtin(1, 1) { a -> (a[0] as? Obj)?.let { lst(entriesOf(it)) } ?: Lst(emptyList()) },
            "get" to Builtin(2, 3) { a -> member(a[0], a.str(1)).takeIf { it != Null } ?: a[2] },
            // owl 9: set(list, i, v) and set(map, key, v) are copies with that one changed; remove(...) without it.
            "set" to Builtin(3, 3) { a -> edited(a[0], a[1], a[2]) },
            "remove" to Builtin(2, 2) { a -> edited(a[0], a[1], null) },
            // owl 9: merge(a, b, ...) is the maps' fields together, a later map's winning.
            "merge" to
                Builtin(1, 16) { a ->
                    val out = LinkedHashMap<String, Value>()
                    a.args.forEach { (it as? Obj)?.let { o -> out.putAll(o.fields) } }
                    Obj(out)
                },
            // owl 9: reduce(list, (total, x, i) => ..., start) folds a list into one value; scan gives every step of it.
            "reduce" to
                Builtin(3, 3) { a ->
                    val f = a.fn(1)
                    val l = a.list(0)
                    a.tick(l.size)
                    var acc = a[2]
                    l.forEachIndexed { i, v -> acc = f.call(listOf(acc, v, n(i.toDouble()))) }
                    acc
                },
            "scan" to
                Builtin(3, 3) { a ->
                    val f = a.fn(1)
                    val l = a.list(0)
                    a.tick(l.size)
                    var acc = a[2]
                    lst(l.mapIndexed { i, v -> f.call(listOf(acc, v, n(i.toDouble()))).also { acc = it } })
                },
            // owl 9: groupBy(list, x => x.day) is a map from each key to the items with it, in the order they came.
            "groupBy" to
                Builtin(2, 2) { a ->
                    val f = a.fn(1)
                    val l = a.list(0)
                    a.tick(l.size)
                    val out = LinkedHashMap<String, ArrayList<Value>>()
                    l.forEach { v -> out.getOrPut(f.call(listOf(v)).toString()) { ArrayList() } += v }
                    Obj(out.mapValues { Lst(it.value) })
                },
            // owl 9: JSON text, for a post body built by hand, a file or a command.
            "toJson" to Builtin(1, 1) { a -> Str(Json.write(a[0], MAX_TEXT) ?: throw WidgetError("toJson takes numbers, text, lists and maps, not colors or functions")) },
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
                    a is Str || b is Str -> {
                        val x = if (a is Str) a.v else a.text()
                        val y = if (b is Str) b.v else b.text()
                        checkText(x.length + y.length)
                        Str(x + y)
                    }
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
                // Nothing compares with NaN ("NaN" is a number): compareTo puts it above everything.
                a is Num && b is Num -> if (a.v.isNaN() || b.v.isNaN()) FALSE else bool(f((a.v + 0.0).compareTo(b.v + 0.0)))
                a == Null || b == Null -> FALSE
                a.num()?.isNaN() == true || b.num()?.isNaN() == true -> FALSE
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

// rank() and strongMatch() read a map by its name and alias, as the bar reads apps; a text matches as it is.
private fun rankText(v: Value?) = v?.takeIf { it != Null }?.toString()

private fun rankField(
    v: Value,
    name: String,
) = (v as? Obj)?.fields?.get(name)

private fun rankName(v: Value) = rankText(if (v is Obj) rankField(v, "name") else v).orEmpty()

private fun rankAlias(v: Value) = rankText(rankField(v, "alias"))

/** [s] in single quotes, each `'` in it closed, escaped and reopened: bash reads it back as one word, exactly [s]. */
fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

/** [s] as one URL component, spaces as %20: what enc(...) gives. */
fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
