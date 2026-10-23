package dev.omalauncher.widgets.lang

/** A compile error at a source offset; [Source.format] turns it into "line:col" plus the offending line. */
class CompileError(
    message: String,
    val pos: Int,
) : RuntimeException(message)

class Source(
    val text: String,
) {
    fun lineCol(pos: Int): Pair<Int, Int> {
        var line = 1
        var col = 1
        for (i in 0 until pos.coerceAtMost(text.length)) {
            if (text[i] == '\n') {
                line++
                col = 1
            } else {
                col++
            }
        }
        return line to col
    }

    fun format(e: CompileError): String {
        val (line, col) = lineCol(e.pos)
        val src = text.lines().getOrElse(line - 1) { "" }
        return "$line:$col: ${e.message}\n  ${src.trimEnd()}\n  ${" ".repeat((col - 1).coerceAtLeast(0))}^"
    }
}

enum class T { Ident, Num, Str, Punct, Eof }

sealed interface StrPart {
    data class Lit(
        val text: String,
    ) : StrPart

    /** `${...}`: the code between the braces, at [start] in the file. */
    data class Code(
        val start: Int,
        val end: Int,
    ) : StrPart
}

class Token(
    val type: T,
    val text: String,
    val pos: Int,
    /** True if a line break separates this token from the previous one. */
    val nl: Boolean,
    val num: Double = 0.0,
    /** Numbers written as `3x2` (grid sizes). */
    val pair: Pair<Double, Double>? = null,
    val parts: List<StrPart> = emptyList(),
) {
    fun isP(p: String) = type == T.Punct && text == p

    fun isId(w: String) = type == T.Ident && text == w

    override fun toString() =
        when (type) {
            T.Eof -> "end of file"
            T.Str -> "string"
            else -> "'$text'"
        }
}

/** Splits [src] between [start] and [end] into tokens. Strings keep their `${}` parts for the parser. */
class Lexer(
    private val src: String,
    private var i: Int = 0,
    private val end: Int = src.length,
) {
    private val out = ArrayList<Token>()
    private var nl = false

    fun run(): List<Token> {
        while (true) {
            skipSpace()
            if (i >= end) {
                out += Token(T.Eof, "", end, true)
                return out
            }
            val start = i
            val c = src[i]
            when {
                c.isLetter() || c == '_' -> {
                    while (i < end && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    add(Token(T.Ident, src.substring(start, i), start, nl))
                }
                c in '0'..'9' -> number()
                c == '#' -> {
                    // #7aa2f7: a color literal.
                    i++
                    while (i < end && src[i].isLetterOrDigit()) i++
                    if (parseHex(src.substring(start, i)) == null) throw CompileError("bad color '${src.substring(start, i)}'", start)
                    add(Token(T.Num, src.substring(start, i), start, nl))
                }
                c == '"' && src.startsWith("\"\"\"", i) -> rawString()
                c == '"' -> string()
                c == '\'' -> singleQuoted()
                else -> {
                    val two = if (i + 1 < end) src.substring(i, i + 2) else ""
                    val p = if (two in TWO) two else c.toString()
                    if (p.length == 1 && c !in ONE) throw CompileError("unexpected character '$c'", i)
                    i += p.length
                    add(Token(T.Punct, p, start, nl))
                }
            }
        }
    }

    private fun add(t: Token) {
        out += t
        nl = false
    }

    private fun skipSpace() {
        while (i < end) {
            val c = src[i]
            when {
                c == '\n' -> {
                    nl = true
                    i++
                }
                c.isWhitespace() -> i++
                src.startsWith("//", i) -> while (i < end && src[i] != '\n') i++
                src.startsWith("/*", i) -> {
                    val close = src.indexOf("*/", i + 2)
                    if (close < 0 || close >= end) throw CompileError("unterminated comment", i)
                    if (src.substring(i, close).contains('\n')) nl = true
                    i = close + 2
                }
                else -> return
            }
        }
    }

    private fun digits(): Double {
        val start = i
        // ASCII only: isDigit() takes other scripts' digits too, which toDouble() can't read.
        while (i < end && (src[i] in '0'..'9' || src[i] == '_')) i++
        if (i < end && src[i] == '.' && src.getOrNull(i + 1)?.let { it in '0'..'9' } == true) {
            i++
            while (i < end && src[i] in '0'..'9') i++
        }
        return src.substring(start, i).replace("_", "").toDouble()
    }

    private fun number() {
        val start = i
        if (src.startsWith("0x", i)) {
            i += 2
            val s = i
            while (i < end && src[i].isLetterOrDigit()) i++
            val v = src.substring(s, i).toLongOrNull(16) ?: throw CompileError("bad hex number", start)
            add(Token(T.Num, src.substring(start, i), start, nl, v.toDouble()))
            return
        }
        var v = digits()
        // 3x2: a size in grid cells.
        if (i + 1 < end && src[i] == 'x' && src[i + 1] in '0'..'9') {
            i++
            val h = digits()
            add(Token(T.Num, src.substring(start, i), start, nl, v, pair = v to h))
            return
        }
        // Durations become seconds: 500ms, 2s, 5m, 1h. Angles: 90deg stays degrees.
        val unitStart = i
        while (i < end && src[i].isLetter()) i++
        when (val unit = src.substring(unitStart, i)) {
            "" -> {}
            "ms" -> v /= 1000
            "s", "deg", "dp", "px" -> {}
            "m", "min" -> v *= 60
            "h" -> v *= 3600
            else -> {
                // 1e9: habit from other languages; say what to write instead.
                val m = Regex("""[eE]([+-]?\d+)""").matchAt(src, unitStart)
                val exp = m?.groupValues?.get(1)?.toIntOrNull()
                if (exp != null && exp in -12..15) {
                    val spelled = java.math.BigDecimal(src.substring(start, unitStart)).scaleByPowerOfTen(exp).stripTrailingZeros().toPlainString()
                    throw CompileError("unknown unit '$unit': numbers have no exponents, so write ${src.substring(start, m.range.last + 1)} as $spelled", unitStart)
                }
                throw CompileError("unknown unit '$unit'", unitStart)
            }
        }
        add(Token(T.Num, src.substring(start, i), start, nl, v))
    }

    private fun escape(out: StringBuilder) {
        val e = src.getOrNull(i + 1) ?: throw CompileError("unterminated string", i)
        out.append(
            when (e) {
                'n' -> '\n'
                't' -> '\t'
                'r' -> '\r'
                '0' -> '\u0000'
                'e' -> '\u001b'
                else -> e
            },
        )
        i += 2
    }

    private fun string() {
        val start = i
        i++
        val parts = ArrayList<StrPart>()
        val buf = StringBuilder()
        while (true) {
            if (i >= end || src[i] == '\n') throw CompileError("unterminated string", start)
            val c = src[i]
            when {
                c == '"' -> {
                    i++
                    break
                }
                c == '\\' -> escape(buf)
                c == '$' && src.getOrNull(i + 1) == '{' -> {
                    if (buf.isNotEmpty()) parts += StrPart.Lit(buf.toString()).also { buf.clear() }
                    val codeStart = i + 2
                    i = matchBrace(codeStart)
                    parts += StrPart.Code(codeStart, i)
                    i++ // closing brace
                }
                else -> {
                    buf.append(c)
                    i++
                }
            }
        }
        if (buf.isNotEmpty() || parts.isEmpty()) parts += StrPart.Lit(buf.toString())
        add(Token(T.Str, src.substring(start, i), start, nl, parts = parts))
    }

    /** Index of the `}` closing a `${`, skipping nested braces and strings. */
    private fun matchBrace(from: Int): Int {
        var depth = 0
        var j = from
        while (j < end) {
            when (src[j]) {
                '{' -> depth++
                '}' -> if (depth == 0) return j else depth--
                '"', '\'' -> {
                    val q = src[j]
                    j++
                    while (j < end && src[j] != q) {
                        if (src[j] == '\\') j++
                        j++
                    }
                }
                '\n' -> throw CompileError("unterminated \${", from - 2)
            }
            j++
        }
        throw CompileError("unterminated \${", from - 2)
    }

    /** 'text': no interpolation and may span lines, handy for shell commands and scripts full of $ and ". */
    private fun singleQuoted() {
        val start = i
        i++
        val buf = StringBuilder()
        while (true) {
            if (i >= end) throw CompileError("unterminated string", start)
            val c = src[i]
            when {
                c == '\'' -> {
                    i++
                    break
                }
                c == '\\' && src.getOrNull(i + 1).let { it == '\'' || it == '\\' } -> {
                    buf.append(src[i + 1])
                    i += 2
                }
                else -> {
                    buf.append(c)
                    i++
                }
            }
        }
        add(Token(T.Str, src.substring(start, i), start, nl, parts = listOf(StrPart.Lit(buf.toString()))))
    }

    /** """multi-line text""" taken literally, for shaders and long scripts. */
    private fun rawString() {
        val start = i
        val close = src.indexOf("\"\"\"", i + 3)
        if (close < 0 || close >= end) throw CompileError("unterminated \"\"\" string", start)
        val body = src.substring(i + 3, close).trimIndent().removePrefix("\n")
        i = close + 3
        add(Token(T.Str, src.substring(start, i), start, nl, parts = listOf(StrPart.Lit(body))))
    }

    companion object {
        val TWO = setOf("==", "!=", "<=", ">=", "&&", "||", "??", "=>")
        private const val ONE = "{}()[],;:.?=<>+-*/%!"
    }
}
