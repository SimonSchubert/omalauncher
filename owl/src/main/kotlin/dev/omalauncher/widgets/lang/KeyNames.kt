package dev.omalauncher.widgets.lang

/**
 * Keys by name, as `pressKey("ctrl+c")` takes them, and characters on a US keyboard: Linux evdev codes, which the
 * launcher sends to the focused Linux window.
 */
object KeyNames {
    /** A key with the modifiers held around it: `ctrl+shift+t` is [code] 20 with Ctrl and Shift. */
    data class Combo(
        val code: Int,
        val held: List<Int>,
    )

    /** The modifiers a combo and `latch(...)` name, left-hand ones. */
    val modifiers = linkedMapOf("ctrl" to 29, "alt" to 56, "shift" to 42, "super" to 125)

    /** The modifiers `latch(...)` holds for the next key: Super stays the launcher's. */
    val latches = listOf("ctrl", "alt", "shift")

    private val named =
        buildMap {
            put("esc", 1)
            put("backspace", 14)
            put("tab", 15)
            put("enter", 28)
            put("space", 57)
            put("home", 102)
            put("up", 103)
            put("pgup", 104)
            put("left", 105)
            put("right", 106)
            put("end", 107)
            put("down", 108)
            put("pgdn", 109)
            put("insert", 110)
            put("delete", 111)
            for (f in 1..10) put("f$f", 58 + f)
            put("f11", 87)
            put("f12", 88)
        }

    /** The names [combo] knows besides single characters, for error messages. */
    val names: Set<String> get() = named.keys

    private const val UNSHIFTED = "1234567890-=qwertyuiop[]asdfghjkl;'`\\zxcvbnm,./ "
    private const val SHIFTED = "!@#$%^&*()_+QWERTYUIOP{}ASDFGHJKL:\"~|ZXCVBNM<>? "
    private val CODES =
        intArrayOf(
            2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
            16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27,
            30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 43,
            44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 57,
        )

    /** Returns (evdev code, needs shift) for a character on a US keyboard; null for one that isn't. */
    fun forChar(c: Char): Pair<Int, Boolean>? {
        if (c == '\n') return 28 to false
        if (c == '\t') return 15 to false
        UNSHIFTED.indexOf(c).takeIf { it >= 0 }?.let { return CODES[it] to false }
        SHIFTED.indexOf(c).takeIf { it >= 0 }?.let { return CODES[it] to true }
        return null
    }

    /**
     * `esc`, `ctrl+c`, `shift+insert`, `ctrl+alt+t` or a character, `/` or `A`; null if it names no key. A shifted
     * character holds Shift itself, so `ctrl+:` is Ctrl+Shift+;. The last `+` of `ctrl++` is the key.
     */
    fun combo(text: String): Combo? {
        if (text.isEmpty()) return null
        val parts =
            when {
                text.length == 1 -> listOf(text)
                text.endsWith("++") -> text.dropLast(2).split('+') + "+"
                else -> text.split('+')
            }
        val key = parts.last()
        val held = ArrayList<Int>()
        for (m in parts.dropLast(1)) {
            val code = modifiers[m.lowercase()] ?: return null
            if (code !in held) held += code
        }
        named[key.lowercase()]?.let { return Combo(it, held) }
        if (key.length != 1) return null
        val (code, shift) = forChar(key[0]) ?: return null
        if (shift && 42 !in held) held += 42
        return Combo(code, held)
    }
}
