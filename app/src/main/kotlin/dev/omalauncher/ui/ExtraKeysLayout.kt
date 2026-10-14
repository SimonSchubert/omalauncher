package dev.omalauncher.ui

import dev.omalauncher.wayland.Keys
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A modifier the extra keys latch for the next key, or lock. */
enum class Mod { Ctrl, Alt, Shift }

/** One keystroke: [code] with [held] down around it; or, with [text], that text typed. */
data class Stroke(
    val code: Int = 0,
    val held: List<Int> = emptyList(),
    val text: String? = null,
)

/** What a key sends when swiped up and let go, drawn as [label] in the key's corner. */
data class Popup(
    val label: String,
    val strokes: List<Stroke>,
)

sealed interface ExtraKey {
    val label: String
    val popup: Popup?
}

/** A key that sends [strokes]; drawn as [label], or as a chevron turned [arrow] degrees from up. */
data class Plain(
    override val label: String,
    val strokes: List<Stroke>,
    val repeats: Boolean = false,
    val arrow: Float? = null,
    override val popup: Popup? = null,
) : ExtraKey

/** Ctrl, Alt or Shift: a tap latches it for the next key, a long press locks it. */
data class Sticky(
    override val label: String,
    val mod: Mod,
    override val popup: Popup? = null,
) : ExtraKey

/**
 * The extra keys' rows, from style.toml's `terminal.extra-keys`, in Termux's
 * extra-keys format: a list of rows, each a list of keys. A key is a name
 * (ESC, CTRL, PGUP, F5, a character) or `{key: …, display: …, popup: …}`;
 * a popup or a key can be a `macro`, "CTRL f d" being Ctrl+F, then D. Names
 * go in any case, quoted either way or bare; other text is typed as it is.
 */
object ExtraKeysLayout {
    /** More would be keys too small to hit, or a bar covering the terminal. */
    const val MAX_ROWS = 3
    const val MAX_KEYS = 10

    private val SHIFT = listOf(Keys.KEY_LEFTSHIFT)

    /** Without the setting: Termux's two rows, with the clipboard keys foot, GTK and Qt all know. */
    val Default: List<List<ExtraKey>> =
        listOf(
            listOf(
                key("esc", Keys.KEY_ESC),
                key("/", Keys.KEY_SLASH),
                key("-", Keys.KEY_MINUS),
                key("home", Keys.KEY_HOME),
                key("up", Keys.KEY_UP, repeats = true, arrow = 0f),
                key("end", Keys.KEY_END),
                key("pgup", Keys.KEY_PAGEUP, repeats = true),
                key("copy", Keys.KEY_INSERT, held = listOf(Keys.KEY_LEFTCTRL)),
            ),
            listOf(
                key("tab", Keys.KEY_TAB),
                Sticky("ctrl", Mod.Ctrl),
                Sticky("alt", Mod.Alt),
                key("left", Keys.KEY_LEFT, repeats = true, arrow = -90f),
                key("down", Keys.KEY_DOWN, repeats = true, arrow = 180f),
                key("right", Keys.KEY_RIGHT, repeats = true, arrow = 90f),
                key("pgdn", Keys.KEY_PAGEDOWN, repeats = true),
                key("paste", Keys.KEY_INSERT, held = SHIFT),
            ),
        )

    private val NAMES: Map<String, Stroke> =
        buildMap {
            fun alias(
                names: String,
                stroke: Stroke,
            ) = names.split(' ').forEach { put(it, stroke) }
            alias("ESC ESCAPE", Stroke(Keys.KEY_ESC))
            alias("TAB", Stroke(Keys.KEY_TAB))
            alias("BACKTAB BACK_TAB BTAB ISO_LEFT_TAB SHIFTTAB SHIFT_TAB", Stroke(Keys.KEY_TAB, SHIFT))
            alias("ENTER RETURN", Stroke(Keys.KEY_ENTER))
            alias("SPACE", Stroke(Keys.KEY_SPACE))
            // Termux's named punctuation keys must send characters, not their literal names.
            alias("BACKSLASH", Stroke(Keys.forChar('\\')!!.first))
            alias("QUOTE", Stroke(Keys.forChar('"')!!.first, SHIFT))
            alias("APOSTROPHE", Stroke(Keys.forChar('\'')!!.first))
            alias("BKSP BACKSPACE", Stroke(Keys.KEY_BACKSPACE))
            alias("DEL DELETE", Stroke(Keys.KEY_DELETE))
            alias("INS INSERT", Stroke(Keys.KEY_INSERT))
            alias("HOME", Stroke(Keys.KEY_HOME))
            alias("END", Stroke(Keys.KEY_END))
            alias("PGUP PAGEUP PAGE_UP", Stroke(Keys.KEY_PAGEUP))
            alias("PGDN PAGEDOWN PAGE_DOWN", Stroke(Keys.KEY_PAGEDOWN))
            alias("UP", Stroke(Keys.KEY_UP))
            alias("DOWN", Stroke(Keys.KEY_DOWN))
            alias("LEFT", Stroke(Keys.KEY_LEFT))
            alias("RIGHT", Stroke(Keys.KEY_RIGHT))
            alias("COPY", Stroke(Keys.KEY_INSERT, listOf(Keys.KEY_LEFTCTRL)))
            alias("PASTE", Stroke(Keys.KEY_INSERT, SHIFT))
            for (n in 1..12) put("F$n", Stroke(Keys.function(n)!!))
        }

    /** Termux's keys that act on its own app rather than send anything. */
    private val TERMUX_ACTIONS = setOf("KEYBOARD", "DRAWER", "SCROLL", "FN")

    private val REPEATS = setOf(
        "UP", "DOWN", "LEFT", "RIGHT",
        "PGUP", "PAGEUP", "PAGE_UP", "PGDN", "PAGEDOWN", "PAGE_DOWN",
        "BKSP", "BACKSPACE", "DEL", "DELETE",
    )
    private val ARROWS = mapOf("UP" to 0f, "DOWN" to 180f, "LEFT" to -90f, "RIGHT" to 90f)

    private const val STRUCTURE = "[]{}:,"

    private var cached: Pair<String?, List<List<ExtraKey>>> = null to Default

    /** [spec]'s rows, read once per change. Unset, or unreadable (style.toml checks it first), the default. */
    fun of(spec: String?): List<List<ExtraKey>> {
        val (last, rows) = cached
        if (last == spec) return rows
        val next = spec?.let { runCatching { parse(it) }.getOrNull() } ?: Default
        cached = spec to next
        return next
    }

    /** [spec]'s rows; [IllegalArgumentException] says what's wrong with it. `[]` is no rows, hiding the bar. */
    fun parse(spec: String): List<List<ExtraKey>> {
        val root =
            try {
                JSONArray(json(spec))
            } catch (e: JSONException) {
                throw IllegalArgumentException("isn't Termux's extra-keys format (${e.message})")
            }
        require(root.length() <= MAX_ROWS) { "has ${root.length()} rows; at most $MAX_ROWS fit" }
        return (0 until root.length()).mapNotNull { r ->
            val row = root.optJSONArray(r) ?: throw IllegalArgumentException("row ${r + 1} isn't a list of keys in [ ]")
            require(row.length() <= MAX_KEYS) { "row ${r + 1} has ${row.length()} keys; at most $MAX_KEYS fit" }
            (0 until row.length())
                .map { k ->
                    try {
                        key(row.get(k))
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("row ${r + 1}, key ${k + 1}: ${e.message}")
                    }
                }.takeIf { it.isNotEmpty() }
        }
    }

    private fun key(
        label: String,
        code: Int,
        held: List<Int> = emptyList(),
        repeats: Boolean = false,
        arrow: Float? = null,
    ) = Plain(label, listOf(Stroke(code, held)), repeats, arrow)

    private fun key(item: Any): ExtraKey =
        when (item) {
            is String -> key(item, null, null)
            is JSONObject -> {
                val label = item.text("display")
                val popup = item.opt("popup")?.let(::popup)
                val macro = item.text("macro")
                val name = item.text("key")
                when {
                    macro != null -> Plain(label ?: macro, macro(macro), popup = popup)
                    name != null -> key(name, label, popup)
                    else -> throw IllegalArgumentException("takes a key or a macro")
                }
            }
            else -> throw IllegalArgumentException("takes a key name or {key: …}")
        }

    private fun key(
        name: String,
        label: String?,
        popup: Popup?,
    ): ExtraKey {
        modifier(name)?.let { return Sticky(label ?: it.name.lowercase(), it, popup) }
        val stroke = stroke(name)
        val upper = name.uppercase()
        return Plain(
            label = label ?: if (upper in NAMES) name.lowercase() else name,
            strokes = listOf(stroke),
            // A key with a popup waits for its release to know which it was, so it can't repeat.
            repeats = upper in REPEATS && popup == null,
            arrow = if (label == null) ARROWS[upper] else null,
            popup = popup,
        )
    }

    private fun popup(value: Any): Popup {
        val label: String?
        val strokes: List<Stroke>
        when (value) {
            is String -> {
                label = value
                strokes = listOf(popupStroke(value))
            }
            is JSONObject -> {
                val macro = value.text("macro")
                val name = value.text("key")
                label = value.text("display") ?: macro ?: name
                strokes =
                    when {
                        macro != null -> macro(macro)
                        name != null -> listOf(popupStroke(name))
                        else -> throw IllegalArgumentException("its popup takes a key or a macro")
                    }
            }
            else -> throw IllegalArgumentException("its popup takes a key name or {key: …}")
        }
        return Popup(label!!, strokes)
    }

    private fun popupStroke(name: String): Stroke {
        require(modifier(name) == null) { "a popup can't latch $name; send a chord with a macro, like \"$name C\"" }
        return stroke(name)
    }

    /** Termux's macro: keys apart by spaces, each with the modifiers named before it. */
    private fun macro(macro: String): List<Stroke> {
        val held = ArrayList<Int>()
        val strokes = ArrayList<Stroke>()
        val tokens = Regex("""\r\n|\r|\n|[^\s]+""").findAll(macro).map { it.value }.toList()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            // Keep line endings as Enter strokes; :wq\n must not drop its last action.
            if (token == "\n" || token == "\r" || token == "\r\n") {
                require(held.isEmpty()) { "a macro can't put a modifier before Enter; name the key it goes with" }
                strokes += Stroke(Keys.KEY_ENTER)
                i++
                continue
            }
            if (token.isEmpty()) {
                i++
                continue
            }
            val mod = modifier(token)
            if (mod != null) {
                if (code(mod) !in held) held += code(mod)
                i++
                continue
            }
            // Termux ANSI escape sequences in macros, most notably "ESC [ Z" for Shift+Tab / Backtab.
            if (token.uppercase() in setOf("ESC", "ESCAPE") && i + 2 < tokens.size && tokens[i + 1] == "[") {
                val csiKey =
                    when (tokens[i + 2].uppercase()) {
                        "Z" -> Stroke(Keys.KEY_TAB, (held + Keys.KEY_LEFTSHIFT).distinct())
                        "A" -> Stroke(Keys.KEY_UP, held)
                        "B" -> Stroke(Keys.KEY_DOWN, held)
                        "C" -> Stroke(Keys.KEY_RIGHT, held)
                        "D" -> Stroke(Keys.KEY_LEFT, held)
                        "H" -> Stroke(Keys.KEY_HOME, held)
                        "F" -> Stroke(Keys.KEY_END, held)
                        else -> null
                    }
                if (csiKey != null) {
                    strokes += csiKey
                    held.clear()
                    i += 3
                    continue
                }
            }
            val stroke = stroke(token)
            require(stroke.text == null || held.isEmpty()) { "in \"$macro\", a modifier goes before a single key, not $token" }
            // "CTRL A" is Ctrl+A, as in Termux, not Ctrl+Shift+A; "CTRL SHIFT A" asks for that.
            val own =
                if (held.isNotEmpty() && token.length == 1 && token[0] in 'A'..'Z') {
                    stroke.held - Keys.KEY_LEFTSHIFT
                } else {
                    stroke.held
                }
            strokes += stroke.copy(held = (held + own).distinct())
            held.clear()
            i++
        }
        require(held.isEmpty()) { "\"$macro\" ends on a modifier; name the key it goes with" }
        require(strokes.isNotEmpty()) { "its macro is empty" }
        return strokes
    }

    private fun stroke(name: String): Stroke {
        val upper = name.uppercase()
        NAMES[upper]?.let { return it }
        require(upper !in TERMUX_ACTIONS) { "$name is one of Termux's own actions, which the launcher doesn't have" }
        if (upper in setOf("\u001b[Z", "\\033[Z", "\\E[Z", "\\U001B[Z", "^[[Z")) {
            return Stroke(Keys.KEY_TAB, SHIFT)
        }
        if (name.length == 1) {
            Keys.forChar(name[0])?.let { (code, shifted) -> return Stroke(code, if (shifted) SHIFT else emptyList()) }
        }
        // As in Termux: anything else is typed as it is, like a character no US key has.
        return Stroke(text = name)
    }

    private fun modifier(name: String) =
        when (name.uppercase()) {
            "CTRL", "CONTROL" -> Mod.Ctrl
            "ALT" -> Mod.Alt
            "SHIFT" -> Mod.Shift
            else -> null
        }

    private fun code(mod: Mod) =
        when (mod) {
            Mod.Ctrl -> Keys.KEY_LEFTCTRL
            Mod.Alt -> Keys.KEY_LEFTALT
            Mod.Shift -> Keys.KEY_LEFTSHIFT
        }

    private fun JSONObject.text(name: String) = optString(name).takeIf { it.isNotEmpty() }

    /**
     * [spec] as strict JSON, every name quoted. Termux takes them quoted either
     * way or bare, which org.json versions don't all read alike, and bare ones
     * could come back as numbers or booleans.
     */
    private fun json(spec: String): String =
        buildString {
            var i = 0
            while (i < spec.length) {
                val c = spec[i]
                when {
                    c.isWhitespace() || c in STRUCTURE -> {
                        append(c)
                        i++
                    }
                    c == '"' || c == '\'' -> {
                        val value = StringBuilder()
                        i++
                        while (true) {
                            require(i < spec.length) { "a string is missing its closing $c" }
                            val ch = spec[i++]
                            if (ch == c) break
                            if (ch == '\\' && i < spec.length) {
                                // Interpret Termux's escaped ESC forms. Preserve unknown
                                // backslashes rather than silently modifying a macro.
                                when (val e = spec[i++]) {
                                    'n' -> value.append('\n')
                                    't' -> value.append('\t')
                                    'r' -> value.append('\r')
                                    'b' -> value.append('\b')
                                    'f' -> value.append('\u000c')
                                    'e', 'E' -> value.append('\u001b')
                                    'u' -> {
                                        val hex = spec.substring(i, (i + 4).coerceAtMost(spec.length))
                                        if (hex.length == 4 && hex.all { it.digitToIntOrNull(16) != null }) {
                                            value.append(hex.toInt(16).toChar())
                                            i += 4
                                        } else {
                                            value.append('\\').append(e)
                                        }
                                    }
                                    '0' -> {
                                        if (spec.startsWith("33", i)) {
                                            value.append('\u001b')
                                            i += 2
                                        } else {
                                            value.append('\\').append(e)
                                        }
                                    }
                                    '\\', '"', '\'' -> value.append(e)
                                    else -> value.append('\\').append(e)
                                }
                            } else {
                                value.append(ch)
                            }
                        }
                        append(JSONObject.quote(value.toString()))
                    }
                    else -> {
                        val from = i
                        while (i < spec.length && !spec[i].isWhitespace() && spec[i] !in STRUCTURE) i++
                        append(JSONObject.quote(spec.substring(from, i)))
                    }
                }
            }
        }
}
