package dev.omalauncher.widgets.lang

/**
 * How much work a Shader's AGSL does for each pixel: a frame waits for every pixel, and nothing stops a heavy one,
 * whose frame hangs the GPU and takes the launcher down (or the phone's graphics) every time its page shows. AGSL
 * only bounds each loop on its own (under 100,000 turns), so three loops inside each other pass it. The launcher
 * draws only code within [MAX_TURNS], and the store's review checks the code it can see before anyone installs it.
 *
 * The count is the turns of every loop, those inside another multiplied by the outer one's, and a call to the
 * shader's own function counts that function's work. AGSL loops count with constant bounds (`for (int i = 0; i < 8;
 * i++)`, a `const` in place of a number), so their turns can be worked out; `break` is ignored, the count is the most.
 */
object ShaderCost {
    /** Loop turns a pixel may take: far above the store's shaders (the heaviest, jinja, does 205), far below a hang. */
    const val MAX_TURNS = 1000L

    /** Why [code] isn't drawn, or null when its work is within [MAX_TURNS]. */
    fun problem(code: String): String? {
        val turns =
            try {
                turns(code)
            } catch (e: Unclear) {
                return "its loops can't be counted (${e.message}): count with fixed numbers, for (int i = 0; i < 8; i++), or a const"
            }
        if (turns <= MAX_TURNS) return null
        return "its loops take ${if (turns >= LIMIT) "billions of" else String.format(java.util.Locale.ROOT, "%,d", turns)} turns for every pixel, " +
            "over the $MAX_TURNS a shader may take: each frame waits for every pixel, and a heavy one freezes the phone's screen; " +
            "use fewer loops inside loops, or fewer turns"
    }

    /** Loop turns per pixel of [code]'s main(), at most [LIMIT]. */
    fun turns(code: String): Long {
        val t = tokens(code)
        val consts = HashMap<String, Double>()
        val functions = HashMap<String, IntRange>()
        // `const int N = 8;`, at the top or in a function: a name it may count to.
        for (k in t.indices) {
            if (t[k] != "const" || t.getOrNull(k + 3) != "=") continue
            val end = t.subList(k, t.size).indexOf(";")
            if (end > 0) runCatching { consts[t[k + 2]] = Expr(t.subList(k + 4, k + end), consts).value() }
        }
        var depth = 0
        var i = 0
        while (i < t.size) {
            when {
                t[i] == "{" -> depth++
                t[i] == "}" -> depth--
                // `float wave(float2 p) {` at the top: one of the shader's own functions.
                depth == 0 && isName(t[i]) && i + 2 < t.size && isName(t.getOrNull(i + 1)) && t[i + 2] == "(" -> {
                    val close = match(t, i + 2)
                    if (t.getOrNull(close + 1) == "{") {
                        functions[t[i + 1]] = close + 1..match(t, close + 1)
                        i = match(t, close + 1)
                    }
                }
            }
            i++
        }
        val main = functions["main"] ?: return 0
        val memo = HashMap<String, Long>()
        fun cost(name: String, stack: Set<String>): Long =
            memo.getOrPut(name) {
                if (name in stack) throw Unclear("$name calls itself")
                val body = functions.getValue(name)
                Count(t, consts, functions) { cost(it, stack + name) }.block(body.first + 1, body.last)
            }
        return cost("main", emptySet())
    }

    /** Counts are kept at most this, so many loops inside each other don't overflow. */
    const val LIMIT = 1_000_000_000_000L

    private class Count(
        val t: List<String>,
        val consts: Map<String, Double>,
        val functions: Map<String, IntRange>,
        val call: (String) -> Long,
    ) {
        /** Turns of the tokens from [from] up to (not including) [to]. */
        fun block(
            from: Int,
            to: Int,
        ): Long {
            var sum = 0L
            var i = from
            while (i < to) {
                val tok = t[i]
                if (tok == "for" && t.getOrNull(i + 1) == "(") {
                    val close = match(t, i + 1)
                    val n = trips(t.subList(i + 2, close))
                    val bodyEnd = if (t.getOrNull(close + 1) == "{") match(t, close + 1) else statementEnd(close + 1)
                    val inner = maxOf(1, block(close + 1, bodyEnd + 1))
                    sum = add(sum, mul(n, inner))
                    i = bodyEnd + 1
                    continue
                }
                if (tok == "while" || tok == "do") throw Unclear("AGSL has no $tok loops")
                if (tok in functions && t.getOrNull(i + 1) == "(") sum = add(sum, call(tok))
                i++
            }
            return sum
        }

        /** The last token of the statement starting at [from], its `;` (or the `}` of a block). */
        fun statementEnd(from: Int): Int {
            var i = from
            while (i < t.size) {
                when (t[i]) {
                    "(", "{" -> {
                        if (t[i] == "{") return match(t, i)
                        i = match(t, i)
                    }
                    ";" -> return i
                    "for" -> {
                        val close = match(t, i + 1)
                        return if (t.getOrNull(close + 1) == "{") match(t, close + 1) else statementEnd(close + 1)
                    }
                }
                i++
            }
            return t.size - 1
        }

        /** Turns of a loop from its header's tokens, `int i = 0; i < 8; i++`. */
        fun trips(header: List<String>): Long {
            val parts = split(header)
            if (parts.size != 3) throw Unclear("a loop without three parts")
            val (init, cond, step) = parts
            val eq = init.indexOf("=")
            if (eq < 1) throw Unclear("a loop that doesn't start its counter at a number")
            val name = init[eq - 1]
            val start = Expr(init.subList(eq + 1, init.size), consts).value()
            if (cond.size < 3 || cond[0] != name) throw Unclear("a loop whose test isn't on its counter ($name < …)")
            val op = cond[1]
            val end = Expr(cond.subList(2, cond.size), consts).value()
            val by =
                when {
                    step == listOf(name, "++") || step == listOf("++", name) -> 1.0
                    step == listOf(name, "--") || step == listOf("--", name) -> -1.0
                    step.size >= 3 && step[0] == name && step[1] == "+=" -> Expr(step.subList(2, step.size), consts).value()
                    step.size >= 3 && step[0] == name && step[1] == "-=" -> -Expr(step.subList(2, step.size), consts).value()
                    else -> throw Unclear("a loop that doesn't count by a fixed step")
                }
            if (by == 0.0) throw Unclear("a loop that never moves")
            val span =
                when (op) {
                    "<" -> end - start
                    "<=" -> end - start + by
                    ">" -> start - end
                    ">=" -> start - end - by
                    "!=" -> if (by > 0) end - start else start - end
                    else -> throw Unclear("a loop tested with $op")
                }
            val n = kotlin.math.ceil(span / kotlin.math.abs(by))
            if (n.isNaN()) throw Unclear("a loop whose bounds aren't numbers")
            return n.coerceIn(0.0, LIMIT.toDouble()).toLong()
        }

        /** [header] cut at its top-level `;`s. */
        fun split(header: List<String>): List<List<String>> {
            val out = ArrayList<List<String>>()
            var at = 0
            var depth = 0
            for ((k, tok) in header.withIndex()) {
                if (tok == "(") depth++
                if (tok == ")") depth--
                if (tok == ";" && depth == 0) {
                    out += header.subList(at, k)
                    at = k + 1
                }
            }
            out += header.subList(at, header.size)
            return out
        }
    }

    /** A loop bound: numbers, consts, + - * /, brackets and casts like float(8). */
    private class Expr(
        val t: List<String>,
        val consts: Map<String, Double>,
    ) {
        var i = 0

        fun value(): Double {
            val v = sum()
            if (i != t.size) throw Unclear("a loop bound that isn't a fixed number (${t.joinToString(" ")})")
            return v
        }

        fun sum(): Double {
            var v = product()
            while (i < t.size && (t[i] == "+" || t[i] == "-")) v = if (t[i++] == "+") v + product() else v - product()
            return v
        }

        fun product(): Double {
            var v = unary()
            while (i < t.size && (t[i] == "*" || t[i] == "/")) v = if (t[i++] == "*") v * unary() else v / unary()
            return v
        }

        fun unary(): Double {
            val tok = t.getOrNull(i++) ?: throw Unclear("a loop bound that ends early")
            return when {
                tok == "-" -> -unary()
                tok == "(" -> sum().also { expect(")") }
                tok in CASTS && t.getOrNull(i) == "(" -> {
                    i++
                    sum().also { expect(")") }
                }
                tok in consts -> consts.getValue(tok)
                else -> tok.trimEnd('f', 'F').toDoubleOrNull() ?: throw Unclear("a loop bound that isn't a fixed number ($tok)")
            }
        }

        fun expect(s: String) {
            if (t.getOrNull(i++) != s) throw Unclear("a loop bound with unmatched brackets")
        }
    }

    private class Unclear(
        message: String,
    ) : Exception(message)

    private val CASTS = setOf("int", "float", "half")

    private fun isName(s: String?) = s != null && (s[0].isLetter() || s[0] == '_')

    /** The index of the bracket that closes the one at [open]. */
    private fun match(
        t: List<String>,
        open: Int,
    ): Int {
        val o = t[open]
        val c = if (o == "(") ")" else "}"
        var depth = 0
        for (k in open until t.size) {
            if (t[k] == o) depth++
            if (t[k] == c && --depth == 0) return k
        }
        throw Unclear("unmatched $o")
    }

    private fun add(
        a: Long,
        b: Long,
    ) = minOf(LIMIT, a + b)

    private fun mul(
        a: Long,
        b: Long,
    ) = if (a != 0L && b > LIMIT / a) LIMIT else minOf(LIMIT, a * b)

    private val TOKEN = Regex("""[A-Za-z_][A-Za-z0-9_]*|\d+\.?\d*(?:[eE][-+]?\d+)?[fF]?|\.\d+(?:[eE][-+]?\d+)?[fF]?|\+\+|--|[-+*/<>=!]=|&&|\|\||\S""")

    /** [code] as tokens, without comments. */
    private fun tokens(code: String): List<String> {
        val bare = code.replace(Regex("""//[^\n]*|/\*[\s\S]*?\*/"""), " ")
        return TOKEN.findAll(bare).map { it.value }.toList()
    }
}
