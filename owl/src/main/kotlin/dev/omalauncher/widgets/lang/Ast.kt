package dev.omalauncher.widgets.lang

sealed interface Expr {
    val pos: Int

    data class Lit(
        val value: Value,
        override val pos: Int,
    ) : Expr

    data class Name(
        val name: String,
        override val pos: Int,
    ) : Expr

    /** "a ${b} c": literal pieces are [Lit]s. */
    data class Interp(
        val parts: List<Expr>,
        override val pos: Int,
    ) : Expr

    data class ListLit(
        val items: List<Expr>,
        override val pos: Int,
    ) : Expr

    data class MapLit(
        val entries: List<Pair<String, Expr>>,
        override val pos: Int,
    ) : Expr

    data class Unary(
        val op: String,
        val e: Expr,
        override val pos: Int,
    ) : Expr

    data class Binary(
        val op: String,
        val a: Expr,
        val b: Expr,
        override val pos: Int,
    ) : Expr

    data class Cond(
        val c: Expr,
        val a: Expr,
        val b: Expr,
        override val pos: Int,
    ) : Expr

    data class Member(
        val e: Expr,
        val name: String,
        override val pos: Int,
    ) : Expr

    data class Index(
        val e: Expr,
        val index: Expr,
        override val pos: Int,
    ) : Expr

    /** `f(a, b, k: v)`; `x.f(a)` is sugar for `f(x, a)`. */
    data class Call(
        val name: String,
        val args: List<Expr>,
        val named: List<Pair<String, Expr>>,
        override val pos: Int,
    ) : Expr

    data class Lambda(
        val params: List<String>,
        val body: Expr,
        override val pos: Int,
    ) : Expr
}

sealed interface Stmt {
    val pos: Int

    data class Assign(
        val name: String,
        val value: Expr,
        override val pos: Int,
    ) : Stmt

    data class Do(
        val call: Expr,
        override val pos: Int,
    ) : Stmt

    /** `if cond { ... } else { ... }` in a handler (owl 9); `else if` is an If alone in [orElse]. */
    data class If(
        val cond: Expr,
        val then: List<Stmt>,
        val orElse: List<Stmt>,
        override val pos: Int,
    ) : Stmt

    /** `let x = ...` in a handler (owl 9): a name for the statements after it, in its block. */
    data class Let(
        val name: String,
        val value: Expr,
        override val pos: Int,
    ) : Stmt
}

/** Everything between a node's braces. */
class Body(
    val props: List<Prop>,
    val handlers: List<Handler>,
    val animations: List<Prop>,
    val items: List<Item>,
    val decls: List<Decl>,
)

class Prop(
    val name: String,
    val value: Expr,
    val pos: Int,
)

class Handler(
    val name: String,
    val stmts: List<Stmt>,
    val pos: Int,
)

sealed interface Item {
    val pos: Int

    class Node(
        val type: String,
        val body: Body,
        override val pos: Int,
    ) : Item

    class If(
        val cond: Expr,
        val then: Body,
        val orElse: Body?,
        override val pos: Int,
    ) : Item

    class For(
        val name: String,
        val index: String?,
        val list: Expr,
        val key: Expr?,
        val body: Body,
        override val pos: Int,
    ) : Item

    /** A drawing call inside a Canvas: `circle(...)`, or `rotate(...) { ... }` with a body. */
    class Call(
        val call: Expr.Call,
        val body: Body?,
        override val pos: Int,
    ) : Item

    class Let(
        val name: String,
        val value: Expr,
        override val pos: Int,
    ) : Item
}

/** Declarations only allowed at the top of a widget. */
sealed interface Decl {
    val name: String
    val pos: Int

    class Source(
        override val name: String,
        val expr: Expr,
        val every: Expr?,
        val json: Boolean,
        val timeout: Expr?,
        override val pos: Int,
        /** `status`: the value is {out, err, code, ok} instead of just the output. */
        val status: Boolean = false,
        /** `optional`: a guest source that waits for Linux instead of making the widget need it. */
        val optional: Boolean = false,
    ) : Decl

    class State(
        override val name: String,
        val init: Expr,
        override val pos: Int,
        /** `persist`: the value survives restarts. */
        val persist: Boolean = false,
    ) : Decl

    class Let(
        override val name: String,
        val value: Expr,
        override val pos: Int,
        /** `persist`, for history(...): the samples survive restarts. */
        val persist: Boolean = false,
    ) : Decl

    class Asset(
        override val name: String,
        val base64: String,
        override val pos: Int,
    ) : Decl

    class Component(
        override val name: String,
        val params: List<Pair<String, Expr?>>,
        val body: Body,
        override val pos: Int,
    ) : Decl

    class Meta(
        val fields: List<Prop>,
        override val pos: Int,
    ) : Decl {
        override val name get() = "meta"
    }

    /**
     * Statements that run on their own instead of on a touch (owl 4):
     * `every 10s { }`, `at endsAt { }`, `when cond { }`, `on value { }`, and `on value first { }`
     * to run for its first value too (owl 9).
     */
    class Trigger(
        val kind: String,
        val expr: Expr,
        val stmts: List<Stmt>,
        override val pos: Int,
        val first: Boolean = false,
    ) : Decl {
        override val name get() = kind
    }

    /** `requires { pacman: ["jq"] }`: packages per package manager. */
    class Requires(
        val fields: List<Prop>,
        override val pos: Int,
    ) : Decl {
        override val name get() = "requires"
    }
}

class WidgetAst(
    val name: String?,
    val body: Body,
    /** What the file is, from its first word: widget, face or app; see [OwlKind]. */
    val kind: OwlKind = OwlKind.Widget,
)

/** What an owl file is: a home-screen tile, a face (a whole home page), or an app in the drawer. */
enum class OwlKind(
    val word: String,
) {
    Widget("widget"),
    Face("face"),
    App("app"),
    ;

    companion object {
        private val FIRST_WORD = Regex("""^(?:\s+|//[^\n]*|/\*[\s\S]*?\*/)*([A-Za-z_]\w*)""")

        /** What [src] says it is by its first word, without compiling it: a file with errors still shows up where it belongs. */
        fun of(src: String): OwlKind = FIRST_WORD.find(src)?.groupValues?.get(1)?.let { w -> entries.firstOrNull { it.word == w } } ?: Widget
    }
}
