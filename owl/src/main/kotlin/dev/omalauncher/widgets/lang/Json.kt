package dev.omalauncher.widgets.lang

/** Minimal JSON reader straight into [Value]s; commands that print JSON are parsed once, here. */
object Json {
    fun parse(text: String): Value = Reader(text).run { value().also { skip(); if (i < s.length) fail("trailing text") } }

    /** JSON if it parses, otherwise the text itself. */
    fun parseOrText(text: String): Value = runCatching { parse(text.trim()) }.getOrElse { Str(text) }

    /** [v] as JSON, or null for what JSON can't hold (colors, functions); past [limit] characters it throws. */
    fun write(
        v: Value,
        limit: Int = Int.MAX_VALUE,
    ): String? = StringBuilder().takeIf { write(v, it, limit) }?.toString()

    private fun write(
        v: Value,
        out: StringBuilder,
        limit: Int,
    ): Boolean {
        if (out.length > limit) throw WidgetError("JSON over ${limit shr 20}M characters")
        when (v) {
            Null -> out.append("null")
            is Bool -> out.append(v.v)
            is Num -> if (v.v.isFinite()) out.append(v.toString()) else out.append("null")
            is Str -> quote(v.v, out)
            is Lst -> {
                out.append('[')
                v.items.forEachIndexed { i, x ->
                    if (i > 0) out.append(',')
                    if (!write(x, out, limit)) return false
                }
                out.append(']')
            }
            is Obj -> {
                out.append('{')
                v.fields.entries.forEachIndexed { i, (k, x) ->
                    if (i > 0) out.append(',')
                    quote(k, out)
                    out.append(':')
                    if (!write(x, out, limit)) return false
                }
                out.append('}')
            }
            else -> return false
        }
        return true
    }

    private fun quote(
        s: String,
        out: StringBuilder,
    ) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    private const val MAX_DEPTH = 512

    private class Reader(
        val s: String,
    ) {
        var i = 0
        var depth = 0

        fun fail(msg: String): Nothing = throw WidgetError("json: $msg at $i")

        fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Value {
            skip()
            if (i >= s.length) fail("unexpected end")
            // Each level recurses: thousands of [[[[ would overflow the stack.
            if (depth >= MAX_DEPTH) fail("nested too deeply")
            depth++
            return when (val c = s[i]) {
                '{' -> {
                    i++
                    val fields = LinkedHashMap<String, Value>()
                    skip()
                    if (s.getOrNull(i) == '}') {
                        i++
                    } else {
                        while (true) {
                            skip()
                            if (s.getOrNull(i) != '"') fail("expected key")
                            val key = string()
                            skip()
                            if (s.getOrNull(i++) != ':') fail("expected ':'")
                            fields[key] = value()
                            skip()
                            when (s.getOrNull(i++)) {
                                ',' -> continue
                                '}' -> break
                                else -> fail("expected ',' or '}'")
                            }
                        }
                    }
                    Obj(fields)
                }
                '[' -> {
                    i++
                    val items = ArrayList<Value>()
                    skip()
                    if (s.getOrNull(i) == ']') {
                        i++
                    } else {
                        while (true) {
                            items += value()
                            skip()
                            when (s.getOrNull(i++)) {
                                ',' -> continue
                                ']' -> break
                                else -> fail("expected ',' or ']'")
                            }
                        }
                    }
                    Lst(items)
                }
                '"' -> Str(string())
                't' -> word("true", TRUE)
                'f' -> word("false", FALSE)
                'n' -> word("null", Null)
                else -> {
                    if (c != '-' && !c.isDigit()) fail("unexpected '$c'")
                    val start = i
                    i++
                    while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
                    Num(s.substring(start, i).toDoubleOrNull() ?: fail("bad number"))
                }
            }.also { depth-- }
        }

        fun word(
            w: String,
            v: Value,
        ): Value {
            if (!s.startsWith(w, i)) fail("unexpected token")
            i += w.length
            return v
        }

        fun string(): String {
            i++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                when (val c = s[i++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        when (val e = s.getOrNull(i++)) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'u' -> {
                                out.append(s.substring(i, (i + 4).coerceAtMost(s.length)).toIntOrNull(16)?.toChar() ?: fail("bad escape"))
                                i += 4
                            }
                            null -> fail("unterminated string")
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
        }
    }
}
