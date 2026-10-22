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
        const val LEVEL = 12
        private const val MAX_ASSET_BYTES = 2 shl 20

        /** Pages as far out as a file can ask for; past Int's range, the pages either side of them would wrap. */
        private const val MAX_PAGE = 1000

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

        private val TIME_WORD = Regex("""\btime\b""")

        /** Whether a Shader's code reads its `time` uniform: only then does it need a frame clock. */
        fun shaderReadsTime(code: String) = TIME_WORD.containsMatchIn(code)

        /**
         * A URL's scheme, host and port, written out so no value can send it elsewhere: what a secret is bound to. No
         * `user@` before the host to hide where it goes, and the host ends where the text does or at a `/`, `?` or `#`.
         */
        private val FIXED_HOST = Regex("""^https?://[A-Za-z0-9.-]+(:[0-9]+)?(?=[/?#]|$)""")
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

    /** States declared `secret`: read only where [secretOk] says. */
    private val secrets = HashSet<String>()

    /** True while compiling what a secret may go into: a request's headers and body, or a check that it's set. */
    private var secretOk = false

    /** While compiling a request's URL, where a secret goes only as `enc(token)`. */
    private var inUrl = false

    /** The secrets a request being compiled sends, see [request]. */
    private var sent: HashSet<String>? = null

    /** Each secret's servers, as `https://host:port`: it's kept only while the file sends it to these. */
    private val secretHosts = HashMap<String, MutableSet<String>>()

    private fun <T> withSecrets(f: () -> T): T {
        val was = secretOk
        secretOk = true
        try {
            return f()
        } finally {
            secretOk = was
        }
    }

    /** Compiles [f], part of a request's URL, where a secret may go as `enc(token)`. */
    private fun <T> inRequestUrl(f: () -> T): T {
        val was = inUrl
        inUrl = true
        try {
            return f()
        } finally {
            inUrl = was
        }
    }

    /**
     * Whether text put together now waits for each value in it: a URL, a command or an image's src with a value still
     * loading is null, and waits, rather than asked for with "…" in it (`?days=${int(days)}` sent `days=…`).
     */
    private var wholeOnly = false

    /** Compiles [f], what a request asks for, with [wholeOnly] set. */
    private fun <T> whole(f: () -> T): T {
        val was = wholeOnly
        wholeOnly = true
        try {
            return f()
        } finally {
            wholeOnly = was
        }
    }

    /** Compiles [f] with secrets readable, but not sent: `token == null` only says whether it's set. */
    private fun <T> checking(f: () -> T): T {
        val was = sent
        sent = null
        try {
            return withSecrets(f)
        } finally {
            sent = was
        }
    }

    /**
     * Compiles a request's URL, headers and body in [f], and binds the secrets they send to the servers written out at
     * the start of its [url], so an edit that sends a secret elsewhere loses it (see [Program.secretHosts]).
     */
    private fun <T> request(
        url: Expr,
        scope: Scope,
        f: () -> T,
    ): T {
        val was = sent
        val names = HashSet<String>()
        sent = names
        try {
            val out = f()
            if (names.isNotEmpty()) {
                val hosts = inRequestUrl { servers(url, scope, names.first()) }
                for (n in names) secretHosts.getOrPut(n) { HashSet() } += hosts
            }
            return out
        } finally {
            sent = was
        }
    }

    /** The servers a request to [e] goes to, each written out at the start of a way it can turn out, for the secret [name]. */
    private fun servers(
        e: Expr,
        s: Scope,
        name: String,
    ): List<String> {
        if (e is Expr.Cond) return servers(e.a, s, name) + servers(e.b, s, name)
        // A URL that's null pauses its source: it goes nowhere.
        if (ex(e, s).const == Null) return emptyList()
        val host = server(e, s) ?: fail("$name is a secret, so the server it goes to is written out: \"https://api.example.com/…\", not a value", e.pos)
        if (host.startsWith("http://")) fail("$name is a secret, so it goes only to an https:// server, where nothing on the way can read it", e.pos)
        return listOf(host)
    }

    /** The scheme, host and port [e] starts with as fixed text ([FIXED_HOST]); null when a value can change them. */
    private fun server(
        e: Expr,
        s: Scope,
    ): String? {
        val whole = constText(e, s)
        val start = whole ?: constantStart(e, s)
        // Where the text gives way to a value, the host has to have ended first: "https://api.x${v}" could go anywhere.
        return FIXED_HOST.find(start)?.takeIf { whole != null || it.range.last + 1 < start.length }?.value?.lowercase()
    }

    /**
     * A request's URL, checked and listed for review as "[what] <url>". A secret may go into it only as `enc(token)`,
     * for an API that takes its key in the query.
     */
    private fun requestUrl(
        what: String,
        name: String,
        e: Expr,
        scope: Scope,
    ): Ev =
        inRequestUrl {
            val url = whole { ex(e, scope) }
            (url.const as? Str)?.v?.let { if (!it.startsWith("https://") && !it.startsWith("http://")) fail("$name takes an http:// or https:// URL", e.pos) }
            // Like a command's sh(...) values: listed by shape when the host is fixed and each value is enc(...) or a number.
            listUrl(what, e, url, scope)
            url
        }

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

    /** A face: it runs under the navigation bar, so its nodes may read `screenInset`. Known from the file's first word, so its lets and components see it too. */
    private val isFace = ast.kind == OwlKind.Face

    /** Where a handler calls screen() or close(), which need a screen { } block. */
    private var screenAction: Int? = null

    /**
     * Compiling a handler or a state's first value, which run once when they run: the only places a
     * value may come out different each time, as random() does. A let or a property re-reads its
     * value whenever what it reads changes, and would jump.
     */
    private var chance = false

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

    private fun <T> chancy(f: () -> T): T {
        val was = chance
        chance = true
        try {
            return f()
        } finally {
            chance = was
        }
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
        // An app, a panel and keys check their own (onBack, onSubmit).
        if (ast.kind != OwlKind.App && ast.kind != OwlKind.Panel && ast.kind != OwlKind.Keybar) {
            for (h in body.handlers) if (h.name != "onTap") fail("a ${ast.kind.word} only takes onTap at the top level", h.pos)
        }

        // Sources, state and assets first: lets and components may use any of them.
        for (d in body.decls) {
            if (d !is Decl.Meta && d !is Decl.Trigger && d.name in slots) fail("'${d.name}' is already declared", d.pos)
            when (d) {
                is Decl.Source -> reserve(d.name, d.pos).also { sources += d.name }
                is Decl.State ->
                    reserve(d.name, d.pos).also {
                        states += d.name
                        if (d.secret) {
                            since(SECRET, d.pos)
                            secrets += d.name
                        }
                    }
                is Decl.Asset -> reserve(d.name, d.pos)
                else -> {}
            }
        }
        for (d in body.decls) {
            when (d) {
                is Decl.Source -> globals[slots[d.name]!!] = source(d)
                is Decl.State -> globals[slots[d.name]!!] = Global.State(d.name, chancy { ex(d.init, root) }, d.persist, d.secret)
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
        if (ast.kind == OwlKind.Panel) return panel(body, meta, level, requires, globals.map { it!! }, triggers)
        if (ast.kind == OwlKind.Keybar) return keybar(body, meta, level, requires, globals.map { it!! }, triggers)
        // A face is a widget that fills a home page of its own, the whole screen: it gets a screen's room, and
        // has no tile's size.
        val allowed = if (isFace) FACE_PROPS else WIDGET_PROPS
        for (p in body.props) {
            if (p.name !in allowed) fail("unknown ${ast.kind.word} property '${p.name}' (${allowed.joinToString()})", p.pos)
        }
        if (isFace) {
            since(FACE_FILE, 0)
            maxNodes = MAX_SCREEN_NODES
        }
        val screens = body.items.filter { it is Item.Node && it.type == "screen" }.map { it as Item.Node }
        if (screens.size > 1) fail("a ${ast.kind.word} has one screen { }", screens[1].pos)
        inScreen = false
        val rootNodes = items(body.items - screens.toSet(), root, Children.UI)
        if (rootNodes.isEmpty()) fail("a widget needs at least one node, like Text { text: \"hi\" }", 0)
        val tileNodes = nodes
        val screen = screens.firstOrNull()?.let { screen(it) }
        val onTap = body.handlers.firstOrNull()?.let { handler(it, root) }
        if (screen == null) screenAction?.let { fail("screen() and close() need a screen { } block in the widget", it) }
        val background = props["background"]?.let { if (gradient(it.value)) since(GRADIENT_BACKGROUND, it.pos); ex(it.value, root) }
        // Whether the status bar stays over a face, read as it changes: a face can hide it for a game and bring it back.
        val statusBar = props["statusBar"]?.let { ex(it.value, root) }
        checkLevel(level)

        return Program(
            name = ast.name ?: "widget",
            meta = meta,
            level = level,
            size = props["size"]?.let { cells(it) },
            isFace = isFace,
            statusBar = statusBar,
            kind = ast.kind,
            minSize = props["minSize"]?.let { cells(it) } ?: (1 to 1),
            // A face goes left of page 1 by default: 0, and -1, -2, ... further left. A tile never does.
            page = props["page"]?.let { constInt(it) }?.let { if (isFace) it.coerceIn(-MAX_PAGE, MAX_PAGE) else it.coerceIn(1, MAX_PAGE) } ?: if (isFace) 0 else 1,
            order = props["order"]?.let { constInt(it) } ?: 0,
            padding = props["padding"]?.let { constant(it.value, "padding").num() } ?: 12.0,
            background = background,
            globals = globals.map { it!! },
            root = rootNodes,
            screen = screen?.first,
            screenTitle = screen?.second,
            onTap = onTap,
            triggers = triggers,
            commands = commands.toList(),
            secretHosts = secretHosts.mapValues { it.value.sorted() },
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
        for (p in body.props) if (p.name !in setOf("title", "icon", "back", "edge", "statusBar")) fail("an app only takes title, icon, back, edge and statusBar (no size or page: it isn't a tile)", p.pos)
        // Whether the status bar stays over the app, read as it changes, as on a face.
        val statusBar = body.props.firstOrNull { it.name == "statusBar" }?.let { since(APP_STATUS_BAR, it.pos); ex(it.value, root) }
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
        // Before the level check, which counts what they use.
        val title = body.props.firstOrNull { it.name == "title" }?.let { ex(it.value, root) }
        val back = backProp?.let { ex(it.value, root) }
        val onBack = onBackHandler?.let { handler(it, root) }
        checkLevel(level)
        return Program(
            name = ast.name ?: "app",
            meta = meta,
            level = level,
            globals = globals,
            screen = tree,
            screenTitle = title,
            back = back,
            onBack = onBack,
            edge = edge,
            statusBar = statusBar,
            triggers = triggers,
            commands = commands.toList(),
            secretHosts = secretHosts.mapValues { it.value.sorted() },
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

    /**
     * A panel: the file is a view of its own, which a swipe up (the app drawer) or down on home opens, picked in
     * gesture settings. The launcher draws the backdrop and moves it with the finger; the prompt bar stays under a
     * panel opened by swiping up, and `query` is its text. No title, icon or frame: its nodes are the whole of it.
     */
    private fun panel(
        body: Body,
        meta: Map<String, Value>,
        level: Int,
        requires: Map<String, List<String>>,
        globals: List<Global>,
        triggers: List<Trigger>,
    ): Program {
        since(PANEL_FILE, 0)
        body.props.firstOrNull { it.name != "enter" }?.let { fail("a panel only takes enter (what the prompt bar says Enter does); the launcher frames it, and gesture settings pick which swipe opens it", it.pos) }
        body.handlers.firstOrNull { it.name != "onSubmit" && it.name != "onKey" }?.let {
            fail("handlers go on the nodes inside a panel; only onSubmit (Enter in the prompt bar) and onKey (its arrow and Tab keys) go at its top", it.pos)
        }
        body.items.firstOrNull { it is Item.Node && it.type == "screen" }?.let { fail("a panel is a screen already: put its nodes at the top, without screen { }", it.pos) }
        nodes = 0
        maxNodes = MAX_SCREEN_NODES
        inScreen = true
        val tree = items(body.items, root, Children.UI)
        if (tree.isEmpty()) fail("a panel needs at least one node, like Text { text: \"hi\" }", 0)
        openScreen?.let { fail("screen() opens a widget's screen { }; a panel is open already, and close() closes it", it) }
        val onSubmit = body.handlers.firstOrNull { it.name == "onSubmit" }?.let { handler(it, root) }
        val onKey = body.handlers.firstOrNull { it.name == "onKey" }?.let { since("onKey", it.pos); handler(it, root) }
        // Before the level check, which counts what it uses.
        val enter = body.props.firstOrNull { it.name == "enter" }?.let { ex(it.value, root) }
        checkLevel(level)
        return Program(
            name = ast.name ?: "panel",
            meta = meta,
            level = level,
            globals = globals,
            screen = tree,
            onSubmit = onSubmit,
            onKey = onKey,
            enter = enter,
            triggers = triggers,
            commands = commands.toList(),
            secretHosts = secretHosts.mapValues { it.value.sorted() },
            warnings = warnings.toList(),
            requires = requires,
            needsLinux = guest || requires.isNotEmpty(),
            programs = scripts.flatMap(ShellWords::programs).toSortedSet(),
            animated = animated,
            terminal = terminal,
            nodeCount = nodes,
            kind = ast.kind,
        )
    }

    /**
     * The extra keys: the bar over the soft keyboard while typing in a terminal, as tall as its nodes. The launcher
     * puts it there, and keeps it from taking focus, so the keyboard stays up; `pressKey(...)` and `latch(...)` reach the
     * terminal.
     */
    private fun keybar(
        body: Body,
        meta: Map<String, Value>,
        level: Int,
        requires: Map<String, List<String>>,
        globals: List<Global>,
        triggers: List<Trigger>,
    ): Program {
        since(KEYBAR_FILE, 0)
        body.props.firstOrNull()?.let { fail("a key bar takes no properties; the launcher puts it over the keyboard, as tall as its nodes", it.pos) }
        body.handlers.firstOrNull()?.let { fail("handlers go on the keys inside, like onPress: pressKey(\"esc\")", it.pos) }
        body.items.firstOrNull { it is Item.Node && it.type == "screen" }?.let { fail("a key bar has no screen { }: its nodes are the bar", it.pos) }
        nodes = 0
        maxNodes = MAX_SCREEN_NODES
        inScreen = true
        val tree = items(body.items, root, Children.UI)
        if (tree.isEmpty()) fail("a key bar needs at least one node, like Button { text: \"esc\"; onPress: pressKey(\"esc\") }", 0)
        (screenAction ?: openScreen)?.let { fail("screen() and close() open and close a widget's screen { }; the key bar comes and goes with the keyboard", it) }
        checkLevel(level)
        return Program(
            name = ast.name ?: "keybar",
            meta = meta,
            level = level,
            globals = globals,
            screen = tree,
            triggers = triggers,
            commands = commands.toList(),
            secretHosts = secretHosts.mapValues { it.value.sorted() },
            warnings = warnings.toList(),
            requires = requires,
            needsLinux = guest || requires.isNotEmpty(),
            programs = scripts.flatMap(ShellWords::programs).toSortedSet(),
            animated = animated,
            terminal = terminal,
            nodeCount = nodes,
            kind = ast.kind,
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
        // Capped at each step: "${a ? "x" : "y"}" twenty times over would otherwise be millions of strings.
        fun capped(l: List<String>) = l.distinct().takeIf { it.size <= MAX_SHAPES }

        fun join(
            a: List<String>,
            b: List<String>,
        ) = capped(a.flatMap { x -> b.map { x + it } })
        val out =
            when (e) {
                is Expr.Interp -> e.parts.fold(listOf("")) { acc, p -> join(acc, shapes(p, s, value) ?: return null) ?: return null }
                is Expr.Binary -> if (e.op == "+") join(shapes(e.a, s, value) ?: return null, shapes(e.b, s, value) ?: return null) else null
                is Expr.Cond -> capped((shapes(e.a, s, value) ?: return null) + (shapes(e.b, s, value) ?: return null))
                is Expr.Call -> if (e.name == value) listOf(VALUE) else if (e.name in NUMBERS) listOf(NUMBER) else null
                // A top-level let that is fixed text: `let api = "https://..."` and then "${api}/x".
                is Expr.Name -> constText(e, s)?.let { listOf(it) }
                else -> null
            }
        return out?.let(::capped)
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

    /**
     * A `font` that isn't one of the words names a font file: an asset, a file in this system or a URL (owl 10).
     * One only known at run time, a state's or a source's, needs no level: widgets from before owl 10 pick a
     * word that way, and below 10 one that turns out to be a file draws in the launcher's font.
     */
    private fun font(
        e: Expr,
        s: Scope,
    ) {
        if (e is Expr.Cond) return listOf(e.a, e.b).forEach { font(it, s) }
        val words = ENUMS.getValue("font")
        if (e is Expr.Name && e.name in words) return
        val text = constText(e, s)
        if (text in words) return
        val asset = e is Expr.Name && globals.any { it is Global.Asset && it.name == e.name }
        if (text != null || asset || e is Expr.Interp) since(FONT_FILE, e.pos)
        webFile(e, s, "font")
    }

    /**
     * The fonts named in a Text's or a Canvas text's spans, `[{text: "a", font: "https://…"}]`: listed and level-checked
     * like a `font:`. They loaded without either, so a font from any server went unseen in the review.
     */
    private fun spanFonts(
        e: Expr,
        s: Scope,
    ) {
        when (e) {
            is Expr.ListLit -> e.items.forEach { spanFonts(it, s) }
            is Expr.MapLit -> e.entries.firstOrNull { it.first == "font" }?.let { font(it.second, s) }
            is Expr.Cond -> listOf(e.a, e.b).forEach { spanFonts(it, s) }
            else -> {}
        }
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
                request(e.args[0], root) {
                    val url = requestUrl("http GET", "http", e.args[0], root)
                    val headers = e.args.getOrNull(1)?.let { withSecrets { ex(it, root) } }
                    Global.Source(d.name, "http", url, (every ?: 60.0).coerceAtLeast(5.0), d.json, timeout.coerceIn(1, 300), d.status, headers)
                }
            }
            e is Expr.Call && e.name in setOf("cmd", "stream") -> {
                if (e.args.size != 1 || e.named.isNotEmpty()) fail("${e.name}(\"command\") takes one command", e.pos)
                val arg = whole { ex(e.args[0], root) }
                listCommand(e.args[0], arg, root, "${e.name}(…)", listed)
                if (!d.optional) guest = true
                Global.Source(d.name, e.name, arg, (every ?: 60.0).coerceAtLeast(1.0), d.json, timeout.coerceIn(1, 300), d.status, optional = d.optional)
            }
            e is Expr.Call && e.name == "file" -> {
                if (e.args.size != 1 || e.named.isNotEmpty()) fail("file(\"~/path\") takes one path", e.pos)
                if (d.every != null || d.timeout != null) fail("file(...) updates when the file changes; it takes no every or timeout", d.pos)
                val path = whole { ex(e.args[0], root) }
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
                if (e.name == "recent") commands += "read the recently opened apps"
                if (e.name == "drawer") commands += "read the launcher's apps (names and icons)"
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
        // `pacman: []` asks for nothing, so it mustn't make the widget need Linux.
        return out.filterValues { it.isNotEmpty() }
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
        if (d.first) since(ON_FIRST, d.pos)
        return Trigger(TriggerKind.of(d.kind), expr, handler(Handler(d.kind, d.stmts, d.pos), root, TRIGGER_PARAMS[d.kind].orEmpty()), d.pos, d.first)
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
        if (c.name == "text" && c.named.any { it.first in TEXT_STYLE }) since(CANVAS_TEXT_STYLE, c.pos)
        c.named.firstOrNull { it.first == "font" }?.let { font(it.second, scope) }
        if (c.name == "text") c.args.firstOrNull()?.let { spanFonts(it, scope) }
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
        if (++nodes > maxNodes) fail("more than $maxNodes nodes; ${if (maxNodes == MAX_NODES) "widgets" else "screens and faces"} should stay small", n.pos)
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
            if (p.name == "reverse" && body.props.none { it.name == "lazy" }) fail("reverse lists a lazy ${n.type} from its end: add lazy: true", p.pos)
            if (p.name == "background" && gradient(p.value)) since(GRADIENT_BACKGROUND, p.pos)
            if (p.name == "frost") since("frost", p.pos)
            if (p.name == "repeats") {
                since("repeats", p.pos)
                if (body.handlers.none { it.name == "onPress" }) fail("repeats runs onPress again while the finger stays down: add onPress", p.pos)
            }
            if (p.name == "font") font(p.value, scope)
            if (p.name == "spans") spanFonts(p.value, scope)
            props[p.name] = if (n.type == "Image" && p.name == "src") whole { prop(p, scope) } else prop(p, scope)
        }
        val handlers =
            body.handlers.associate { h ->
                if (h.name !in HANDLER_PARAMS) fail("unknown handler '${h.name}' (${HANDLER_PARAMS.keys.joinToString()})", h.pos)
                if (h.name == "onKey") fail("onKey goes at the top of a panel, next to onSubmit", h.pos)
                HANDLER_KINDS[h.name]?.let { kinds -> if (n.type !in kinds) fail("${h.name} only works on ${kinds.joinToString(" and ")}", h.pos) }
                if (h.name == "onLongPress" && inScreen == false && !isFace) {
                    fail("long-press on a tile belongs to the home screen, which lifts it to move and resize it; onLongPress works in faces, apps and a screen { }", h.pos)
                }
                since(h.name, h.pos)
                h.name to handler(h, scope)
            }
        val animations =
            body.animations.associate { a ->
                if (a.name !in ANIMATABLE) fail("'${a.name}' can't animate (${ANIMATABLE.joinToString()})", a.pos)
                a.name to ex(a.value, scope)
            }
        since(n.type, n.pos)
        // A tap outside or Back asks it to close; without a handler for that, nothing could.
        if (n.type == "Popup" && "onDismiss" !in handlers) fail("a Popup needs onDismiss, run when a tap outside it or Back closes it: onDismiss: open = false", n.pos)
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
        // A shader animates only if its code reads `time`; code that's worked out at run time might.
        if (n.type == "Shader") {
            val code = props["code"]?.const
            if (code == null || shaderReadsTime(code.toString())) animated = true
        }
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
        if (body.handlers.isNotEmpty() || body.animations.isNotEmpty()) fail("put handlers and animate inside the component, or wrap it: Box { onTap: …; ${n.type} { … } }", n.pos)
        val given = body.props.associateBy { it.name }
        for (p in body.props) if (p.name !in c.params && p.name !in COMMON) fail("${n.type} has no parameter '${p.name}' (${c.params.joinToString()})", p.pos)
        val args =
            c.params.mapIndexed { i, name ->
                given[name]?.let { ex(it.value, scope) } ?: c.defaults[i] ?: fail("${n.type} needs '$name'", n.pos)
            }
        val wrapper = body.props.filter { it.name !in c.params }.associate { it.name to prop(it, scope) }
        body.props.firstOrNull { it.name == "frost" && it.name !in c.params }?.let { since("frost", it.pos) }
        return WNode.Use(n.type, args, wrapper, c.body, n.pos)
    }

    /** Whether a background is written as a gradient, `linear(...)` or `radial(...)`, or chooses one: they need owl 10 there. */
    private fun gradient(e: Expr): Boolean =
        when (e) {
            is Expr.Call -> e.name == "linear" || e.name == "radial"
            is Expr.Cond -> gradient(e.a) || gradient(e.b)
            is Expr.Binary -> e.op == "??" && (gradient(e.a) || gradient(e.b))
            else -> false
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
        val run = chancy { block(h.stmts, inner) }
        return Action(params) { env, values ->
            val e = if (params.isEmpty()) env else env.child(*Array(params.size) { values.getOrElse(it) { Null } })
            // Handlers run from touch events: a failure is reported, never thrown into the UI.
            try {
                env.host.budget.run { run(e) }
            } catch (x: RuntimeException) {
                env.host.report("${h.name}: ${x.message ?: x}")
            }
        }
    }

    /** Handler statements, in order. A `let` opens a frame that the statements after it in its block see. */
    private fun block(
        stmts: List<Stmt>,
        scope: Scope,
    ): (Env) -> Unit {
        val steps = ArrayList<(Env) -> Env>()
        var s = scope
        for (st in stmts) {
            when (st) {
                is Stmt.Let -> {
                    since(HANDLER_LET, st.pos)
                    // One name, one meaning: a let named like a state would read as the let but assign the state.
                    if (st.name in slots || lookup(st.name, s) != null) fail("'${st.name}' is already a name here; give the let a name of its own", st.pos)
                    val v = ex(st.value, s)
                    s = Scope(s, listOf(st.name))
                    steps += { env -> env.child(v(env)) }
                }
                is Stmt.If -> {
                    since(HANDLER_IF, st.pos)
                    val cond = ex(st.cond, s)
                    val then = block(st.then, s)
                    val orElse = block(st.orElse, s)
                    steps += { env ->
                        if (cond(env).truthy) then(env) else orElse(env)
                        env
                    }
                }
                else -> {
                    val run = statement(st, s)
                    steps += { env ->
                        run(env)
                        env
                    }
                }
            }
        }
        return { env -> steps.fold(env) { e, step -> step(e) } }
    }

    /** An assignment or an action. */
    private fun statement(
        s: Stmt,
        scope: Scope,
    ): (Env) -> Unit {
        return when (s) {
            is Stmt.Assign -> {
                val slot = slots[s.name]
                // A state wins, even with a local of its name in reach (onChange: value = value): widgets did that before owl 9.
                if (s.name !in states && lookup(s.name, scope) is Ref.Local) fail("'${s.name}' is a let or a value given to the handler, not a state, so it can't be assigned", s.pos)
                if (slot == null || s.name !in states) {
                    fail(if (slot == null) "unknown state '${s.name}'; declare it with: state ${s.name} = ..." else "'${s.name}' isn't a state and can't be assigned", s.pos)
                }
                val call = s.value as? Expr.Call
                if (call?.name == "run") {
                    // result = run("..."): the output arrives later, when the command finishes.
                    if (call.args.size != 1 || call.named.isNotEmpty()) fail("run takes one command", call.pos)
                    val command = ex(call.args[0], scope)
                    listCommand(call.args[0], command, scope, "run(…)")
                    guest = true
                    val run: (Env) -> Unit = { env -> env.host.runInto(command(env).toString(), slot) }
                    run
                } else if (call?.name == "post") {
                    post(call, scope, slot)
                } else if (call?.name == "shortcuts") {
                    // s = shortcuts(key): they load off the main thread, and arrive in the state.
                    if (call.args.size != 1 || call.named.isNotEmpty()) fail("shortcuts takes an app's key", call.pos)
                    since("shortcuts", call.pos)
                    val key = ex(call.args[0], scope)
                    commands += "read an app's shortcuts (the actions its long-press menu offers)"
                    val run: (Env) -> Unit = { env -> env.host.shortcutsInto(key(env).toString(), slot) }
                    run
                } else {
                    val v = ex(s.value, scope)
                    val run: (Env) -> Unit = { env -> env.host.write(slot, v(env)) }
                    run
                }
            }
            is Stmt.Do -> {
                val call = s.call as Expr.Call
                if (call.name == "post") return post(call, scope, null)
                if (call.name == "shortcuts") fail("shortcuts(...) puts an app's shortcuts into a state, when they've loaded: menuShortcuts = shortcuts(a.key)", call.pos)
                val arity = Builtins.actions[call.name] ?: fail("'${call.name}' isn't an action (${Builtins.actions.keys.joinToString()})", call.pos)
                if (call.args.size !in arity) fail("${call.name} takes ${arity.first}-${arity.last} values", call.pos)
                val args = call.args.map { ex(it, scope) }
                ACTION_WORDS[call.name]?.let { w ->
                    val c = (args[0].const as? Str)?.v
                    if (c != null && c !in w.words && !(w.numbers && c.toDoubleOrNull() != null)) fail(w.message, call.pos)
                }
                since(call.name, call.pos)
                if (call.name == "sound") webFile(call.args[0], scope, "sound")
                if (call.name == "pressKey") {
                    (args[0].const as? Str)?.v?.let { if (KeyNames.combo(it) == null) fail(KeyNames.help, call.args[0].pos) }
                    commands += "press keys in the Linux window you're typing in: " + ((args[0].const as? Str)?.v ?: "keys built from widget values")
                }
                if (call.name == "typeText") commands += "type into the Linux window you're typing in: " + ((args[0].const as? Str)?.v ?: "text built from widget values")
                if (call.name == "latch") (args.getOrNull(1)?.const as? Str)?.v?.let { if (it !in KeyNames.hows) fail("latch's second value is ${KeyNames.hows.joinToString()}", call.args[1].pos) }
                if (call.name == "screen" || call.name == "close") screenAction = screenAction ?: call.pos
                if (call.name == "screen") openScreen = openScreen ?: call.pos
                // The reviewer sees what a widget would ask, as it sees a command.
                // Uninstalling and removing take apps away: the reviewer sees which entries a widget uses.
                if (call.name == "appAction") commands += "act on an app from the drawer's menu: " + ((args[0].const as? Str)?.v ?: "any of its entries")
                if (call.name == "ask") commands += (args[0].const as? Str)?.v?.let { "ask the agent: $it" } ?: "ask the agent something built from widget values"
                if (call.name in setOf("run", "terminal", "app")) {
                    listCommand(call.args[0], args[0], scope, "${call.name}(…)")
                    // A tap on terminal(...) or app(...) without Linux offers to install it; run(...) would fail unseen.
                    if (call.name == "run") guest = true
                }
                val run: (Env) -> Unit = { env -> env.host.act(call.name, args.map { it(env) }) }
                run
            }
            // block() takes the others.
            is Stmt.If, is Stmt.Let -> throw IllegalStateException("not a single statement")
        }
    }

    /**
     * `post(url, body, headers)`, or `x = post(...)` to get the answer into the state [slot]: an HTTP POST
     * (or `method:`'s PUT, PATCH or DELETE) from Android. Its URLs are listed like http(...)'s, as "http POST <url>".
     */
    private fun post(
        call: Expr.Call,
        scope: Scope,
        slot: Int?,
    ): (Env) -> Unit {
        if (call.args.size !in 1..3) fail("post(\"https://...\", body, headers) takes a URL, optionally a body and a map of headers", call.pos)
        for ((k, v) in call.named) if (k != "method") fail("post takes method: and no other named arguments", v.pos)
        since("post", call.pos)
        // A constant, so the reviewer reads what it does: "http DELETE https://…".
        val method =
            call.named.firstOrNull { it.first == "method" }?.second?.let { m ->
                since(POST_METHOD, m.pos)
                (constant(m, "method") as? Str)?.v?.uppercase()?.takeIf { it in POST_METHODS }
                    ?: fail("method: is one of ${POST_METHODS.joinToString { "\"$it\"" }}", m.pos)
            } ?: "POST"
        val (url, body, headers) =
            request(call.args[0], scope) {
                Triple(
                    requestUrl("http $method", "post", call.args[0], scope),
                    call.args.getOrNull(1)?.let { withSecrets { ex(it, scope) } },
                    call.args.getOrNull(2)?.let { withSecrets { ex(it, scope) } },
                )
            }
        return { env ->
            val target = url(env)
            // Like any action whose value hasn't loaded: a null URL sends nothing.
            if (target != Null) {
                val h = (headers?.invoke(env) as? Obj)?.fields.orEmpty().mapValues { it.value.toString() }
                env.host.post(target.toString(), body?.invoke(env) ?: Null, h, slot, method)
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
        // Every value is encoded: so either the server comes from one, or the URL has too many ways to turn out to list.
        fun fixed(p: Expr): Boolean = if (p is Expr.Cond) fixed(p.a) && fixed(p.b) else ex(p, s).const == Null || server(p, s) != null
        if (fixed(e)) {
            return "this URL can turn out more than $MAX_SHAPES ways, more than the store's review lists: write fewer ternaries into it, " +
                "or pass the parts that vary through enc() as one value" to e.pos
        }
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

    private fun isSecret(
        e: Expr,
        s: Scope,
    ) = e is Expr.Name && e.name in secrets && lookup(e.name, s) is Ref.Slot

    private fun isUnset(e: Expr) = e is Expr.Lit && (e.value == Null || e.value == Str(""))

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
                val literal = BooleanArray(e.parts.size) { e.parts[it].let { p -> p is Expr.Lit && p.value is Str } }
                val whole = wholeOnly
                fold(parts) { env ->
                    val sb = StringBuilder()
                    for (i in parts.indices) {
                        val v = parts[i](env)
                        if (whole && v == Null) return@fold Null
                        sb.append(if (literal[i]) v.toString() else v.text())
                        checkText(sb.length)
                    }
                    Str(sb.toString())
                }
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
                // `token == null` says whether it's set, which a widget shows (sign in, or the data).
                val check = (e.op == "==" || e.op == "!=") && (isSecret(e.a, s) && isUnset(e.b) || isSecret(e.b, s) && isUnset(e.a))
                val (a, b) = if (check) checking { ex(e.a, s) to ex(e.b, s) } else ex(e.a, s) to ex(e.b, s)
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
            is Ref.Slot -> {
                // Never shown, run or copied: on screen or in Linux, it would be out.
                if (e.name in secrets) sent?.add(e.name)
                if (e.name in secrets && !secretOk) {
                    fail(
                        "${e.name} is a secret: it goes only into http(...)'s and post(...)'s headers and body, written out in them, not through a let " +
                            "({Authorization: \"Bearer \${${e.name}}\"}), into their URL as enc(${e.name}), or into ${e.name} == null, != null or != \"\"",
                        e.pos,
                    )
                }
                return Ev(null) { env -> env.host.read(ref.index) }
            }
            null -> {}
        }
        Builtins.constants[e.name]?.let { return Ev.of(it) }
        if (e.name in Builtins.theme) return Ev.of(Col(e.name, 0xff888888.toInt()))
        if (e.name == "time") {
            animated = true
            return Ev(null, { env -> Num(env.host.time()) }, Fast { env -> env.host.time() })
        }
        // A widget's screen always ends above the navigation bar; only an app with `edge` runs under it.
        if (e.name == "screenInset" && ast.kind != OwlKind.App && !(isFace && inScreen != true)) {
            fail("screenInset is for apps with edge: true and faces; a widget's screen ends above the navigation bar", e.pos)
        }
        // Only an app runs up to the top edge, with statusBar: false; a face and a widget's screen stay clear of the cutout.
        if (e.name == "screenTop" && ast.kind != OwlKind.App) {
            fail("screenTop is for apps with statusBar: false; a face and a widget's screen start below the camera", e.pos)
        }
        // Not reserved: widgets that already declare `rows` or `cols` keep their own.
        if (e.name in TILE || e.name == LINUX) {
            since(e.name, e.pos)
            return Ev(null) { env -> env.host.tile(e.name) }
        }
        if (e.name == QUERY) {
            if (ast.kind != OwlKind.Panel) fail("query is the prompt bar's text, which only a panel has", e.pos)
            since(QUERY, e.pos)
            return Ev(null) { env -> env.host.tile(QUERY) }
        }
        ENUMS.entries.firstOrNull { e.name in it.value }?.let { (prop, _) ->
            fail("'${e.name}' isn't a value here: a word like it stands alone, as in $prop: ${e.name}; in an expression, quote it: x ? \"${e.name}\" : …", e.pos)
        }
        fail("unknown name '${e.name}'" + (letHint(e.name, s)?.let { ": $it" } ?: ""), e.pos)
    }

    /** Why a let by this name can't be read from here, when there is one. */
    private fun letHint(
        name: String,
        scope: Scope,
    ): String? {
        val below = "it's declared below; a let reads only the lets above it, so move it up"
        var f: Scope? = scope
        while (f != null) {
            if (name in f.names.subList(f.visible, f.names.size)) return below
            f = f.parent
        }
        if (ast.body.decls.any { it is Decl.Let && it.name == name }) return below
        if (name in nodeLets) {
            return "it's a let inside a node, which only the nodes inside that one see, not the node's own properties or anything outside it; put the let on the parent"
        }
        return null
    }

    /** Every let written inside a node, anywhere in the file, for [letHint]. */
    private val nodeLets: Set<String> by lazy {
        val names = HashSet<String>()

        fun walk(b: Body?) {
            if (b == null) return
            for (i in b.items) {
                when (i) {
                    is Item.Let -> names += i.name
                    is Item.Node -> walk(i.body)
                    is Item.If -> walk(i.then).also { walk(i.orElse) }
                    is Item.For -> walk(i.body)
                    is Item.Call -> walk(i.body)
                }
            }
            for (d in b.decls) if (d is Decl.Component) walk(d.body)
        }
        walk(ast.body)
        names
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
        val b =
            Builtins.all[e.name] ?: run {
                val isLet = ast.body.decls.any { it is Decl.Let && it.name == e.name } || e.name in nodeLets || lookup(e.name, s) is Ref.Local
                val hint = if (isLet) ": '${e.name}' is a let, and a let can't be called; write the expression out where it's used, or make a component for nodes you repeat" else ""
                fail("unknown function '${e.name}'$hint", e.pos)
            }
        since(e.name, e.pos)
        if (e.name == "fmtTime" && e.args.size > 2) since(FMT_TIME_ZONE, e.pos)
        if (e.name == "flatten" && e.args.size > 1) since(FLATTEN_DEPTH, e.pos)
        if ((e.name == "random" || e.name == "shuffle" && e.args.size == 1) && !chance) {
            fail(
                if (e.name == "random") {
                    "random() gives a new number each time it's read; set a state with it in a handler, like onTap: roll = random(1, 6), or use hash(seed, i) in a let"
                } else {
                    "shuffle(list) shuffles anew each time it's read; give it a seed, shuffle(list, seed), with the seed a state set to random(1000000) in a handler"
                },
                e.pos,
            )
        }
        if (e.named.isNotEmpty()) fail("${e.name} takes no named arguments", e.named[0].second.pos)
        if (e.args.size < b.min || e.args.size > b.max) {
            fail("${e.name} takes ${if (b.min == b.max) "${b.min}" else "${b.min}-${b.max}"} arguments, not ${e.args.size}", e.pos)
        }
        // A secret in a request's URL, as enc(token): see [requestUrl].
        val urlSecret = (e.args.singleOrNull() as? Expr.Name)?.takeIf { e.name == "enc" && inUrl && isSecret(it, s) }
        val args = if (urlSecret != null) listOf(withSecrets { ex(urlSecret, s) }) else e.args.map { ex(it, s) }
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
