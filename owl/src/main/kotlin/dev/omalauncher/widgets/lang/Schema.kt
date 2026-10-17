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
        "Column" to Kind(setOf("gap", "align", "justify", "scroll"), Children.UI),
        "Row" to Kind(setOf("gap", "align", "justify", "wrap"), Children.UI),
        "Flow" to Kind(setOf("gap", "align", "justify"), Children.UI),
        "Box" to Kind(setOf("align"), Children.UI),
        "Grid" to Kind(setOf("columns", "gap"), Children.UI),
        "Text" to Kind(setOf("text", "style", "color", "fontSize", "bold", "italic", "font", "align", "maxLines"), Children.NONE),
        "Image" to Kind(setOf("src", "fit", "tint"), Children.NONE),
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
        "Input" to Kind(setOf("text", "placeholder", "color", "fontSize", "clear", "focus"), Children.NONE),
    )

internal val COMMON =
    setOf(
        "width", "height", "size", "fill", "weight", "padding", "background", "border", "borderColor",
        "radius", "opacity", "rotation", "scale", "offset", "clip", "visible",
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
    )

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
internal val DRAW_NAMED = setOf("fill", "stroke", "width", "cap", "radius", "closed", "size", "color", "align", "bold", "palette")

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
        listOf("app \"…\" { }", "android.apps", WEB_IMAGE, "sound", "enc", "post", "screenInset").associateWith { 8 }

internal const val WEB_IMAGE = "an image from the web"

/**
 * Package managers a widget can declare in `requires`, with what a
 * package name may look like. Names end up in a shell command, so the
 * pattern is strict.
 */
internal val MANAGERS = mapOf("pacman" to Regex("[a-z0-9@_+][a-z0-9@._+-]*"))

// No onLongPress: long-press belongs to the home screen, which lifts the tile to move it.
internal val HANDLER_PARAMS =
    mapOf("onTap" to listOf("x", "y"), "onDrag" to listOf("dx", "dy", "x", "y"), "onChange" to listOf("value"), "onSubmit" to listOf("value"))

/** Handlers only some nodes have. */
internal val HANDLER_KINDS = mapOf("onChange" to setOf("Slider", "Input"), "onSubmit" to setOf("Input"))

/** What triggers bind in their statements. */
internal val TRIGGER_PARAMS = mapOf("on" to listOf("value", "old"))

/** Data only Android has; battery, media, notifications, volume, alarm and modes update as they change, device is fixed, system and apps are polled. */
internal val ANDROID = setOf("battery", "device", "media", "notifications", "volume", "system", "alarm", "modes", "apps")

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
    )

/** The tile's size, readable anywhere a declaration of the same name doesn't shadow it. */
internal val TILE = setOf("cols", "rows", "tileWidth", "tileHeight", "screenWidth", "screenHeight", "screenInset", "corners")

/** Builtins that always give a number (or null, which reads as …). */
internal val NUMBERS = setOf("int", "round", "floor", "ceil", "abs", "fixed", "len", "count", "sum", "min", "max", "clamp")

internal val WIDGET_PROPS = setOf("size", "minSize", "page", "order", "padding", "background")
