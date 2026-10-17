package dev.omalauncher.widgets.lang

import java.util.Base64

/**
 * Checks a parsed widget and compiles it: names resolve to slots, expressions
 * become closures with constants folded, and every node and property is
 * validated here so the renderer never sees a malformed tree.
 */
class Compiler private constructor(
    private val ast: WidgetAst,
    /** The file, to quote a value in a warning. */
    private val src: String,
) {
    companion object {
        const val MAX_NODES = 500

        /** A screen has more room than a tile, so it gets its own, larger limit. */
        const val MAX_SCREEN_NODES = 1000

        /**
         * The language version this compiler implements. Widgets that need a
         * newer one declare it, `meta { owl: 2 }`, and older launchers say so
         * instead of failing on what they don't know. Bump it with every
         * addition a widget could depend on: nodes, properties, builtins.
         */
        const val LEVEL = 8
        private const val MAX_ASSET_BYTES = 2 shl 20

        /** An app icon is a shape or two drawn in SVG, not a picture. */
        private const val MAX_ICON_CHARS = 16 * 1024

        fun compile(src: String): Program =
            try {
                Compiler(Parser.parse(src), src).program()
            } catch (e: StackOverflowError) {
                // `((((...` or `1+1+1+...` thousands deep: parsing and compiling recurse per level.
                throw CompileError("too deeply nested", 0)
            }

        /** Whether Linux is installed and running, for widgets with `optional` sources. Shadowable like [TILE]. */
        const val LINUX = "linux"

        /** Prefixes an `optional` source's command in [Program.commands], so a reviewer (and the store) can tell. */
        const val IF_LINUX = "if Linux is installed: "

        /** Where a command has an sh(...) value, as [Program.commands] lists it. */
        const val VALUE = "‹value›"

        /** Where a command has a number, which can't be anything but digits, a dot and a sign. */
        const val NUMBER = "‹number›"

        private const val MAX_SHAPES = 8

        /** A URL whose scheme and host are fixed text, so no value can send it elsewhere. */
        private val FIXED_HOST = Regex("""^https?://[A-Za-z0-9.-]+(:[0-9]+)?/""")
    }

    /** Compile-time mirror of an [Env] frame. Only the first [visible] names resolve (lets see earlier lets only). */
    private class Scope(
        val parent: Scope?,
        val names: List<String>,
        var visible: Int = names.size,
    )

    private class Component(
        val params: List<String>,
        val defaults: List<Ev?>,
        val body: List<WNode>,
    )

    private val root = Scope(null, emptyList())

    /** Per compile: its budget is never reset, and compiles run on several threads. */
    private val constHost = ConstHost()
    private val globals = ArrayList<Global?>()
    private val slots = HashMap<String, Int>()
    private val states = HashSet<String>()
    private val sources = HashSet<String>()
    private val components = HashMap<String, Component>()
    private val commands = LinkedHashSet<String>()
    private val warnings = LinkedHashSet<String>()

    /** The commands that are fixed text, so their programs can be read off them. */
    private val scripts = LinkedHashSet<String>()

    /** Runs anything in the guest: a cmd or stream source, a Terminal, or a run, terminal or app action. */
    private var guest = false
    private var animated = false
    private var terminal = false
    private var nodes = 0
    private var maxNodes = MAX_NODES

    /** Whether nodes being compiled are the screen's (true) or the tile's (false); null in components, which can go in either. */
    private var inScreen: Boolean? = null

    /** Where a handler calls screen() or close(), which need a screen { } block. */
    private var screenAction: Int? = null

    /** Where a handler calls screen(), which an app has no use for. */
    private var openScreen: Int? = null

    /** The newest feature used, from [SINCE], and where: the widget must declare at least its version. */
    private var needs: Triple<Int, String, Int>? = null

    private fun since(
        feature: String,
        pos: Int,
    ) {
        val v = SINCE[feature] ?: return
        if (v > (needs?.first ?: 1)) needs = Triple(v, feature, pos)
    }

    private fun fail(
        msg: String,
        pos: Int,
    ): Nothing = throw CompileError(msg, pos)

    private fun program(): Program {
        val body = ast.body
        // Checked first: a newer widget likely uses nodes this launcher doesn't know, and would fail confusingly below.
        val levelField = body.decls.filterIsInstance<Decl.Meta>().flatMap { it.fields }.firstOrNull { it.name == "owl" }
        val level = levelField?.let { constant(it.value, "meta values").num()?.toInt() ?: fail("owl: takes a number, like owl: 1", it.pos) } ?: 1
        if (level > LEVEL) fail("this widget needs owl $level; the launcher supports up to $LEVEL, so update the launcher", levelField!!.pos)
        if (body.animations.isNotEmpty()) fail("animate belongs inside a node", body.animations[0].pos)
        for (h in body.handlers) if (h.name != "onTap" && !(ast.kind == OwlKind.App && h.name == "onBack")) fail("a widget only takes onTap at the top level", h.pos)

        // Sources, state and assets first: lets and components may use any of them.
        for (d in body.decls) {
            if (d !is Decl.Meta && d !is Decl.Trigger && d.name in slots) fail("'${d.name}' is already declared", d.pos)
            when (d) {
                is Decl.Source -> reserve(d.name, d.pos).also { sources += d.name }
                is Decl.State -> reserve(d.name, d.pos).also { states += d.name }
                is Decl.Asset -> reserve(d.name, d.pos)
                else -> {}
            }
        }
        for (d in body.decls) {
            when (d) {
                is Decl.Source -> globals[slots[d.name]!!] = source(d)
                is Decl.State -> globals[slots[d.name]!!] = Global.State(d.name, ex(d.init, root), d.persist)
                is Decl.Asset -> globals[slots[d.name]!!] = asset(d)
                is Decl.Let -> {
                    val g = let(d)
                    reserve(d.name, d.pos)
                    globals[slots[d.name]!!] = g
                }
                is Decl.Component -> {
                    if (d.name in components || d.name in KINDS) fail("'${d.name}' is already a node", d.pos)
                    components[d.name] = component(d)
                }
                is Decl.Meta, is Decl.Requires, is Decl.Trigger -> {}
            }
        }
        // After every declaration, so a trigger can read lets declared below it.
        val triggers = body.decls.filterIsInstance<Decl.Trigger>().map(::trigger)

        val props = body.props.associateBy { it.name }
        val meta = body.decls.filterIsInstance<Decl.Meta>().flatMap { it.fields }.associate { it.name to constant(it.value, "meta values") }
        val requires = requires(body.decls.filterIsInstance<Decl.Requires>().flatMap { it.fields })
        if (ast.kind == OwlKind.App) return app(body, meta, level, requires, globals.map { it!! }, triggers)
        for (p in body.props) if (p.name !in WIDGET_PROPS) fail("unknown widget property '${p.name}' (${WIDGET_PROPS.joinToString()})", p.pos)
        val screens = body.items.filter { it is Item.Node && it.type == "screen" }.map { it as Item.Node }
        if (screens.size > 1) fail("a widget has one screen { }", screens[1].pos)
        inScreen = false
        val rootNodes = items(body.items - screens.toSet(), root, Children.UI)
        if (rootNodes.isEmpty()) fail("a widget needs at least one node, like Text { text: \"hi\" }", 0)
        val tileNodes = nodes
        val screen = screens.firstOrNull()?.let { screen(it) }
        val onTap = body.handlers.firstOrNull()?.let { handler(it, root) }
        if (screen == null) screenAction?.let { fail("screen() and close() need a screen { } block in the widget", it) }
        checkLevel(level)

        return Program(
            name = ast.name ?: "widget",
            meta = meta,
            level = level,
            size = props["size"]?.let { cells(it) },
            minSize = props["minSize"]?.let { cells(it) } ?: (1 to 1),
            page = props["page"]?.let { constInt(it) }?.coerceAtLeast(1) ?: 1,
            order = props["order"]?.let { constInt(it) } ?: 0,
            padding = props["padding"]?.let { constant(it.value, "padding").num() } ?: 12.0,
            background = props["background"]?.let { ex(it.value, root) },
            globals = globals.map { it!! },
            root = rootNodes,
            screen = screen?.first,
            screenTitle = screen?.second,
            onTap = onTap,
            triggers = triggers,
            commands = commands.toList(),
            warnings = warnings.toList(),
            requires = requires,
            needsLinux = guest || requires.isNotEmpty(),
            programs = scripts.flatMap(ShellWords::programs).toSortedSet(),
            animated = animated,
            terminal = terminal,
            nodeCount = tileNodes,
        )
    }

    /** Declared, so a launcher too old for a feature says "update the launcher" instead of failing on an unknown name. */
    private fun checkLevel(level: Int) {
        needs?.let { (v, feature, pos) -> if (level < v) fail("$feature needs owl $v: add meta { owl: $v }", pos) }
    }

    /**
     * An app: the file is a screen of its own, opened from the app drawer, so its nodes are the screen's and
     * there's no tile, no place on a page, and no screen { } inside it.
     */
    private fun app(
        body: Body,
        meta: Map<String, Value>,
        level: Int,
        requires: Map<String, List<String>>,
        globals: List<Global>,
        triggers: List<Trigger>,
    ): Program {
        since("app \"…\" { }", 0)
        for (p in body.props) if (p.name !in setOf("title", "icon", "back", "edge")) fail("an app only takes title, icon, back and edge (no size or page: it isn't a tile)", p.pos)
        // Drawn under the navigation bar: fixed in the file, since the whole view moves when it changes.
        val edge =
            body.props.firstOrNull { it.name == "edge" }?.let { p ->
                ((p.value as? Expr.Lit)?.value as? Bool)?.v ?: fail("edge is true or false, not an expression", p.pos)
            } ?: false
        // The drawer draws it before the app runs, so it's fixed in the file, not an expression: an SVG, or an asset's image.
        val iconProp = body.props.firstOrNull { it.name == "icon" }
        val iconImage =
            (iconProp?.value as? Expr.Name)?.let { n ->
                (globals.firstOrNull { it is Global.Asset && it.name == n.name } as? Global.Asset)?.bytes
                    ?: fail("icon names an asset: asset logo = base64\"...\" and icon: logo", n.pos)
            }
        val icon =
            iconProp?.takeIf { iconImage == null }?.let { p ->
                val svg = ((p.value as? Expr.Lit)?.value as? Str)?.v?.trim()
                    ?: fail("icon is an SVG in a string (icon: \"\"\"<svg ...>...</svg>\"\"\") or an asset's name (icon: logo)", p.pos)
                if (!svg.startsWith("<svg") || !svg.endsWith("</svg>")) fail("icon should be one <svg>...</svg> element", p.pos)
                if (svg.length > MAX_ICON_CHARS) fail("icon is over ${MAX_ICON_CHARS / 1024} KB; keep it a simple shape, or use a PNG asset", p.pos)
                svg
            }
        body.handlers.firstOrNull { it.name != "onBack" }?.let { fail("handlers go on the nodes inside an app; only onBack goes at its top", it.pos) }
        // Back goes up a level inside the app while `back` is true, and closes it otherwise.
        val backProp = body.props.firstOrNull { it.name == "back" }
        val onBackHandler = body.handlers.firstOrNull { it.name == "onBack" }
        if ((backProp == null) != (onBackHandler == null)) {
            fail("back and onBack go together: back: view != \"list\" says when Back goes up a level, onBack: view = \"list\" does it", (backProp?.pos ?: onBackHandler!!.pos))
        }
        body.items.firstOrNull { it is Item.Node && it.type == "screen" }?.let { fail("an app is a screen already: put its nodes at the top, without screen { }", it.pos) }
        nodes = 0
        maxNodes = MAX_SCREEN_NODES
        inScreen = true
        val tree = items(body.items, root, Children.UI)
        if (tree.isEmpty()) fail("an app needs at least one node, like Text { text: \"hi\" }", 0)
        openScreen?.let { fail("screen() opens a widget's screen { }; an app is open already, and close() closes it", it) }
        checkLevel(level)
        return Program(
            name = ast.name ?: "app",
            meta = meta,
            level = level,
            globals = globals,
            screen = tree,
            screenTitle = body.props.firstOrNull { it.name == "title" }?.let { ex(it.value, root) },
            back = backProp?.let { ex(it.value, root) },
            onBack = onBackHandler?.let { handler(it, root) },
            edge = edge,
            triggers = triggers,
            commands = commands.toList(),
            warnings = warnings.toList(),
            requires = requires,
            needsLinux = guest || requires.isNotEmpty(),
            programs = scripts.flatMap(ShellWords::programs).toSortedSet(),
            animated = animated,
            terminal = terminal,
            nodeCount = nodes,
            kind = ast.kind,
            icon = icon,
            iconImage = iconImage,
        )
    }

    /** The screen { } block: its nodes, and its title if it sets one. */
    private fun screen(n: Item.Node): Pair<List<WNode>, Ev?> {
        since("screen", n.pos)
        val body = n.body
        for (p in body.props) if (p.name != "title") fail("a screen only takes title (its nodes go inside it)", p.pos)
        body.handlers.firstOrNull()?.let { fail("handlers go on the nodes inside a screen", it.pos) }
        body.animations.firstOrNull()?.let { fail("animate belongs inside a node", it.pos) }
        nodes = 0
        maxNodes = MAX_SCREEN_NODES
        inScreen = true
        val tree = items(body.items, root, Children.UI)
        if (tree.isEmpty()) fail("a screen needs at least one node", n.pos)
        return tree to body.props.firstOrNull()?.let { ex(it.value, root) }
    }

    private fun reserve(
        name: String,
        pos: Int,
    ): Int {
        if (name in Builtins.theme || name in Builtins.constants || name == "time") fail("'$name' is a built-in name", pos)
        slots[name] = globals.size
        globals += null
        return globals.size - 1
    }

    /**
     * Lists a command for the reviewer, and its text for [scripts]. Fixed text as it is; put together while
     * the widget runs, its [shapes] when every value goes in through sh(...) where quoting holds, and
     * "[what] built from widget values" when one doesn't: then it could run anything.
     */
    private fun listCommand(
        e: Expr,
        ev: Ev,
        s: Scope,
        what: String,
        prefix: String = "",
    ) {
        (ev.const as? Str)?.v?.let {
            scripts += it
            commands += prefix + it
            return
        }
        val shapes = shapes(e, s)?.takeIf { it.all(ShellWords::quotable) }
        if (shapes == null) {
            commands += "$prefix$what built from widget values"
            return
        }
        for (shape in shapes) {
            // For ShellWords, a value is a word it skips, like a variable.
            scripts += shape.replace(VALUE, "\"\$v\"").replace(NUMBER, "0")
            commands += prefix + shape
        }
    }

    /**
     * What [e] can turn out as, one string per way, with each sh(...) as [VALUE] and each number as
     * [NUMBER]. Null when a value goes in any other way, or there are more than [MAX_SHAPES].
     */
    private fun shapes(
        e: Expr,
        s: Scope,
        value: String = "sh",
    ): List<String>? {
        ex(e, s).const?.let { c ->
            // A URL that's null pauses its source: no shape.
            return if (c is Str || c is Num) listOf(c.toString()) else if (c == Null && value == "enc") emptyList() else null
        }
        fun join(
            a: List<String>,
            b: List<String>,
        ) = a.flatMap { x -> b.map { x + it } }
        val out =
            when (e) {
                is Expr.Interp -> e.parts.fold(listOf("")) { acc, p -> join(acc, shapes(p, s, value) ?: return null) }
                is Expr.Binary -> if (e.op == "+") join(shapes(e.a, s, value) ?: return null, shapes(e.b, s, value) ?: return null) else null
                is Expr.Cond -> (shapes(e.a, s, value) ?: return null) + (shapes(e.b, s, value) ?: return null)
                is Expr.Call -> if (e.name == value) listOf(VALUE) else if (e.name in NUMBERS) listOf(NUMBER) else null
                // A top-level let that is fixed text: `let api = "https://..."` and then "${api}/x".
                is Expr.Name -> constText(e, s)?.let { listOf(it) }
                else -> null
            }
        return out?.distinct()?.takeIf { it.size <= MAX_SHAPES }
    }

    /**
     * An image src or a sound that is, or starts as, an https URL: it needs owl 8, and it's listed like
     * http(...), as "[what] GET <url>", or the fixed start of it and "…" when the rest comes from widget
     * values. A URL that is all a value (one from a source's JSON) can't be known here.
     */
    private fun webFile(
        e: Expr,
        s: Scope,
        what: String,
    ) {
        // Each way it can turn out: sound(voice ? "https://..." : null) plays that URL.
        if (e is Expr.Cond) return listOf(e.a, e.b).forEach { webFile(it, s, what) }
        val whole = constText(e, s)
        val start = whole ?: constantStart(e, s)
        if (start.startsWith("http://")) fail("${what}s load over https only", e.pos)
        if (!start.startsWith("https://")) return
        if (what == "image") since(WEB_IMAGE, e.pos)
        commands += "$what GET " + (whole ?: "$start…")
    }

    /** The text [e] starts with whatever its values turn out to be: "https://x.test/" for "https://x.test/${id}.svg". */
    private fun constantStart(
        e: Expr,
        s: Scope,
    ): String =
        when (e) {
            is Expr.Interp -> {
                val out = StringBuilder()
                for (p in e.parts) {
                    val c = constText(p, s) ?: return out.append(constantStart(p, s)).toString()
                    out.append(c)
                }
                out.toString()
            }
            is Expr.Binary -> if (e.op == "+") constText(e.a, s)?.let { it + constantStart(e.b, s) } ?: constantStart(e.a, s) else ""
            else -> constText(e, s).orEmpty()
        }

    /** [e]'s text if it's fixed: a literal, a name for a top-level let that is one (`let base = "https://..."`), or "${base}a.svg". */
    private fun constText(
        e: Expr,
        s: Scope,
    ): String? {
        if (e is Expr.Interp) return e.parts.map { constText(it, s) ?: return null }.joinToString("")
        val c = ex(e, s).const ?: (e as? Expr.Name)?.let { n -> (lookup(n.name, s) as? Ref.Slot)?.let { (globals[it.index] as? Global.Let)?.value?.const } }
        return if (c is Str || c is Num) c.toString() else null
    }

    private fun constant(
        e: Expr,
        what: String,
    ): Value = ex(e, root).const ?: fail("$what must be constant", e.pos)

    private fun constInt(p: Prop) = constant(p.value, p.name).num()?.toInt() ?: fail("${p.name} must be a number", p.pos)

    private fun cells(p: Prop): Pair<Int, Int> {
        val v = constant(p.value, p.name) as? Lst
        val w = v?.items?.getOrNull(0)?.num()?.toInt()
        val h = v?.items?.getOrNull(1)?.num()?.toInt()
        if (v?.items?.size != 2 || w == null || h == null) fail("${p.name} is columns x rows, like 3x2", p.pos)
        return w.coerceAtLeast(1) to h.coerceAtLeast(1)
    }

    private fun source(d: Decl.Source): Global.Source {
        val e = d.expr
        if (d.status && !(e is Expr.Call && e.name in setOf("cmd", "http"))) fail("status only works on cmd(...) and http(...)", d.pos)
        if (d.optional) {
            if (!(e is Expr.Call && e.name in setOf("cmd", "stream", "file"))) fail("optional only works on cmd(...), stream(...) and file(...), which need Linux", d.pos)
            since("optional", d.pos)
        }
        // An optional source's command is listed as one, and doesn't make the widget need Linux.
        val listed = if (d.optional) IF_LINUX else ""
        // NaN would poll without pause (and a clock would divide by its zero interval).
        val every = d.every?.let { constant(it, "every").num() }?.takeIf { !it.isNaN() }
        val timeout = d.timeout?.let { constant(it, "timeout").num()?.toInt() } ?: 10
        return when {
            e is Expr.Call && e.name == "http" -> {
                if (e.args.size !in 1..2 || e.named.isNotEmpty()) fail("http(\"https://...\") takes a URL and optionally a map of headers", e.pos)
                val url = ex(e.args[0], root)
                (url.const as? Str)?.v?.let { if (!it.startsWith("https://") && !it.startsWith("http://")) fail("http takes an http:// or https:// URL", e.args[0].pos) }
                // Like a command's sh(...) values: listed by shape when the host is fixed and each value is enc(...) or a number.
                listUrl("http GET", e.args[0], url, root)
                Global.Source(d.name, "http", url, (every ?: 60.0).coerceAtLeast(5.0), d.json, timeout.coerceIn(1, 300), d.status, e.args.getOrNull(1)?.let { ex(it, root) })
            }
            e is Expr.Call && e.name in setOf("cmd", "stream") -> {
                if (e.args.size != 1 || e.named.isNotEmpty()) fail("${e.name}(\"command\") takes one command", e.pos)
                val arg = ex(e.args[0], root)
                listCommand(e.args[0], arg, root, "${e.name}(…)", listed)
                if (!d.optional) guest = true
                Global.Source(d.name, e.name, arg, (every ?: 60.0).coerceAtLeast(1.0), d.json, timeout.coerceIn(1, 300), d.status, optional = d.optional)
            }
            e is Expr.Call && e.name == "file" -> {
                if (e.args.size != 1 || e.named.isNotEmpty()) fail("file(\"~/path\") takes one path", e.pos)
                if (d.every != null || d.timeout != null) fail("file(...) updates when the file changes; it takes no every or timeout", d.pos)
                val path = ex(e.args[0], root)
                (path.const as? Str)?.v?.let { if (!it.startsWith("/") && !it.startsWith("~/")) fail("file takes a path starting with / or ~/", e.args[0].pos) }
                commands += listed + "read file " + ((path.const as? Str)?.v ?: "(a path built from widget values)")
                since("file", e.pos)
                if (!d.optional) guest = true
                Global.Source(d.name, "file", path, 0.0, d.json, 0, optional = d.optional)
            }
            e is Expr.Name && e.name == "clock" -> Global.Source(d.name, "clock", null, (every ?: 1.0).coerceAtLeast(0.1), false, 0)
            e is Expr.Member && (e.e as? Expr.Name)?.name == "android" -> {
                if (e.name !in ANDROID) fail("unknown android source '${e.name}' (${ANDROID.joinToString()})", e.pos)
                since("android.${e.name}", e.pos)
                // Listed with the commands, so the store shows it before anyone installs the widget.
                if (e.name == "notifications") commands += "read Android notifications (apps, titles and text)"
                if (e.name == "apps") commands += "read installed Android apps (names, packages and icons)"
                Global.Source(d.name, "android.${e.name}", null, (every ?: 60.0).coerceAtLeast(1.0), false, 0)
            }
            else -> fail("a source is cmd(\"...\"), stream(\"...\"), http(\"...\"), file(\"...\"), clock, or android.${ANDROID.joinToString("/")}", e.pos)
        }
    }

    private fun requires(fields: List<Prop>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (f in fields) {
            val valid = MANAGERS[f.name] ?: fail("unknown package manager '${f.name}' (${MANAGERS.keys.joinToString()})", f.pos)
            val names =
                when (val v = constant(f.value, "requires")) {
                    is Str -> listOf(v.v)
                    is Lst -> v.items.map { (it as? Str)?.v ?: fail("package names are strings, like \"jq\"", f.value.pos) }
                    else -> fail("${f.name} takes a list of package names, like [\"jq\", \"curl\"]", f.value.pos)
                }
            for (n in names) if (!valid.matches(n)) fail("'$n' isn't a valid ${f.name} package name", f.value.pos)
            out.getOrPut(f.name) { mutableListOf() }.addAll(names.filter { it !in out[f.name].orEmpty() })
        }
        return out
    }

    private fun asset(d: Decl.Asset): Global.Asset {
        val bytes = runCatching { Base64.getDecoder().decode(d.base64) }.getOrElse { fail("asset '${d.name}' isn't valid base64", d.pos) }
        if (bytes.size > MAX_ASSET_BYTES) fail("asset '${d.name}' is over 2 MB", d.pos)
        return Global.Asset(d.name, bytes)
    }

    private fun let(d: Decl.Let): Global {
        val v = d.value
        if (v is Expr.Call && v.name in setOf("history", "prev")) {
            val of = v.args.getOrNull(0) ?: fail("${v.name}(value) needs a value", v.pos)
            return if (v.name == "history") {
                val keep = v.args.getOrNull(1)?.let { constant(it, "history length").num()?.toInt() } ?: 60
                Global.Sampled(d.name, ex(of, root), keep.coerceIn(2, Builtins.MAX_LIST), prev = false, persist = d.persist)
            } else {
                if (d.persist) fail("only history(...) and state persist; prev(...) refills from the next sample", d.pos)
                Global.Sampled(d.name, ex(of, root), 2, prev = true)
            }
        }
        if (d.persist) fail("a let is recomputed from what it reads; persist a state or a history(...) instead", d.pos)
        return Global.Let(d.name, ex(v, root))
    }

    private fun trigger(d: Decl.Trigger): Trigger {
        since(d.kind, d.pos)
        val expr =
            if (d.kind == "every") {
                val seconds = constant(d.expr, "every").num() ?: fail("every takes an interval, like every 10s { ... }", d.expr.pos)
                if (!(seconds >= 1)) fail("every runs at most once a second", d.expr.pos)
                Ev.of(Num(seconds))
            } else {
                ex(d.expr, root)
            }
        if (d.kind != "every" && expr.const != null) fail("${d.kind} needs a value that changes, like a state or a source", d.expr.pos)
        return Trigger(TriggerKind.of(d.kind), expr, handler(Handler(d.kind, d.stmts, d.pos), root, TRIGGER_PARAMS[d.kind].orEmpty()), d.pos)
    }

    private fun component(d: Decl.Component): Component {
        val body = d.body
        if (body.props.isNotEmpty() || body.handlers.isNotEmpty()) fail("a component's body holds nodes; put properties on a node inside it", d.pos)
        val scope = Scope(root, d.params.map { it.first })
        val defaults = d.params.map { (_, def) -> def?.let { ex(it, root) } }
        return Component(d.params.map { it.first }, defaults, items(body.items, scope, Children.UI))
    }

    // Items

    private fun items(
        list: List<Item>,
        scope: Scope,
        mode: Children,
    ): List<WNode> {
        val lets = list.filterIsInstance<Item.Let>()
        if (lets.isEmpty()) return list.map { item(it, scope, mode) }
        // Local lets share one frame; each sees only the ones before it.
        val frame = Scope(scope, lets.map { it.name }, visible = 0)
        val values =
            lets.map { l ->
                ex(l.value, frame).also { frame.visible++ }
            }
        val rest = list.filter { it !is Item.Let }.map { item(it, frame, mode) }
        return listOf(WNode.Lets(values, rest, lets[0].pos))
    }

    private fun noProps(
        body: Body,
        where: String,
    ) {
        body.props.firstOrNull()?.let { fail("properties can't go inside $where; use cond ? a : b on the property instead", it.pos) }
        body.handlers.firstOrNull()?.let { fail("handlers can't go inside $where", it.pos) }
        body.animations.firstOrNull()?.let { fail("animate can't go inside $where", it.pos) }
        body.decls.firstOrNull()?.let { fail("declarations belong at the top of the widget", it.pos) }
    }

    private fun item(
        it: Item,
        scope: Scope,
        mode: Children,
    ): WNode =
        when (it) {
            is Item.If -> {
                noProps(it.then, "if")
                it.orElse?.let { b -> noProps(b, "else") }
                WNode.If(ex(it.cond, scope), items(it.then.items, scope, mode), it.orElse?.let { b -> items(b.items, scope, mode) }.orEmpty(), it.pos)
            }
            is Item.For -> {
                noProps(it.body, "for")
                val list = ex(it.list, scope)
                val inner = Scope(scope, listOfNotNull(it.name, it.index ?: "\u0000index"))
                WNode.For(list, it.key?.let { k -> ex(k, inner) }, items(it.body.items, inner, mode), it.pos)
            }
            is Item.Let -> error("lets are gathered by items()")
            is Item.Call -> {
                if (mode != Children.DRAW) fail("${it.call.name}(...) draws, so it goes inside a Canvas", it.pos)
                draw(it, scope)
            }
            is Item.Node -> {
                if (mode == Children.DRAW) fail("a Canvas holds drawing calls like circle(...), not nodes", it.pos)
                element(it, scope)
            }
        }

    private fun draw(
        it: Item.Call,
        scope: Scope,
    ): WNode.Draw {
        val c = it.call
        val op = DRAW[c.name] ?: fail("unknown drawing call '${c.name}' (${DRAW.keys.joinToString()})", c.pos)
        if (c.args.size !in op.arity) fail("${c.name} takes ${op.arity.first}${if (op.arity.last != op.arity.first) "-${op.arity.last}" else ""} values", c.pos)
        if (op.group && it.body == null) fail("${c.name}(...) wraps drawing calls: ${c.name}(...) { ... }", c.pos)
        if (!op.group && it.body != null) fail("${c.name}(...) takes no { } body", c.pos)
        for ((k, v) in c.named) if (k !in DRAW_NAMED) fail("unknown option '$k' (${DRAW_NAMED.joinToString()})", v.pos)
        since(c.name, c.pos)
        if (c.name == "image") webFile(c.args[0], scope, "image")
        nodes++
        return WNode.Draw(
            c.name,
            c.args.map { a -> ex(a, scope) },
            c.named.associate { (k, v) -> k to (enumOrNull(k, v, scope) ?: ex(v, scope)) },
            it.body?.let { b ->
                noProps(b, c.name)
                items(b.items, scope, Children.DRAW)
            },
            it.pos,
        )
    }

    private fun element(
        n: Item.Node,
        scope: Scope,
    ): WNode {
        if (++nodes > maxNodes) fail("more than $maxNodes nodes; ${if (maxNodes == MAX_NODES) "widgets" else "screens"} should stay small", n.pos)
        if (n.type == "screen") fail("screen { } goes at the top of the widget, next to its nodes", n.pos)
        val body = n.body
        body.decls.firstOrNull()?.let { fail("declarations belong at the top of the widget", it.pos) }
        components[n.type]?.let { return use(n, it, scope) }
        val kind = KINDS[n.type] ?: fail("unknown node '${n.type}' (${(KINDS.keys + components.keys).joinToString()})", n.pos)

        val props = HashMap<String, Ev>()
        for (p in body.props) {
            if (p.name !in kind.props && p.name !in COMMON) fail("${n.type} has no property '${p.name}' (${(kind.props + COMMON).joinToString()})", p.pos)
            if (p.name in props) fail("'${p.name}' is set twice", p.pos)
            // On a tile it would raise the keyboard whenever its page shows; in a component the launcher ignores it there.
            if (p.name == "focus" && inScreen == false) fail("focus only works in a screen { }", p.pos)
            since("${n.type}.${p.name}", p.pos)
            props[p.name] = prop(p, scope)
        }
        val handlers =
            body.handlers.associate { h ->
                if (h.name !in HANDLER_PARAMS) fail("unknown handler '${h.name}' (${HANDLER_PARAMS.keys.joinToString()})", h.pos)
                HANDLER_KINDS[h.name]?.let { kinds -> if (n.type !in kinds) fail("${h.name} only works on ${kinds.joinToString(" and ")}", h.pos) }
                h.name to handler(h, scope)
            }
        val animations =
            body.animations.associate { a ->
                if (a.name !in ANIMATABLE) fail("'${a.name}' can't animate (${ANIMATABLE.joinToString()})", a.pos)
                a.name to ex(a.value, scope)
            }
        since(n.type, n.pos)
        if (n.type == "Terminal") {
            terminal = true
            val command = props["command"]
            if (command == null) {
                commands += "a terminal running the login shell"
            } else {
                listCommand(body.props.first { it.name == "command" }.value, command, scope, "Terminal command")
            }
            guest = true
        }
        if (n.type == "Image") body.props.firstOrNull { it.name == "src" }?.let { webFile(it.value, scope, "image") }
        // Shaders get `time` whether they use it or not.
        if (n.type == "Shader") animated = true
        if (kind.children == Children.NONE && body.items.isNotEmpty()) fail("${n.type} can't hold other nodes", body.items[0].pos)
        val children =
            when (kind.children) {
                Children.NONE -> emptyList()
                Children.UI -> items(body.items, scope, Children.UI)
                Children.DRAW -> items(body.items, Scope(scope, listOf("width", "height")), Children.DRAW)
            }
        return WNode.Element(n.type, props, handlers, animations, children, n.pos)
    }

    private fun use(
        n: Item.Node,
        c: Component,
        scope: Scope,
    ): WNode {
        val body = n.body
        if (body.items.isNotEmpty()) fail("${n.type} takes no child nodes", body.items[0].pos)
        if (body.handlers.isNotEmpty() || body.animations.isNotEmpty()) fail("put handlers and animate inside the component", n.pos)
        val given = body.props.associateBy { it.name }
        for (p in body.props) if (p.name !in c.params && p.name !in COMMON) fail("${n.type} has no parameter '${p.name}' (${c.params.joinToString()})", p.pos)
        val args =
            c.params.mapIndexed { i, name ->
                given[name]?.let { ex(it.value, scope) } ?: c.defaults[i] ?: fail("${n.type} needs '$name'", n.pos)
            }
        val wrapper = body.props.filter { it.name !in c.params }.associate { it.name to prop(it, scope) }
        return WNode.Use(n.type, args, wrapper, c.body, n.pos)
    }

    private fun enumOrNull(
        name: String,
        e: Expr,
        scope: Scope,
    ): Ev? {
        val options = ENUMS[name] ?: return null
        // The word, even when a let, state, source or parameter has its name: `align: top` beside
        // `source top` aligns rows instead of reading (and stringifying) the source in every one.
        if (e is Expr.Name && e.name in options) return Ev.of(Str(e.name))
        return null
    }

    private fun prop(
        p: Prop,
        scope: Scope,
    ): Ev = enumOrNull(p.name, p.value, scope) ?: ex(p.value, scope)

    private fun handler(
        h: Handler,
        scope: Scope,
        params: List<String> = HANDLER_PARAMS[h.name].orEmpty(),
    ): Action {
        val inner = Scope(scope, params)
        val stmts =
            h.stmts.map { s ->
                when (s) {
                    is Stmt.Assign -> {
                        val slot = slots[s.name]
                        if (slot == null || s.name !in states) {
                            fail(if (slot == null) "unknown state '${s.name}'; declare it with: state ${s.name} = ..." else "'${s.name}' isn't a state and can't be assigned", s.pos)
                        }
                        val call = s.value as? Expr.Call
                        if (call?.name == "run") {
                            // result = run("..."): the output arrives later, when the command finishes.
                            if (call.args.size != 1 || call.named.isNotEmpty()) fail("run takes one command", call.pos)
                            val command = ex(call.args[0], inner)
                            listCommand(call.args[0], command, inner, "run(…)")
                            guest = true
                            val run: (Env) -> Unit = { env -> env.host.runInto(command(env).toString(), slot) }
                            return@map run
                        }
                        if (call?.name == "post") return@map post(call, inner, slot)
                        val v = ex(s.value, inner)
                        val run: (Env) -> Unit = { env -> env.host.write(slot, v(env)) }
                        run
                    }
                    is Stmt.Do -> {
                        val call = s.call as Expr.Call
                        if (call.name == "post") return@map post(call, inner, null)
                        val arity = Builtins.actions[call.name] ?: fail("'${call.name}' isn't an action (${Builtins.actions.keys.joinToString()})", call.pos)
                        if (call.args.size !in arity) fail("${call.name} takes ${arity.first}-${arity.last} values", call.pos)
                        val args = call.args.map { ex(it, inner) }
                        ACTION_WORDS[call.name]?.let { w ->
                            val c = (args[0].const as? Str)?.v
                            if (c != null && c !in w.words && !(w.numbers && c.toDoubleOrNull() != null)) fail(w.message, call.pos)
                        }
                        since(call.name, call.pos)
                        if (call.name == "sound") webFile(call.args[0], inner, "sound")
                        if (call.name == "screen" || call.name == "close") screenAction = screenAction ?: call.pos
                        if (call.name == "screen") openScreen = openScreen ?: call.pos
                        if (call.name in setOf("run", "terminal", "app")) {
                            listCommand(call.args[0], args[0], inner, "${call.name}(…)")
                            // A tap on terminal(...) or app(...) without Linux offers to install it; run(...) would fail unseen.
                            if (call.name == "run") guest = true
                        }
                        val run: (Env) -> Unit = { env -> env.host.act(call.name, args.map { it(env) }) }
                        run
                    }
                }
            }
        return Action(params) { env, values ->
            val e = if (params.isEmpty()) env else env.child(*Array(params.size) { values.getOrElse(it) { Null } })
            // Handlers run from touch events: a failure is reported, never thrown into the UI.
            try {
                env.host.budget.run { stmts.forEach { it(e) } }
            } catch (x: RuntimeException) {
                env.host.report("${h.name}: ${x.message ?: x}")
            }
        }
    }

    /**
     * `post(url, body, headers)`, or `x = post(...)` to get the answer into the state [slot]: an HTTP POST
     * from Android. Its URLs are listed like http(...)'s, as "http POST <url>".
     */
    private fun post(
        call: Expr.Call,
        scope: Scope,
        slot: Int?,
    ): (Env) -> Unit {
        if (call.args.size !in 1..3 || call.named.isNotEmpty()) fail("post(\"https://...\", body, headers) takes a URL, optionally a body and a map of headers", call.pos)
        since("post", call.pos)
        val url = ex(call.args[0], scope)
        (url.const as? Str)?.v?.let { if (!it.startsWith("https://") && !it.startsWith("http://")) fail("post takes an http:// or https:// URL", call.args[0].pos) }
        listUrl("http POST", call.args[0], url, scope)
        val body = call.args.getOrNull(1)?.let { ex(it, scope) }
        val headers = call.args.getOrNull(2)?.let { ex(it, scope) }
        return { env ->
            val target = url(env)
            // Like any action whose value hasn't loaded: a null URL sends nothing.
            if (target != Null) {
                val h = (headers?.invoke(env) as? Obj)?.fields.orEmpty().mapValues { it.value.toString() }
                env.host.post(target.toString(), body?.invoke(env) ?: Null, h, slot)
            }
        }
    }

    /** Lists an http URL for the reviewer, by shape when the host is fixed and each value in it is enc(...) or a number. */
    private fun listUrl(
        what: String,
        e: Expr,
        url: Ev,
        scope: Scope,
    ) {
        val shapes = (url.const as? Str)?.let { listOf(it.v) } ?: shapes(e, scope, "enc")?.takeIf { it.isNotEmpty() && it.all { s -> FIXED_HOST.containsMatchIn(s) } }
        if (shapes == null) {
            commands += "$what (a URL built from widget values)"
            urlWarning(e, scope)?.let { (msg, pos) -> warnings += "${Source(src).lineCol(pos).let { "${it.first}:${it.second}" }}: $msg" }
        } else {
            shapes.forEach { commands += "$what $it" }
        }
    }

    /**
     * Why [e], an http URL, can't be listed for review, and where: the first value that isn't put
     * through enc() or a number, with the fix, or a server that comes from a value. Agents read it
     * from owl-check, so it names the change to make.
     */
    private fun urlWarning(
        e: Expr,
        s: Scope,
    ): Pair<String, Int>? {
        // A value is the whole URL when it's reached through ternaries alone, not inside the text of one.
        var whole = false
        fun bad(
            p: Expr,
            top: Boolean,
        ): Expr? =
            when {
                constText(p, s) != null -> null
                p is Expr.Interp -> p.parts.firstNotNullOfOrNull { bad(it, false) }
                p is Expr.Binary && p.op == "+" -> bad(p.a, false) ?: bad(p.b, false)
                p is Expr.Cond -> bad(p.a, top) ?: bad(p.b, top)
                p is Expr.Call && (p.name == "enc" || p.name in NUMBERS) -> null
                ex(p, s).const == Null -> null
                else -> p.also { whole = top }
            }
        val value = bad(e, true)
        if (value != null) {
            val code = snippet(value.pos)
            if (whole) {
                return "this URL is all a value ($code), so the store's review can't show where it goes: write it out here, " +
                    "\"https://host/path?q=\${enc(v)}\", with only its values from state or a source" to value.pos
            }
            val inText = e is Expr.Interp && e.parts.any { it === value } || e is Expr.Cond
            val fix =
                when {
                    value is Expr.Call && value.name == "sh" -> "enc(...) instead of sh(...): sh() is for commands"
                    // Escaped by hand: enc() would escape the % again.
                    value is Expr.Call && value.name == "replace" && code.contains("%") -> "enc(...) around the value itself and drop the replace(...): enc() does the escaping"
                    inText -> "\${enc($code)}"
                    else -> "enc($code)"
                }
            return "a value in this URL isn't put through enc(), so the store's review can't show where it goes: write $fix (a number can go through int(n) or fixed(n, 4) instead; a path with / in it can't, so write its fixed parts out and enc() only the values between them)" to value.pos
        }
        // Every value is encoded, so the server itself must come from one.
        return "the server in this URL comes from a value: keep \"https://host/\" fixed text and put values after it" to e.pos
    }

    /** The code of the expression at [pos], as written: up to where it ends at its own depth. */
    private fun snippet(pos: Int): String {
        var depth = 0
        var i = pos
        while (i < src.length) {
            val c = src[i]
            if (c == '(' || c == '[' || c == '{') depth++
            if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) break
                depth--
            }
            if (depth == 0 && (c == ',' || c == ';' || c == '"' || c == '\n' || c == ' ' || c == '?' || c == ':')) break
            i++
        }
        return src.substring(pos, i).ifEmpty { "value" }.take(60)
    }

    // Expressions

    private sealed interface Ref {
        class Local(
            val depth: Int,
            val index: Int,
        ) : Ref

        class Slot(
            val index: Int,
        ) : Ref
    }

    private fun lookup(
        name: String,
        scope: Scope,
    ): Ref? {
        var s: Scope? = scope
        var depth = 0
        while (s != null) {
            val i = s.names.subList(0, s.visible).lastIndexOf(name)
            if (i >= 0) return Ref.Local(depth, i)
            depth++
            s = s.parent
        }
        return slots[name]?.let { Ref.Slot(it) }
    }

    private fun ex(
        e: Expr,
        s: Scope,
    ): Ev =
        when (e) {
            is Expr.Lit -> Ev.of(e.value)
            is Expr.Name -> name(e, s)
            is Expr.Interp -> {
                val parts = e.parts.map { ex(it, s) }
                // Values are trimmed (command output ends in newlines); the literal text around them isn't.
                val literal = e.parts.map { it is Expr.Lit && it.value is Str }
                fold(parts) { env -> Str(parts.withIndex().joinToString("") { (i, p) -> p(env).let { v -> if (literal[i]) v.toString() else v.text() } }) }
            }
            is Expr.ListLit -> {
                val items = e.items.map { ex(it, s) }
                fold(items) { env -> Lst(items.map { it(env) }) }
            }
            is Expr.MapLit -> {
                val keys = e.entries.map { it.first }
                val vals = e.entries.map { ex(it.second, s) }
                fold(vals) { env -> Obj(LinkedHashMap<String, Value>().apply { keys.forEachIndexed { i, k -> put(k, vals[i](env)) } }) }
            }
            is Expr.Unary -> {
                val a = ex(e.e, s)
                if (e.op == "!") {
                    fold(listOf(a)) { env -> bool(!a(env).truthy) }
                } else {
                    val slow = fold(listOf(a)) { env -> a(env).num()?.let { Num(-it) } ?: Null }
                    if (numberish(a)) fastOf(a).let { x -> numeric(slow) { env -> -x.of(env) } } else slow
                }
            }
            is Expr.Binary -> {
                val a = ex(e.a, s)
                val b = ex(e.b, s)
                when (e.op) {
                    // Like JS: `x || "none"` gives x when it's set.
                    "&&" -> fold(listOf(a, b)) { env -> a(env).let { if (!it.truthy) it else b(env) } }
                    "||" -> fold(listOf(a, b)) { env -> a(env).let { if (it.truthy) it else b(env) } }
                    "??" -> fold(listOf(a, b)) { env -> a(env).let { if (it != Null) it else b(env) } }
                    else -> {
                        val op = Builtins.operator(e.op)
                        arithmetic(e.op, a, b, fold(listOf(a, b)) { env -> op(a(env), b(env)) })
                    }
                }
            }
            is Expr.Cond -> {
                val c = ex(e.c, s)
                val a = ex(e.a, s)
                val b = ex(e.b, s)
                when {
                    c.const != null -> if (c.const.truthy) a else b
                    // `(j < 3 ? 2 : 1) * p` stays on the fast path: each branch is already a boundary of its own.
                    numberish(a) && numberish(b) -> {
                        val x = fastOf(a)
                        val y = fastOf(b)
                        Ev(null, { env -> if (c(env).truthy) a(env) else b(env) }, Fast { env -> if (c(env).truthy) x.of(env) else y.of(env) })
                    }
                    else -> Ev(null) { env -> if (c(env).truthy) a(env) else b(env) }
                }
            }
            is Expr.Member -> {
                val o = ex(e.e, s)
                fold(listOf(o)) { env -> Builtins.member(o(env), e.name) }
            }
            is Expr.Index -> {
                val o = ex(e.e, s)
                val i = ex(e.index, s)
                fold(listOf(o, i)) { env -> Builtins.index(o(env), i(env)) }
            }
            is Expr.Call -> call(e, s)
            is Expr.Lambda -> {
                val inner = Scope(s, e.params)
                val body = ex(e.body, inner)
                val n = e.params.size
                Ev(null) { env ->
                    Fn(n) { args ->
                        env.host.budget.tick()
                        // Not env.child(*...): spreading would copy the array.
                        body(Env(env.host, env, Array<Value>(n) { args.getOrElse(it) { Null } }))
                    }
                }
            }
        }

    private fun name(
        e: Expr.Name,
        s: Scope,
    ): Ev {
        when (val ref = lookup(e.name, s)) {
            is Ref.Local -> return Ev(null) { env -> env.local(ref.depth, ref.index) }
            is Ref.Slot -> return Ev(null) { env -> env.host.read(ref.index) }
            null -> {}
        }
        Builtins.constants[e.name]?.let { return Ev.of(it) }
        if (e.name in Builtins.theme) return Ev.of(Col(e.name, 0xff888888.toInt()))
        if (e.name == "time") {
            animated = true
            return Ev(null, { env -> Num(env.host.time()) }, Fast { env -> env.host.time() })
        }
        // A widget's screen always ends above the navigation bar; only an app with `edge` runs under it.
        if (e.name == "screenInset" && ast.kind != OwlKind.App) fail("screenInset is for apps with edge: true; a widget's screen ends above the navigation bar", e.pos)
        // Not reserved: widgets that already declare `rows` or `cols` keep their own.
        if (e.name in TILE || e.name == LINUX) {
            since(e.name, e.pos)
            return Ev(null) { env -> env.host.tile(e.name) }
        }
        if (e.name in ENUMS.values.flatten()) fail("'${e.name}' isn't a value here", e.pos)
        fail("unknown name '${e.name}'", e.pos)
    }

    /** Evaluates at compile time when every input is constant. */
    private fun fold(
        inputs: List<Ev>,
        f: (Env) -> Value,
    ): Ev {
        if (inputs.all { it.const != null }) {
            return runCatching { Ev.of(f(Env.root(constHost))) }.getOrElse { Ev(null, f) }
        }
        return Ev(null, f)
    }

    private fun call(
        e: Expr.Call,
        s: Scope,
    ): Ev {
        if (e.name in Builtins.actions) fail("${e.name}(...) is an action; use it in a handler like onTap", e.pos)
        if (e.name in setOf("history", "prev")) fail("${e.name}(...) keeps samples over time, so it only works as: let x = ${e.name}(...)", e.pos)
        if (e.name in setOf("cmd", "stream", "http", "file")) fail("${e.name}(...) is a source: source x = ${e.name}(\"...\")", e.pos)
        val b = Builtins.all[e.name] ?: fail("unknown function '${e.name}'", e.pos)
        since(e.name, e.pos)
        if (e.named.isNotEmpty()) fail("${e.name} takes no named arguments", e.named[0].second.pos)
        if (e.args.size < b.min || e.args.size > b.max) {
            fail("${e.name} takes ${if (b.min == b.max) "${b.min}" else "${b.min}-${b.max}"} arguments, not ${e.args.size}", e.pos)
        }
        val args = e.args.map { ex(it, s) }
        val impl = b.impl
        val run: (Env) -> Value = { env ->
            env.host.budget.tick()
            try {
                impl(Args(List(args.size) { args[it](env) }, env.host))
            } catch (x: WidgetError) {
                throw x
            } catch (x: RuntimeException) {
                throw WidgetError("${e.name}: ${x.message}")
            }
        }
        if (b.pure && args.all { it.const != null }) {
            return runCatching { Ev.of(run(Env.root(constHost))) }.getOrElse { x -> fail(x.message ?: "can't evaluate", e.pos) }
        }
        return numericCall(e.name, args, Ev(null, run))
    }

    /** Host for folding constants: no slots, no theme, no clock. */
    private class ConstHost : Host {
        override fun read(slot: Int) = throw IllegalStateException()

        override fun write(
            slot: Int,
            value: Value,
        ) = throw IllegalStateException()

        override fun theme(name: String): Int? = null

        override fun time() = 0.0

        override fun act(
            name: String,
            args: List<Value>,
        ) = throw IllegalStateException()

        override val budget = Budget()
    }
}
