package dev.omalauncher.widgets.market

import dev.omalauncher.widgets.lang.CompileError
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Lexer
import dev.omalauncher.widgets.lang.Program
import dev.omalauncher.widgets.lang.Source
import dev.omalauncher.widgets.lang.T
import dev.omalauncher.widgets.lang.Token
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A whole widget file in a link, to pass on as a QR code:
 *
 *     https://simonschubert.github.io/omarchy-widgets/w#owl1.<data>
 *
 * `<data>` is the file's UTF-8 bytes, raw DEFLATE-compressed (no zlib or gzip
 * header), in unpadded base64url. It sits after the `#`, so it never reaches a
 * server: the page there only shows the widget and hands it to the launcher.
 * The desktop app (omarchy-widgets share) makes the same links.
 *
 * Nobody reviewed a shared widget, so [Shared] works out what it runs and what
 * looks risky, for the user to read before it goes on the home screen.
 */
object Share {
    const val PREFIX = "owl1."
    const val LINK = "https://simonschubert.github.io/omarchy-widgets/w#"
    const val HOST = "simonschubert.github.io"
    const val PATH = "/omarchy-widgets/w"

    /** What a version 40 QR code holds in byte mode at error correction L. */
    const val MAX_LINK = 2953

    fun link(text: String) = LINK + encode(text)

    /** The short link to a widget uploaded with [Paste]: `…/w#dpaste.<id>`. */
    fun pasteLink(id: String) = LINK + PASTE + id

    const val PASTE = "dpaste."

    /** The [Paste] id in a link, its fragment, or the bare `dpaste.` data; null for a link that holds the widget itself. */
    fun pasteId(s: String): String? {
        if (s.contains(PREFIX)) return null
        val at = s.indexOf(PASTE)
        if (at < 0) return null
        return s.substring(at + PASTE.length).trim().takeIf(Paste.ID::matches)
    }

    /** A widget as it came, from a link or from [Paste]: [format]ted if it was [minify]d, as written otherwise. */
    fun received(text: String) = if (minify(text) == text) format(text) else text

    fun encode(text: String): String {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            d.setInput(text.toByteArray(Charsets.UTF_8))
            d.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        } finally {
            d.end()
        }
    }

    /**
     * [text] as it goes out: without `page` and `order`, which place it on the sender's home screen.
     * The rest stays as written. A file that doesn't lex goes as it is.
     */
    fun prepare(text: String): String {
        val tokens = lex(text) ?: return text
        val out = StringBuilder()
        var at = 0
        var depth = 0
        var k = 0
        while (k < tokens.size) {
            val t = tokens[k]
            out.append(text, at, t.pos)
            // `page: 2` at the top of the widget, as a statement of its own.
            val placement =
                depth == 1 && (t.isId("page") || t.isId("order")) && (t.nl || tokens[k - 1].let { it.isP("{") || it.isP(";") }) &&
                    tokens.getOrNull(k + 1)?.isP(":") == true && tokens.getOrNull(k + 2)?.type == T.Num
            if (placement) {
                var last = k + 2
                if (tokens.getOrNull(k + 3)?.let { it.isP(";") && !it.nl } == true) last++
                at = tokens[last].let { it.pos + it.text.length }
                // On a line of its own, the line goes too, with a comment at its end.
                if (text.substring(text.lastIndexOf('\n', t.pos - 1) + 1, t.pos).isBlank()) {
                    val eol = text.indexOf('\n', at)
                    val rest = text.substring(at, if (eol < 0) text.length else eol)
                    if (rest.isBlank() || rest.trimStart().startsWith("//")) at = if (eol < 0) text.length else eol + 1
                }
                // No spaces left at the end of a line: the indentation above, when the whole line went.
                if (at == text.length || text[at] == '\n' || text[at - 1] == '\n') {
                    while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) out.setLength(out.length - 1)
                }
                k = last + 1
                continue
            }
            if (t.type == T.Eof) break
            if (t.isP("{")) depth++
            if (t.isP("}")) depth--
            at = t.pos + t.text.length
            out.append(text, t.pos, at)
            k++
        }
        return out.toString()
    }

    /**
     * [prepare]d [text] at its smallest, for a QR code: no comments, no indentation, a space only where two
     * tokens would otherwise run together. Every line break stays, since a line break ends a statement, so
     * it compiles to the same program. [format] lays it out again on the other side.
     */
    fun minify(text: String): String {
        val src = prepare(text)
        val tokens = lex(src) ?: return src
        return join(tokens) { a, b, _ -> if (b.nl) "\n" else if (apart(a, b)) " " else "" }
    }

    /**
     * A [minify]d widget laid out to read: indented by its brackets, with spaces around operators and after
     * commas. Only spaces and indentation change, never the tokens or where lines break.
     */
    fun format(text: String): String {
        val tokens = lex(text) ?: return text
        // The line each open bracket was opened on: a line indents once however many brackets it opens.
        val open = ArrayList<Int>()
        var line = 0
        var ternary = 0
        return join(tokens) { a, b, k ->
            // Track the brackets and ternaries [a] leaves open.
            when {
                a.isP("{") || a.isP("(") || a.isP("[") -> open += line
                a.isP("}") || a.isP(")") || a.isP("]") -> open.removeLastOrNull()
                a.isP("?") -> ternary++
                a.isP(":") && ternary > 0 -> ternary--
            }
            // A sign or a `!` sticks to what follows.
            val unary = (a.isP("-") || a.isP("+")) && !operand(tokens.getOrNull(k - 2)) || a.isP("!")
            when {
                b.nl -> {
                    line++
                    ternary = 0
                    // A line that starts by closing a bracket sits with the line that opened it.
                    val levels = (if (b.isP("}") || b.isP(")") || b.isP("]")) open.dropLast(1) else open).distinct().size
                    "\n" + "  ".repeat(levels)
                }
                apart(a, b) -> " "
                unary || a.isP(".") || b.isP(".") || b.isP(",") || b.isP(";") || b.isP(")") || b.isP("]") -> ""
                a.isP("(") || a.isP("[") -> ""
                a.isP("{") && b.isP("}") -> ""
                b.isP("(") || b.isP("[") -> if (operand(a) && a.text !in KEYWORDS) "" else " "
                b.isP(":") -> if (ternary > 0) " " else ""
                else -> " "
            }
        }
    }

    private fun lex(text: String): List<Token>? =
        try {
            Lexer(text).run()
        } catch (e: CompileError) {
            null
        }

    /** [tokens] as written, with [gap] between each and the next; a file ends in a line break. */
    private fun join(
        tokens: List<Token>,
        gap: (Token, Token, Int) -> String,
    ): String {
        val out = StringBuilder()
        for (k in tokens.indices) {
            val t = tokens[k]
            if (k > 0) out.append(if (t.type == T.Eof) "\n" else gap(tokens[k - 1], t, k))
            out.append(t.text)
        }
        return out.toString()
    }

    private val KEYWORDS = setOf("if", "else", "for", "in", "let", "state", "source", "asset", "component", "meta", "requires", "every", "at", "when", "on", "widget", "key", "persist")

    /** Ends a value, so a `-` after it subtracts and a `(` after it calls. */
    private fun operand(t: Token?) = t != null && (t.type != T.Punct || t.isP(")") || t.isP("]"))

    /** [a] and [b] would lex as something else written together: `let x`, `3 x2`, `= =`, `/ /`, `"a" "b"`. */
    private fun apart(
        a: Token,
        b: Token,
    ): Boolean {
        val x = a.text.last()
        val y = b.text.first()
        fun word(c: Char) = c.isLetterOrDigit() || c == '_'
        return word(x) && word(y) ||
            a.type == T.Num && y == '.' ||
            "$x$y" in Lexer.TWO || "$x$y" == "//" || "$x$y" == "/*" ||
            a.type == T.Str && b.type == T.Str || x == '"' && y == '"'
    }

    /** Fits in one QR code. */
    fun fits(link: String) = link.length <= MAX_LINK

    /**
     * The widget file in a link, its fragment, or the bare `owl1.` data, [format]ted if it came [minify]d. Throws [ShareError] for anything else,
     * and for data that would unpack to more than [Review.MAX_BYTES].
     */
    fun decode(s: String): String {
        val at = s.indexOf(PREFIX)
        if (at < 0) throw ShareError("not a widget link")
        val data = s.substring(at + PREFIX.length).trim().trimEnd('=')
        val bytes =
            try {
                Base64.getUrlDecoder().decode(data)
            } catch (e: IllegalArgumentException) {
                throw ShareError("the link is damaged")
            }
        val inf = Inflater(true)
        try {
            // A raw inflater wants one byte past the end of the stream.
            inf.setInput(bytes + 0)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n =
                    try {
                        inf.inflate(buf)
                    } catch (e: DataFormatException) {
                        throw ShareError("the link is damaged")
                    }
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw ShareError("the link is cut short")
                out.write(buf, 0, n)
                if (out.size() > Review.MAX_BYTES) throw ShareError("the widget is over ${Review.MAX_BYTES / 1024} KB")
            }
            val decoder = Charsets.UTF_8.newDecoder()
            val text =
                try {
                    decoder.decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
                } catch (e: java.nio.charset.CharacterCodingException) {
                    throw ShareError("the widget isn't text")
                }
            return received(text)
        } finally {
            inf.end()
        }
    }
}

class ShareError(
    message: String,
) : Exception(message)

/** A widget from a link, compiled, with what a user should know before adding it. */
class Shared(
    val text: String,
) {
    val program: Program?
    val error: String?

    init {
        var p: Program? = null
        var e: String? = null
        try {
            p = Compiler.compile(text)
        } catch (x: CompileError) {
            e = "doesn't compile: " + Source(text).format(x)
        }
        program = p
        error = e
    }

    val name get() = program?.name.orEmpty()
    val description get() = Catalog.metaText(program?.meta?.get("description")).orEmpty()
    val version get() = Catalog.metaText(program?.meta?.get("version")).orEmpty()
    val commands get() = program?.commands.orEmpty()
    val requires get() = program?.requires.orEmpty()

    /**
     * What an agent reviewing it reads: without comments, which are only the sender talking to whoever reads
     * the file, an agent included. Laid out again, so it's as readable. The user sees [text].
     */
    val forReview get() = Share.format(Share.minify(text))

    /** Commands that do something a reviewer would look at twice. */
    val risks get() = Review.risks(commands)

    /** The file it is saved as: `shared-<name>.owl`, the name as the store would spell it. */
    val fileName: String
        get() = "$FILE_PREFIX${Submit.slug(name)}.owl"

    companion object {
        const val FILE_PREFIX = "shared-"
    }
}
