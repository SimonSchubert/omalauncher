package dev.omalauncher.widgets.lang

/**
 * Recursive-descent parser for widget files. Items need no separators: a new
 * item starts wherever the previous expression can't continue, and `(`/`[`
 * only continue an expression on the same line.
 */
class Parser private constructor(
    private val src: String,
    private val tokens: List<Token>,
) {
    private var p = 0

    companion object {
        fun parse(src: String): WidgetAst = Parser(src, Lexer(src).run()).widget()

        /** Declarations written as a block: `meta { ... }`, `requires { ... }`. */
        private val BLOCK_DECLS = setOf("meta", "requires")

        private val DECLS = setOf("source", "state", "let", "asset", "component", "meta", "requires")

        /** Statements that run on a timer or a change, not a touch: `when cond { ... }`. */
        val TRIGGERS = TriggerKind.entries.mapTo(HashSet()) { it.word }
    }

    private val t get() = tokens[p]

    private fun peek(n: Int = 1) = tokens[(p + n).coerceAtMost(tokens.size - 1)]

    private fun next() = tokens[p++]

    private fun fail(
        msg: String,
        at: Token = t,
    ): Nothing = throw CompileError(msg, at.pos)

    private fun expect(type: T) {
        if (t.type != type) fail("expected ${type.name.lowercase()}, found $t")
    }

    private fun punct(s: String): Token {
        if (!t.isP(s)) fail("expected '$s', found $t")
        return next()
    }

    private fun accept(s: String) = t.isP(s).also { if (it) p++ }

    /** A trailing `persist` on the same line as a state or let. */
    private fun persist() = (t.isId("persist") && !t.nl).also { if (it) p++ }

    private fun ident(what: String = "name"): String {
        if (t.type != T.Ident) fail("expected $what, found $t")
        return next().text
    }

    private fun widget(): WidgetAst {
        val kind = OwlKind.entries.firstOrNull { t.isId(it.word) } ?: fail("an owl file starts with: widget \"name\" {, face \"name\" {, app \"name\" { or panel \"name\" {")
        next()
        val name = if (t.type == T.Str) literalString(next()) else null
        punct("{")
        val body = body(top = true)
        punct("}")
        expect(T.Eof)
        return WidgetAst(name, body, kind)
    }

    private fun literalString(tok: Token): String =
        tok.parts.singleOrNull()?.let { (it as? StrPart.Lit)?.text } ?: fail("expected a plain string", tok)

    /** Items until the closing brace (not consumed). */
    private fun body(top: Boolean = false): Body {
        val props = ArrayList<Prop>()
        val handlers = ArrayList<Handler>()
        val animations = ArrayList<Prop>()
        val items = ArrayList<Item>()
        val decls = ArrayList<Decl>()
        while (!t.isP("}") && t.type != T.Eof) {
            if (accept(";")) continue
            val start = t
            if (start.type != T.Ident) fail("expected a property, node or declaration, found $start")
            val word = start.text
            val after = peek()
            when {
                word in TRIGGERS && !after.isP(":") && !after.isP("{") -> {
                    if (!top) fail("'$word' is only allowed at the top of the widget")
                    next()
                    val e = expr()
                    // `on x first { }`: for x's first value too.
                    val first = word == "on" && t.type == T.Ident && t.text == "first"
                    if (first) next()
                    if (!t.isP("{")) fail("expected '{' with the statements to run: $word ... { ... }")
                    decls += Decl.Trigger(word, e, handler(), start.pos, first)
                }
                word == "if" -> items += ifItem()
                word == "for" -> items += forItem()
                word == "animate" && after.type == T.Ident -> {
                    next()
                    val prop = ident("property to animate")
                    punct(":")
                    animations += Prop(prop, expr(), start.pos)
                }
                word == "let" && !top && after.type == T.Ident -> {
                    next()
                    val name = ident()
                    punct("=")
                    items += Item.Let(name, expr(), start.pos)
                }
                word in DECLS && (after.type == T.Ident || (word in BLOCK_DECLS && after.isP("{"))) -> {
                    if (!top) fail("'$word' is only allowed at the top of the widget")
                    decls += decl()
                }
                after.isP(":") -> {
                    next()
                    next()
                    if (word.startsWith("on") && word.length > 2 && word[2].isUpperCase()) {
                        handlers += Handler(word, handler(), start.pos)
                    } else {
                        props += Prop(word, expr(), start.pos)
                    }
                }
                after.isP("{") -> {
                    next()
                    next()
                    val b = body()
                    punct("}")
                    items += Item.Node(word, b, start.pos)
                }
                after.isP("(") && !after.nl -> {
                    val call = primary() as? Expr.Call ?: fail("expected a call", start)
                    val b =
                        if (t.isP("{") && !t.nl) {
                            next()
                            body().also { punct("}") }
                        } else {
                            null
                        }
                    items += Item.Call(call, b, start.pos)
                }
                else -> fail("expected ':' or '{' after '$word'", after)
            }
        }
        return Body(props, handlers, animations, items, decls)
    }

    private fun ifItem(): Item.If {
        val pos = next().pos
        val cond = expr()
        punct("{")
        val then = body()
        punct("}")
        val orElse =
            if (t.isId("else")) {
                next()
                if (t.isId("if")) {
                    val nested = ifItem()
                    Body(emptyList(), emptyList(), emptyList(), listOf(nested), emptyList())
                } else {
                    punct("{")
                    body().also { punct("}") }
                }
            } else {
                null
            }
        return Item.If(cond, then, orElse, pos)
    }

    private fun forItem(): Item.For {
        val pos = next().pos
        val name = ident("loop variable")
        val index = if (accept(",")) ident("index variable") else null
        if (!t.isId("in")) fail("expected 'in'")
        next()
        val list = expr()
        val key =
            if (t.isId("key")) {
                next()
                expr()
            } else {
                null
            }
        punct("{")
        val b = body()
        punct("}")
        return Item.For(name, index, list, key, b, pos)
    }

    private fun decl(): Decl {
        val kw = next()
        if (kw.text in BLOCK_DECLS) {
            punct("{")
            val fields = ArrayList<Prop>()
            while (!t.isP("}")) {
                if (accept(";") || accept(",")) continue
                val pos = t.pos
                val name = ident("${kw.text} field")
                punct(":")
                fields += Prop(name, expr(), pos)
            }
            punct("}")
            return if (kw.text == "meta") Decl.Meta(fields, kw.pos) else Decl.Requires(fields, kw.pos)
        }
        val name = ident()
        return when (kw.text) {
            "component" -> {
                if (!name[0].isUpperCase()) fail("component names start with a capital letter", kw)
                val params = ArrayList<Pair<String, Expr?>>()
                if (accept("(")) {
                    while (!t.isP(")")) {
                        val param = ident("parameter")
                        params += param to if (accept("=")) expr() else null
                        if (!accept(",")) break
                    }
                    punct(")")
                }
                punct("{")
                val b = body()
                punct("}")
                Decl.Component(name, params, b, kw.pos)
            }
            else -> {
                punct("=")
                when (kw.text) {
                    "state" -> Decl.State(name, expr(), kw.pos, persist())
                    "let" -> Decl.Let(name, expr(), kw.pos, persist())
                    "asset" -> {
                        if (!t.isId("base64")) fail("expected base64\"...\"")
                        next()
                        expect(T.Str)
                        Decl.Asset(name, literalString(next()).filterNot { it.isWhitespace() }, kw.pos)
                    }
                    else -> {
                        val e = expr()
                        var every: Expr? = null
                        var json = false
                        var timeout: Expr? = null
                        var status = false
                        var optional = false
                        while (t.type == T.Ident && !t.nl) {
                            when (t.text) {
                                "every" -> {
                                    next()
                                    every = unary()
                                }
                                "json" -> {
                                    next()
                                    json = true
                                }
                                "timeout" -> {
                                    next()
                                    timeout = unary()
                                }
                                "status" -> {
                                    next()
                                    status = true
                                }
                                "optional" -> {
                                    next()
                                    optional = true
                                }
                                else -> break
                            }
                        }
                        Decl.Source(name, e, every, json, timeout, kw.pos, status, optional)
                    }
                }
            }
        }
    }

    private fun handler(): List<Stmt> = if (t.isP("{")) block() else listOf(stmt())

    private fun block(): List<Stmt> {
        punct("{")
        val out = ArrayList<Stmt>()
        while (!t.isP("}")) {
            if (accept(";")) continue
            out += stmt()
        }
        punct("}")
        return out
    }

    private fun stmt(): Stmt {
        val start = t
        if (start.type == T.Ident && peek().isP("=")) {
            next()
            next()
            return Stmt.Assign(start.text, expr(), start.pos)
        }
        // A state named if or let still assigns, above.
        if (start.isId("if")) {
            next()
            val cond = expr()
            if (!t.isP("{")) fail("expected '{' with the statements to run: if ... { ... }")
            val then = block()
            val orElse =
                if (t.isId("else")) {
                    next()
                    if (t.isId("if")) listOf(stmt()) else block()
                } else {
                    emptyList()
                }
            return Stmt.If(cond, then, orElse, start.pos)
        }
        if (start.isId("let") && peek().type == T.Ident && peek(2).isP("=")) {
            next()
            val name = next().text
            next()
            return Stmt.Let(name, expr(), start.pos)
        }
        val e = expr()
        if (e !is Expr.Call) fail("expected an assignment (x = ...) or an action like run(\"...\")", start)
        return Stmt.Do(e, start.pos)
    }

    // Expressions, lowest precedence first.

    fun expr(): Expr {
        val c = binary(0)
        if (t.isP("?")) {
            val q = next()
            val a = expr()
            punct(":")
            val b = expr()
            return Expr.Cond(c, a, b, q.pos)
        }
        return c
    }

    private val levels =
        listOf(
            setOf("??"),
            setOf("||"),
            setOf("&&"),
            setOf("==", "!="),
            setOf("<", "<=", ">", ">="),
            setOf("+", "-"),
            setOf("*", "/", "%"),
        )

    private fun binary(level: Int): Expr {
        if (level == levels.size) return unary()
        var left = binary(level + 1)
        while (t.type == T.Punct && t.text in levels[level]) {
            val op = next()
            left = Expr.Binary(op.text, left, binary(level + 1), op.pos)
        }
        return left
    }

    private fun unary(): Expr {
        if (t.isP("!") || t.isP("-")) {
            val op = next()
            return Expr.Unary(op.text, unary(), op.pos)
        }
        return postfix(primary())
    }

    private fun postfix(start: Expr): Expr {
        var e = start
        while (true) {
            e =
                when {
                    t.isP(".") -> {
                        next()
                        val nameTok = t
                        // list.0 as well as obj.field
                        val name = if (t.type == T.Num) next().text else ident("field name")
                        if (t.isP("(") && !t.nl) {
                            val (args, named) = args()
                            Expr.Call(name, listOf(e) + args, named, nameTok.pos)
                        } else {
                            Expr.Member(e, name, nameTok.pos)
                        }
                    }
                    t.isP("[") && !t.nl -> {
                        val open = next()
                        val index = expr()
                        punct("]")
                        Expr.Index(e, index, open.pos)
                    }
                    else -> return e
                }
        }
    }

    private fun args(): Pair<List<Expr>, List<Pair<String, Expr>>> {
        punct("(")
        val args = ArrayList<Expr>()
        val named = ArrayList<Pair<String, Expr>>()
        while (!t.isP(")")) {
            if (t.type == T.Ident && peek().isP(":")) {
                val name = next().text
                next()
                named += name to expr()
            } else {
                if (named.isNotEmpty()) fail("positional arguments go before named ones")
                args += expr()
            }
            if (!accept(",")) break
        }
        punct(")")
        return args to named
    }

    private fun isLambdaAhead(): Boolean {
        if (t.type == T.Ident) return peek().isP("=>")
        if (!t.isP("(")) return false
        var n = 1
        while (true) {
            val tok = peek(n)
            when {
                tok.isP(")") -> return peek(n + 1).isP("=>")
                tok.type == T.Ident || tok.isP(",") -> n++
                else -> return false
            }
        }
    }

    private fun primary(): Expr {
        val tok = t
        if (isLambdaAhead()) {
            val params = ArrayList<String>()
            if (accept("(")) {
                while (!t.isP(")")) {
                    params += ident("parameter")
                    if (!accept(",")) break
                }
                punct(")")
            } else {
                params += ident()
            }
            punct("=>")
            return Expr.Lambda(params, expr(), tok.pos)
        }
        return when (tok.type) {
            T.Num -> {
                next()
                when {
                    tok.text.startsWith("#") -> Expr.Lit(Col(null, parseHex(tok.text)!!), tok.pos)
                    tok.pair != null -> Expr.Lit(Lst(listOf(Num(tok.pair.first), Num(tok.pair.second))), tok.pos)
                    else -> Expr.Lit(Num(tok.num), tok.pos)
                }
            }
            T.Str -> {
                next()
                stringExpr(tok)
            }
            T.Ident -> {
                next()
                when (tok.text) {
                    "true" -> Expr.Lit(TRUE, tok.pos)
                    "false" -> Expr.Lit(FALSE, tok.pos)
                    "null" -> Expr.Lit(Null, tok.pos)
                    else ->
                        if (t.isP("(") && !t.nl) {
                            val (args, named) = args()
                            Expr.Call(tok.text, args, named, tok.pos)
                        } else {
                            Expr.Name(tok.text, tok.pos)
                        }
                }
            }
            T.Punct ->
                when (tok.text) {
                    "(" -> {
                        next()
                        expr().also { punct(")") }
                    }
                    "[" -> {
                        next()
                        val items = ArrayList<Expr>()
                        while (!t.isP("]")) {
                            items += expr()
                            if (!accept(",")) break
                        }
                        punct("]")
                        Expr.ListLit(items, tok.pos)
                    }
                    "{" -> {
                        next()
                        val entries = ArrayList<Pair<String, Expr>>()
                        while (!t.isP("}")) {
                            val key =
                                when (t.type) {
                                    T.Ident -> next().text
                                    T.Str -> literalString(next())
                                    else -> fail("expected a key")
                                }
                            punct(":")
                            entries += key to expr()
                            if (!accept(",")) accept(";")
                        }
                        punct("}")
                        Expr.MapLit(entries, tok.pos)
                    }
                    else -> fail("expected a value, found $tok")
                }
            T.Eof -> fail("expected a value, found end of file")
        }
    }

    private fun stringExpr(tok: Token): Expr {
        if (tok.parts.size == 1 && tok.parts[0] is StrPart.Lit) return Expr.Lit(Str((tok.parts[0] as StrPart.Lit).text), tok.pos)
        val parts =
            tok.parts.map { part ->
                when (part) {
                    is StrPart.Lit -> Expr.Lit(Str(part.text), tok.pos)
                    is StrPart.Code -> {
                        val sub = Parser(src, Lexer(src, part.start, part.end).run())
                        sub.expr().also { sub.expect(T.Eof) }
                    }
                }
            }
        return Expr.Interp(parts, tok.pos)
    }
}
