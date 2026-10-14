package dev.omalauncher.theme

/**
 * The part of TOML a settings file needs: `[section]` headers, dotted bare
 * keys and string, number and boolean values, with comments. Keys come out
 * flattened ("widgets.gaps-in"), in file order. Quoted keys, arrays, inline
 * tables and multi-line strings aren't settings anyone writes, so they're
 * errors; that way whatever [parse] reads, [write] writes back readably.
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

    /** Values are String, Double or Boolean. Throws [Error] at the first line it can't read. */
    fun parse(text: String): LinkedHashMap<String, Any> {
        val out = LinkedHashMap<String, Any>()
        var section = ""
        text.lines().forEachIndexed { i, raw ->
            val n = i + 1
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            if (line.startsWith("[")) {
                if (line.startsWith("[[")) throw Error(n, "arrays of tables aren't supported")
                val end = line.indexOf(']')
                if (end < 0) throw Error(n, "missing ]")
                if (!comment(line.substring(end + 1))) throw Error(n, "unexpected text after ]")
                section = key(line.substring(1, end), n)
                return@forEachIndexed
            }
            val eq = line.indexOf('=')
            if (eq < 0) throw Error(n, "expected key = value")
            val key = key(line.substring(0, eq), n).let { if (section.isEmpty()) it else "$section.$it" }
            if (key in out) throw Error(n, "$key is set twice")
            out[key] = value(line.substring(eq + 1).trim(), n)
        }
        return out
    }

    /** A bare key, dotted parts joined with dots. */
    private fun key(
        text: String,
        line: Int,
    ): String =
        text.split('.').joinToString(".") { part ->
            part.trim().takeIf { BARE_KEY.matches(it) } ?: throw Error(line, "bad key \"${text.trim()}\": use letters, digits, - and _")
        }

    private fun value(
        text: String,
        line: Int,
    ): Any {
        if (text.startsWith("\"\"\"") || text.startsWith("'''")) throw Error(line, "multi-line strings aren't supported")
        if (text.startsWith("\"")) return basicString(text, line)
        if (text.startsWith("'")) {
            val end = text.indexOf('\'', 1)
            if (end < 0) throw Error(line, "missing closing '")
            if (!comment(text.substring(end + 1))) throw Error(line, "unexpected text after the string")
            return text.substring(1, end)
        }
        if (text.startsWith("[") || text.startsWith("{")) throw Error(line, "arrays and inline tables aren't supported")
        val bare = text.substringBefore('#').trim()
        return when {
            bare == "true" -> true
            bare == "false" -> false
            NUMBER.matches(bare) -> bare.replace("_", "").toDouble()
            bare.isEmpty() -> throw Error(line, "missing value")
            else -> throw Error(line, "\"$bare\" isn't a value: put text in quotes")
        }
    }

    private fun basicString(
        text: String,
        line: Int,
    ): String {
        val sb = StringBuilder()
        var i = 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    if (!comment(text.substring(i + 1))) throw Error(line, "unexpected text after the string")
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
                                if (hex.length < 4) throw Error(line, "\\u takes four hex digits")
                                i += 4
                                hex.toIntOrNull(16)?.toChar() ?: throw Error(line, "\\u takes four hex digits")
                            }
                            else -> throw Error(line, "unknown escape \\$e")
                        },
                    )
                    i++
                }
                else -> sb.append(c)
            }
            i++
        }
        throw Error(line, "missing closing \"")
    }

    /** Whether [rest] of a line is blank or a comment. */
    private fun comment(rest: String) = rest.isBlank() || rest.trimStart().startsWith("#")

    /**
     * [values] as TOML: top-level keys first, then a section per first key
     * part, in the order given. Whole numbers are written without a fraction.
     */
    fun write(
        values: Map<String, Any>,
        header: String = "",
    ): String =
        buildString {
            append(header)
            val (top, nested) = values.entries.partition { '.' !in it.key }
            for ((k, v) in top) append(k).append(" = ").append(format(v)).append('\n')
            for ((section, entries) in nested.groupBy { it.key.substringBefore('.') }) {
                if (isNotEmpty()) append('\n')
                append('[').append(section).append("]\n")
                for ((k, v) in entries) append(k.substringAfter('.')).append(" = ").append(format(v)).append('\n')
            }
        }

    fun format(v: Any): String =
        when (v) {
            // In single quotes when that saves escaping its double ones, like an extra-keys layout's.
            is String -> formatString(v)
            is Double -> if (v == Math.rint(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
            is Number -> format(v.toDouble())
            else -> v.toString()
        }

    /** Write a literal TOML string when it avoids escaping the layout's double quotes. */
    private fun formatString(value: String): String {
        if ('"' in value && '\'' !in value && !value.any { it < ' ' || it == '\u007f' }) return "'$value'"
        return "\"" +
            value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace(CONTROL) { "\\u%04x".format(it.value[0].code) } +
            "\""
    }
}
