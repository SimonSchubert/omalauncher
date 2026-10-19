package dev.omalauncher.widgets.lang

/**
 * What a running widget provides to compiled code: its global slots (sources,
 * state, lets), theme colors, the animation clock and actions. Implemented by
 * the runtime; tests use a plain map.
 */
interface Host {
    fun read(slot: Int): Value

    fun write(
        slot: Int,
        value: Value,
    )

    /** ARGB of a theme color, for built-ins that need real colors (mix, alpha). */
    fun theme(name: String): Int?

    /** Seconds since the widget started animating; only drawing code should read this. */
    fun time(): Double

    /**
     * The tile's size: cols and rows in grid cells, tileWidth and tileHeight in dp; null until it's laid out.
     * Also screenWidth and screenHeight (its screen's size), screenInset (how much of its bottom the
     * navigation bar covers, for an app with `edge` and a face, or the keyboard while a face's field has it), linux: whether Linux is installed and running,
     * and corners: the launcher's corner style as a radius scale, 0 (edge), 0.5 (soft) or 1 (round).
     */
    fun tile(name: String): Value = Null

    fun act(
        name: String,
        args: List<Value>,
    )

    /** `x = run("...")` in a handler: runs [command] and writes its output to [slot] when it finishes. */
    fun runInto(
        command: String,
        slot: Int,
    ) {}

    /**
     * `post(url, body, headers)` in a handler: an HTTP POST, with a map or list [body] sent as JSON. With a
     * [slot] (`x = post(...)`), the answer goes there as `{code, ok, out}` when it arrives.
     */
    fun post(
        url: String,
        body: Value,
        headers: Map<String, String>,
        slot: Int?,
    ) {}

    val budget: Budget

    /** A problem at run time, shown to the widget's author; never thrown. */
    fun report(message: String) {}
}

/** Caps the work of one evaluation, so a widget can't freeze the launcher with nested maps over big lists. */
class Budget(
    private val limit: Int = 200_000,
) {
    private var steps = 0
    private var depth = 0

    fun <T> run(block: () -> T): T {
        if (depth++ == 0) steps = 0
        try {
            return block()
        } finally {
            depth--
        }
    }

    fun tick(n: Int = 1) {
        steps += n
        if (steps > limit) throw WidgetError("too much work in one update (over $limit steps)")
    }
}

/** A frame of local variables; lookups are resolved to (depth, index) at compile time. */
class Env(
    val host: Host,
    val parent: Env?,
    /**
     * Each slot is a [Value]; or a `() -> Value`, called on every read (in
     * composition, where it reads derived states); or an [Ev] evaluated in
     * this frame on first read and kept, like a Canvas's lets once per draw.
     */
    val slots: Array<out Any?>,
) {
    fun local(
        depth: Int,
        index: Int,
    ): Value {
        var e = this
        repeat(depth) { e = e.parent!! }
        return when (val s = e.slots[index]) {
            is Value -> s
            // Only frames from lets() hold an Ev, and their array is an Array<Any?>.
            is Ev -> s(e).also { @Suppress("UNCHECKED_CAST") (e.slots as Array<Any?>)[index] = it }
            else -> @Suppress("UNCHECKED_CAST") (s as () -> Value)()
        }
    }

    fun child(vararg values: Value) = Env(host, this, values)

    fun childLazy(slots: Array<() -> Value>) = Env(host, this, slots)

    /** A frame of [values], each evaluated in it on first read and then kept; see [slots]. */
    fun lets(values: List<Ev>) = Env(host, this, Array<Any?>(values.size) { values[it] })

    companion object {
        private val NONE = emptyArray<Any?>()

        fun root(host: Host) = Env(host, null, NONE)
    }
}

/** A compiled expression. [const] is set when it doesn't depend on anything, so the renderer can skip tracking it. */
class Ev internal constructor(
    val const: Value?,
    private val f: (Env) -> Value,
    /**
     * Arithmetic on plain doubles, without a Num for every step. Gives the
     * number [invoke] would, or NaN: an input wasn't a number (null, text, a
     * list) or the result is NaN, and the caller evaluates the general way
     * instead. NaN rather than an exception, which would cost more than the
     * boxing saved in a Canvas that draws a value still loading every frame.
     */
    internal val fast: Fast?,
) {
    constructor(const: Value?, f: (Env) -> Value) : this(const, f, null)

    operator fun invoke(env: Env): Value = const ?: f(env)

    /** The value as a number, as [Value.num] gives it, or [default]; arithmetic doesn't box on the way. */
    fun num(
        env: Env,
        default: Double,
    ): Double {
        if (fast != null) {
            val d = fast.of(env)
            if (!d.isNaN()) return d
        }
        val v = invoke(env)
        return if (v is Num) v.v else v.num() ?: default
    }

    companion object {
        fun of(v: Value) = Ev(v, { v }, (v as? Num)?.let { n -> Fast { n.v } })
    }
}

/**
 * A compiled expression's numeric form; see [Ev.fast]. NaN in gives NaN out,
 * so "not a number" can't turn into one. Not a (Env) -> Double, which would
 * box its result.
 */
internal fun interface Fast {
    fun of(env: Env): Double
}

/** A compiled handler; [params] are the names the event binds (x, y, dx, dy). */
class Action(
    val params: List<String>,
    val run: (Env, List<Value>) -> Unit,
)

/** The widget tree after compilation. */
sealed interface WNode {
    val pos: Int

    class Element(
        val kind: String,
        val props: Map<String, Ev>,
        val handlers: Map<String, Action>,
        val animations: Map<String, Ev>,
        val children: List<WNode>,
        override val pos: Int,
    ) : WNode

    class If(
        val cond: Ev,
        val then: List<WNode>,
        val orElse: List<WNode>,
        override val pos: Int,
    ) : WNode

    /** Each element gets a frame of [item, index]. */
    class For(
        val list: Ev,
        val key: Ev?,
        val body: List<WNode>,
        override val pos: Int,
    ) : WNode

    /** Local `let`s: one frame for [children], each slot evaluated on read. */
    class Lets(
        val values: List<Ev>,
        val children: List<WNode>,
        override val pos: Int,
    ) : WNode

    /** A component instance: a frame of its parameters (evaluated in the caller) around its body. */
    class Use(
        val component: String,
        val args: List<Ev>,
        val wrapper: Map<String, Ev>,
        val body: List<WNode>,
        override val pos: Int,
    ) : WNode

    /** A Canvas drawing call; [children] for transforms like rotate(...) { }. */
    class Draw(
        val op: String,
        val args: List<Ev>,
        val named: Map<String, Ev>,
        val children: List<WNode>?,
        override val pos: Int,
    ) : WNode
}

sealed interface Global {
    val name: String

    class Source(
        override val name: String,
        val kind: String,
        val arg: Ev?,
        val every: Double,
        val json: Boolean,
        val timeout: Int,
        /** cmd(...) status: {out, err, code, ok}, and failures aren't reported. */
        val status: Boolean = false,
        /** http(url, headers): the request headers, a map. */
        val headers: Ev? = null,
        /** cmd, stream or file that runs only while Linux is installed, and stays null until then. */
        val optional: Boolean = false,
    ) : Global

    class State(
        override val name: String,
        val init: Ev,
        /** Saved across restarts. */
        val persist: Boolean = false,
    ) : Global

    class Let(
        override val name: String,
        val value: Ev,
    ) : Global

    /** history(x, n) / prev(x): sampled each time a source updates. */
    class Sampled(
        override val name: String,
        val of: Ev,
        val keep: Int,
        /** prev(x): the sample before the latest, instead of the list. */
        val prev: Boolean,
        /** history(...) persist: saved across restarts. */
        val persist: Boolean = false,
    ) : Global

    class Asset(
        override val name: String,
        val bytes: ByteArray,
    ) : Global
}

/**
 * Statements that run without a touch: `every` on an interval while the page
 * is live, `at` when the clock reaches a Unix time, `when` each time a
 * condition turns true, `on` each time a value changes (with `value` and `old`).
 */
/** What a trigger reacts to, by the word that starts it. */
enum class TriggerKind(
    val word: String,
) {
    EVERY("every"),
    AT("at"),
    WHEN("when"),
    ON("on"),
    ;

    companion object {
        fun of(word: String) = entries.first { it.word == word }
    }
}

class Trigger(
    val kind: TriggerKind,
    /** The condition, time or value; for `every`, the interval in seconds. */
    val expr: Ev,
    val action: Action,
    val pos: Int,
    /** `on x first`: also for x's first value, with `old` null. */
    val first: Boolean = false,
)

class Program(
    val name: String,
    val meta: Map<String, Value>,
    /** The owl language version it needs; see [Compiler.LEVEL]. */
    val level: Int,
    /** The tile's: an app has none of these. */
    val size: Pair<Int, Int>? = null,
    val minSize: Pair<Int, Int> = 1 to 1,
    /** A face (`face "…" { }`): it is its home page, the whole screen with no frame, and nothing else goes on it. */
    val isFace: Boolean = false,
    /** A face's `statusBar`: while false, the status bar hides as the face shows; null keeps it. */
    val statusBar: Ev? = null,
    val page: Int = 1,
    val order: Int = 0,
    /** Tile padding in dp, and an optional tile background. */
    val padding: Double = 12.0,
    val background: Ev? = null,
    val globals: List<Global>,
    val root: List<WNode> = emptyList(),
    /** The screen { } block: a full-screen view over the same globals, opened with screen(). */
    val screen: List<WNode>? = null,
    val screenTitle: Ev? = null,
    /** An app's `back`: while true, the frame shows a back chevron and Back runs [onBack] instead of closing it. */
    val back: Ev? = null,
    val onBack: Action? = null,
    /** An app's `edge`: its view runs on under the navigation bar, which `screenInset` measures. */
    val edge: Boolean = false,
    val onTap: Action? = null,
    val triggers: List<Trigger>,
    /** Shell commands this widget can run, known without running it; for reviewing shared widgets. */
    val commands: List<String>,
    /** Declared dependencies: package manager -> package names, e.g. pacman -> [jq, curl]. */
    val requires: Map<String, List<String>>,
    /**
     * Runs commands in the guest or declares packages, so it can't work without Linux.
     * `optional` sources and terminal(...) or app(...) taps don't count: without Linux
     * the sources wait and a tap offers to install it.
     */
    val needsLinux: Boolean,
    /** Programs the widget's fixed commands appear to run (a heuristic), to catch undeclared dependencies. */
    val programs: Set<String>,
    /** Reads `time`, so it needs a frame clock while visible. */
    val animated: Boolean,
    /** Has a Terminal node. */
    val terminal: Boolean = false,
    val nodeCount: Int,
    /** A tile or an app; for an app [screen] is the whole view and [root] is empty. */
    val kind: OwlKind = OwlKind.Widget,
    /** An app's icon for the drawer and home, when `icon:` holds an SVG. */
    val icon: String? = null,
    /** An app's icon, when `icon:` names an `asset` (a PNG or other image) instead. */
    val iconImage: ByteArray? = null,
    /** What compiles but is likely wrong, one line each with its line:col, for owl-check: a URL value not put through enc(). */
    val warnings: List<String> = emptyList(),
)
