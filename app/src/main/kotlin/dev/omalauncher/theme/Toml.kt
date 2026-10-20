package dev.omalauncher.theme

/**
 * The part of TOML the launcher's files need: `[section]` headers, dotted
 * bare keys, string, number and boolean values, arrays (over several lines if
 * need be), one-line inline tables and top-level `[[name]]` arrays of tables,
 * with comments. Keys come out flattened ("widgets.gaps-in"), in file order.
 * Quoted keys, multi-line strings, dates and nested `[[a.b]]` aren't anything
 * these files hold, so they're errors; that way whatever [parse] reads,
 * [write] writes back readably.
 */
object Toml {
    class Error(
        val line: Int,
        message: String,
    ) : Exception("line $line: $message")

    private val BARE_KEY = Regex("[A-Za-z0-9_-]+")
    /** Control characters without a short escape; [format] writes them as \uXXXX. */
    private val CONTROL = Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f\\u007f]")
    private val NUMBER = Regex("[+-]?(\\d[\\d_]*)(\\.\\d[\\d_]*)?([eE][+-]?\\d+)?")

    /** An array written on one line up to this long; longer, or holding tables, it takes a line per value. */
    private const val LINE = 80

    /**
     * Values are String, Double, Boolean, List (an array, or a `[[name]]`'s tables) or LinkedHashMap
     * (an inline table, or one `[[name]]`, its keys flattened too). Throws [Error] at the first line it can't read.
     */
    fun parse(text: String): LinkedHashMap<String, Any> = Reader(text).document()

    private class Reader(
        private val text: String,
    ) {
        private var i = 0
        private var line = 1

        private fun fail(message: String): Nothing = throw Error(line, message)

        private fun peek() = text.getOrNull(i)

        private fun next(): Char = text[i++].also { if (it == '\n') line++ }

        /** Spaces and tabs. */
        private fun blanks() {
            while (peek() == ' ' || peek() == '\t') i++
        }

        private fun skipComment() {
            if (peek() == '#') while (peek() != null && peek() != '\n') i++
        }

        /** Blanks, comments and line ends: what may come between lines, or between an array's values. */
        private fun gaps() {
            while (true) {
                blanks()
                skipComment()
                if (peek() == '\n' || peek() == '\r') next() else return
            }
        }

        /** What may follow a header or a value on its line: blanks and a comment. */
        private fun endOfLine(after: String) {
            blanks()
            skipComment()
            when (peek()) {
                null, '\n' -> {}
                '\r' -> if (text.getOrNull(i + 1) != '\n') fail("unexpected text after $after")
                else -> fail("unexpected text after $after")
            }
        }

        /** A bare key up to [end], dotted parts joined with dots; [end] is read too. */
        private fun key(
            end: String,
            missing: String,
        ): String {
            val stop = text.indexOf(end, i).takeIf { it >= 0 && '\n' !in text.substring(i, it) } ?: fail(missing)
            val raw = text.substring(i, stop)
            i = stop + end.length
            return raw.split('.').joinToString(".") { part ->
                part.trim().takeIf { BARE_KEY.matches(it) } ?: fail("bad key \"${raw.trim()}\": use letters, digits, - and _")
            }
        }

        private fun put(
            table: MutableMap<String, Any>,
            key: String,
            value: Any,
        ) {
            if (key in table) fail("$key is set twice")
            table[key] = value
        }

        fun document(): LinkedHashMap<String, Any> {
            val out = LinkedHashMap<String, Any>()
            /** Names of the `[[name]]` arrays: nothing else may go under them. */
            val arrays = HashSet<String>()
            var table: MutableMap<String, Any> = out
            var section = ""
            fun underArray(key: String) = key.substringBefore('.') in arrays
            while (true) {
                gaps()
                if (peek() == null) return out
                if (text.startsWith("[[", i)) {
                    i += 2
                    val name = key("]]", "missing ]]")
                    if ('.' in name) fail("[[$name]]: only arrays of tables at the top are supported")
                    val list =
                        if (name in arrays) {
                            @Suppress("UNCHECKED_CAST")
                            out[name] as MutableList<Any>
                        } else {
                            if (name in out || out.keys.any { it.startsWith("$name.") }) fail("$name is already set")
                            arrays += name
                            ArrayList<Any>().also { out[name] = it }
                        }
                    table = LinkedHashMap<String, Any>().also { list += it }
                    section = ""
                    endOfLine("]]")
                } else if (peek() == '[') {
                    i++
                    section = key("]", "missing ]")
                    if (underArray(section)) fail("[$section]: ${section.substringBefore('.')} is an array of tables")
                    table = out
                    endOfLine("]")
                } else {
                    val k = key("=", "expected key = value").let { if (section.isEmpty()) it else "$section.$it" }
                    if (table === out && underArray(k)) fail("$k: ${k.substringBefore('.')} is an array of tables")
                    blanks()
                    put(table, k, value())
                    endOfLine("the value")
                }
            }
        }

        private fun value(): Any =
            when {
                text.startsWith("\"\"\"", i) || text.startsWith("'''", i) -> fail("multi-line strings aren't supported")
                peek() == '"' -> basicString()
                peek() == '\'' -> literalString()
                peek() == '[' -> array()
                peek() == '{' -> inlineTable()
                else -> bare()
            }

        private fun bare(): Any {
            val start = i
            while (peek()?.let { it !in ",]}#\r\n" } == true) i++
            val bare = text.substring(start, i).trim()
            return when {
                bare == "true" -> true
                bare == "false" -> false
                NUMBER.matches(bare) -> bare.replace("_", "").toDouble()
                bare.isEmpty() -> fail("missing value")
                else -> fail("\"$bare\" isn't a value: put text in quotes")
            }
        }

        private fun array(): List<Any> {
            next()
            val out = ArrayList<Any>()
            while (true) {
                gaps()
                if (peek() == ']') {
                    next()
                    return out
                }
                if (peek() == null) fail("missing ]")
                out += value()
                gaps()
                when (peek()) {
                    ',' -> next()
                    ']' -> {
                        next()
                        return out
                    }
                    null -> fail("missing ]")
                    else -> fail("expected , or ] after a value")
                }
            }
        }

        private fun inlineTable(): LinkedHashMap<String, Any> {
            next()
            val out = LinkedHashMap<String, Any>()
            blanks()
            if (peek() == '}') {
                next()
                return out
            }
            while (true) {
                blanks()
                val k = key("=", "expected key = value, all on one line")
                blanks()
                put(out, k, value())
                blanks()
                when (peek()) {
                    ',' -> next()
                    '}' -> {
                        next()
                        return out
                    }
                    null, '\r', '\n' -> fail("missing }: an inline table stays on one line")
                    else -> fail("expected , or } after a value")
                }
            }
        }

        private fun literalString(): String {
            val end = text.indexOf('\'', i + 1)
            if (end < 0 || '\n' in text.substring(i, end)) fail("missing closing '")
            return text.substring(i + 1, end).also { i = end + 1 }
        }

        private fun basicString(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                val c = peek()
                when {
                    c == null || c == '\n' -> fail("missing closing \"")
                    c == '"' -> {
                        i++
                        return sb.toString()
                    }
                    c == '\\' && i + 1 < text.length -> {
                        sb.append(
                            when (val e = text[i + 1]) {
                                'n' -> '\n'
                                't' -> '\t'
                                'r' -> '\r'
                                '"', '\\' -> e
                                'u' -> {
                                    val hex = text.substring(i + 2, minOf(i + 6, text.length))
                                    if (hex.length < 4) fail("\\u takes four hex digits")
                                    i += 4
                                    hex.toIntOrNull(16)?.toChar() ?: fail("\\u takes four hex digits")
                                }
                                else -> fail("unknown escape \\$e")
                            },
                        )
                        i += 2
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }
    }

    /**
     * [values] as TOML: top-level keys first, then a section per first key
     * part, in the order given, then a `[[name]]` per table of a top-level list
     * of tables. Whole numbers are written without a fraction.
     */
    fun write(
        values: Map<String, Any>,
        header: String = "",
    ): String =
        buildString {
            append(header)
            fun entry(
                k: String,
                v: Any,
            ) = append(k).append(" = ").append(format(v, block = true)).append('\n')
            val arrays = values.filter { (k, v) -> '.' !in k && v is List<*> && v.isNotEmpty() && v.all { it is Map<*, *> } }
            val (top, nested) = values.entries.filter { it.key !in arrays }.partition { '.' !in it.key }
            for ((k, v) in top) entry(k, v)
            for ((section, entries) in nested.groupBy { it.key.substringBefore('.') }) {
                if (isNotEmpty()) append('\n')
                append('[').append(section).append("]\n")
                for ((k, v) in entries) entry(k.substringAfter('.'), v)
            }
            for ((name, tables) in arrays) {
                for (table in tables as List<*>) {
                    if (isNotEmpty()) append('\n')
                    append("[[").append(name).append("]]\n")
                    for ((k, v) in table as Map<*, *>) entry(k.toString(), v!!)
                }
            }
        }

    /** [v] as a TOML value; a [block] value, one on a line of its own, may spread an array over several lines. */
    fun format(
        v: Any,
        block: Boolean = false,
    ): String =
        when (v) {
            is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r").replace(CONTROL) { "\\u%04x".format(it.value[0].code) } + "\""
            is Double -> if (v == Math.rint(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
            is Number -> format(v.toDouble())
            is Map<*, *> -> if (v.isEmpty()) "{}" else v.entries.joinToString(", ", "{ ", " }") { (k, x) -> "$k = ${format(x!!)}" }
            is List<*> -> {
                val one = v.joinToString(", ", "[", "]") { format(it!!) }
                if (block && v.isNotEmpty() && (one.length > LINE || v.any { it is Map<*, *> })) v.joinToString("", "[\n", "]") { "  ${format(it!!)},\n" } else one
            }
            else -> v.toString()
        }
}
