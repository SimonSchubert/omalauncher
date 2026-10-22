package dev.omalauncher.widgets.lang

/*
 * What the language has: nodes and their properties, drawing calls, handlers,
 * sources and actions, and the owl version each arrived in. [Compiler] checks
 * widgets against these tables.
 */

internal enum class Children { NONE, UI, DRAW }

internal class Kind(
    val props: Set<String>,
    val children: Children,
)

internal class DrawOp(
    val arity: IntRange,
    val group: Boolean = false,
)

/** Nodes: their own properties, and what they may contain. */
internal val KINDS: Map<String, Kind> =
    mapOf(
        "Column" to Kind(setOf("gap", "align", "justify", "scroll", "lazy", "reverse"), Children.UI),
        "Row" to Kind(setOf("gap", "align", "justify", "wrap"), Children.UI),
        "Flow" to Kind(setOf("gap", "align", "justify"), Children.UI),
        "Box" to Kind(setOf("align"), Children.UI),
        "Grid" to Kind(setOf("columns", "gap", "lazy", "reverse"), Children.UI),
        "Text" to Kind(setOf("text", "style", "color", "fontSize", "bold", "italic", "font", "align", "maxLines", "minLines", "spans", "fontWeight", "letterSpacing"), Children.NONE),
        "Image" to Kind(setOf("src", "fit", "tint", "label"), Children.NONE),
        "Button" to Kind(setOf("text", "color"), Children.NONE),
        "Progress" to Kind(setOf("value", "max", "color", "track"), Children.NONE),
        "Ring" to Kind(setOf("value", "max", "color", "track", "thickness", "start", "sweep"), Children.NONE),
        "Sparkline" to Kind(setOf("values", "min", "max", "color", "area", "thickness"), Children.NONE),
        "Canvas" to Kind(emptySet(), Children.DRAW),
        "Shader" to Kind(setOf("code", "uniforms", "fallback"), Children.NONE),
        "Spacer" to Kind(emptySet(), Children.NONE),
        // owl 2: a live terminal (foot) inside the tile.
        "Terminal" to Kind(setOf("command", "fontSize"), Children.NONE),
        // owl 4: input.
        "Slider" to Kind(setOf("value", "min", "max", "step", "color", "track", "live"), Children.NONE),
        "Input" to Kind(setOf("text", "placeholder", "color", "fontSize", "clear", "focus", "underline", "password"), Children.NONE),
        // owl 10: a menu over everything else, next to the node that holds it.
        "Popup" to Kind(setOf("position", "gap", "align"), Children.UI),
    )

internal val COMMON =
    setOf(
        "width", "height", "size", "fill", "weight", "padding", "background", "border", "borderColor",
        "radius", "opacity", "rotation", "scale", "offset", "clip", "visible", "frost", "repeats",
    )

internal val ANIMATABLE = setOf("opacity", "rotation", "scale", "value", "color", "background")

internal val ALIGN = setOf("start", "center", "end", "top", "bottom", "topStart", "topEnd", "bottomStart", "bottomEnd")
internal val ENUMS =
    mapOf(
        "style" to setOf("huge", "title", "body", "small", "muted", "label"),
        "align" to ALIGN,
        "justify" to setOf("start", "center", "end", "spaceBetween", "spaceAround", "spaceEvenly"),
        "fit" to setOf("contain", "cover", "fill"),
        "font" to setOf("mono", "sans", "serif", "icon"),
        "fill" to setOf("width", "height"),
        "cap" to setOf("round", "butt", "square"),
        "position" to setOf("above", "below"),
    )

/** text(...)'s options beyond size, color, align and bold, as Text has them (owl 10). */
internal val TEXT_STYLE = setOf("font", "fontWeight", "italic", "letterSpacing")

/** Canvas calls: positional arity, and whether they take a { } body of further calls. */
internal val DRAW: Map<String, DrawOp> =
    mapOf(
        "rect" to DrawOp(4..4),
        "circle" to DrawOp(3..3),
        "arc" to DrawOp(5..5),
        "line" to DrawOp(4..4),
        "path" to DrawOp(1..1),
        "text" to DrawOp(3..3),
        "image" to DrawOp(5..5),
        "translate" to DrawOp(2..2, group = true),
        "rotate" to DrawOp(1..3, group = true),
        "scale" to DrawOp(1..4, group = true),
        "clip" to DrawOp(4..4, group = true),
        "layer" to DrawOp(1..1, group = true),
        // owl 3: pixel art from rows of letters, one color per letter.
        "sprite" to DrawOp(4..4),
    )
internal val DRAW_NAMED = setOf("fill", "stroke", "width", "cap", "radius", "closed", "size", "color", "align", "bold", "palette") + TEXT_STYLE

/** What needs a newer language than 1, with the version: using one without `meta { owl: N }` is an error. */
internal val SINCE =
    mapOf("Terminal" to 2, "sprite" to 3) +
        listOf("Slider", "Input", "file", "android.notifications", "android.volume", "volume", "notification", "every", "at", "when", "on").associateWith { 4 } +
        // owl 5: a full-screen view of the widget.
        listOf("screen", "close", "screenWidth", "screenHeight", "optional", "linux", "android.system").associateWith { 5 } +
        // owl 6: properties, named Node.property; values in commands, quoted; the launcher's corner style.
        listOf("Input.focus", "sh", "corners").associateWith { 6 } +
        // owl 7: more of the phone, without new permissions.
        listOf("android.alarm", "android.modes", "torch", "share", "haptic").associateWith { 7 } +
        // owl 8: apps, files that aren't tiles; the phone's apps as a list; images and sounds from the web; values in URLs.
        listOf("app \"…\" { }", "android.apps", WEB_IMAGE, "sound", "enc", "post", "screenInset").associateWith { 8 } +
        // owl 9: dates and numbers as text and back; chance.
        listOf("parseTime", "fmtAgo", "fmtNum", FMT_TIME_ZONE, "random", "shuffle", "hash").associateWith { 9 } +
        // owl 9: lists and maps changed in place of rebuilding them; a trigger for a first value.
        listOf("set", "remove", "merge", "reduce", "scan", "groupBy", "toJson", "capitalize", "asin", "acos", FLATTEN_DEPTH, ON_FIRST).associateWith { 9 } +
        // owl 9: handlers that choose, and name values on the way.
        listOf(HANDLER_IF, HANDLER_LET).associateWith { 9 } +
        // owl 10: faces, files that are a whole home page; long-press where it isn't the home screen's.
        mapOf(FACE_FILE to 10, "onLongPress" to 10) +
        // owl 10: text styled in pieces, by weight and spacing; gradients and frosted glass behind any node; Text in a Row as wide as its text.
        listOf("Text.spans", "Text.fontWeight", "Text.letterSpacing", GRADIENT_BACKGROUND, "frost").associateWith { 10 } +
        // owl 10: the coding agents in Linux and the recent strip's apps, for a face with its own prompt bar.
        listOf("android.agents", "android.recent", "open", "ask", "Popup", "Input.underline").associateWith { 10 } +
        // owl 10: fonts of the widget's own, and Canvas text styled like a Text.
        listOf(FONT_FILE, CANVAS_TEXT_STYLE).associateWith { 10 } +
        // owl 11: panels, files a swipe on home opens (the app drawer is one), with the prompt bar's text; the drawer's
        // apps and what their long-press menu does; long lists built as they scroll into view; the bar's search ranking;
        // an app that hides the status bar.
        listOf(PANEL_FILE, QUERY, "onKey", "android.drawer", "appAction", "workApps", "shortcuts", APP_STATUS_BAR).associateWith { 11 } +
        listOf("Column.lazy", "Grid.lazy", "Column.reverse", "Grid.reverse", "Image.label", "Text.minLines", "rank", "strongMatch", "chunk").associateWith { 11 } +
        // owl 12: how much of an app's top a camera cutout covers, once it hides the status bar.
        mapOf("screenTop" to 12) +
        // owl 12: the extra keys over the soft keyboard as a file; keys for the focused Linux window; touches as they go down.
        listOf(KEYBAR_FILE, "pressKey", "typeText", "latch", "android.modifiers", "onPress", "onRelease", "repeats").associateWith { 12 } +
        // owl 12: the launcher's gaps and border width, the extra keys' own in a keybar.
        listOf("gapsIn", "gapsOut", "borderWidth").associateWith { 12 } +
        // owl 12: requests other than POST; base64 text.
        listOf(POST_METHOD, "toBase64", "fromBase64").associateWith { 12 } +
        // owl 12: tokens kept encrypted, which only go into requests, and fields that hide what's typed.
        listOf(SECRET, "Input.password").associateWith { 12 }

/** `state x = null secret`, named like this in [SINCE]. */
internal const val SECRET = "secret"

/** `method:` on post(...), named like this in [SINCE]. */
internal const val POST_METHOD = "method:"

/** The methods post(...) sends. */
internal val POST_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

internal const val FACE_FILE = "face \"…\" { }"

internal const val PANEL_FILE = "panel \"…\" { }"

internal const val KEYBAR_FILE = "keybar \"…\" { }"

/** A panel's read-only name for the prompt bar's text. */
internal const val QUERY = "query"

internal const val GRADIENT_BACKGROUND = "a gradient background"

internal const val APP_STATUS_BAR = "an app's statusBar"

internal const val FONT_FILE = "a font of its own"

internal const val CANVAS_TEXT_STYLE = "text(...) with font, fontWeight, italic or letterSpacing"

internal const val WEB_IMAGE = "an image from the web"

internal const val FMT_TIME_ZONE = "fmtTime with a time zone or language"

internal const val FLATTEN_DEPTH = "flatten with a depth"

internal const val ON_FIRST = "on … first"

internal const val HANDLER_IF = "if in a handler"

internal const val HANDLER_LET = "let in a handler"

/**
 * Package managers a widget can declare in `requires`, with what a
 * package name may look like. Names end up in a shell command, so the
 * pattern is strict.
 */
internal val MANAGERS = mapOf("pacman" to Regex("[a-z0-9@_+][a-z0-9@._+-]*"))

// onLongPress only off the home grid: on a tile, long-press belongs to the home screen, which lifts the tile to move it.
internal val HANDLER_PARAMS =
    mapOf(
        "onTap" to listOf("x", "y"),
        "onLongPress" to listOf("x", "y"),
        // As the finger goes down, and with `repeats: true` again while it stays: `repeat` counts from 0, the press.
        "onPress" to listOf("x", "y", "repeat"),
        "onRelease" to emptyList(),
        "onDrag" to listOf("dx", "dy", "x", "y"),
        "onChange" to listOf("value"),
        "onSubmit" to listOf("value"),
        "onDismiss" to emptyList(),
        // A panel's, at its top: a key pressed in the prompt bar while it's open.
        "onKey" to listOf("value"),
    )

/** Handlers only some nodes have. */
internal val HANDLER_KINDS = mapOf("onChange" to setOf("Slider", "Input"), "onSubmit" to setOf("Input"), "onDismiss" to setOf("Popup"))

/** What triggers bind in their statements. */
internal val TRIGGER_PARAMS = mapOf("on" to listOf("value", "old"))

/** Data from the phone and the launcher; device is fixed, system is polled, the rest update as they change. */
internal val ANDROID = setOf("battery", "device", "media", "notifications", "volume", "system", "alarm", "modes", "apps", "agents", "recent", "drawer", "modifiers")

/** The words an action's first value may be, when it's written out; [numbers] also allows a number. */
internal class Words(
    val words: Set<String>,
    val message: String,
    val numbers: Boolean = false,
)

internal val ACTION_WORDS =
    mapOf(
        "media" to setOf("toggle", "play", "pause", "next", "previous", "open").let { Words(it, "media takes ${it.joinToString()}") },
        // A number sets the level in percent.
        "volume" to setOf("up", "down", "mute", "unmute", "toggle").let { Words(it, "volume takes a level in percent, or ${it.joinToString()}", numbers = true) },
        // true and false also turn it on and off.
        "torch" to setOf("on", "off", "toggle").let { Words(it, "torch takes true, false, ${it.joinToString()}") },
        "notification" to setOf("open", "dismiss").let { Words(it, "notification takes ${it.joinToString()} and a notification's key") },
        "appAction" to setOf("pin", "info", "uninstall", "remove", "edit", "share").let { Words(it, "appAction takes ${it.joinToString()} and an app's key") },
        "workApps" to setOf("pause", "resume", "toggle").let { Words(it, "workApps takes ${it.joinToString()}") },
        "latch" to KeyNames.latches.toSet().let { Words(it, "latch takes ${it.joinToString()}, and optionally once, locked or off") },
    )

/** The tile's size, readable anywhere a declaration of the same name doesn't shadow it. */
internal val TILE = setOf("cols", "rows", "tileWidth", "tileHeight", "screenWidth", "screenHeight", "screenInset", "screenTop", "corners", "gapsIn", "gapsOut", "borderWidth")

/** Builtins that always give a number (or null, which reads as …). */
internal val NUMBERS = setOf("int", "round", "floor", "ceil", "abs", "fixed", "len", "count", "sum", "min", "max", "clamp")

internal val WIDGET_PROPS = setOf("size", "minSize", "page", "order", "padding", "background")

/** A face has no tile's size; it says where it goes and whether the status bar stays over it. */
internal val FACE_PROPS = setOf("page", "order", "padding", "background", "statusBar")
