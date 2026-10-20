package dev.omalauncher.widgets.lang

/** Physical Linux evdev keys, independent of the client's key bindings and keyboard layout. */
object LinuxKeys {
    private val named = buildMap {
        for ((row, start) in listOf("qwertyuiop" to 16, "asdfghjkl" to 30, "zxcvbnm" to 44)) {
            row.forEachIndexed { i, c -> put(c.toString(), start + i) }
        }
        for (n in 1..9) put(n.toString(), n + 1)
        put("0", 11)
        putAll(mapOf(
            "escape" to 1, "backspace" to 14, "tab" to 15, "enter" to 28,
            "leftctrl" to 29, "leftshift" to 42, "rightshift" to 54, "leftalt" to 56,
            "space" to 57, "rightctrl" to 97, "rightalt" to 100,
            "home" to 102, "up" to 103, "pageup" to 104, "left" to 105,
            "right" to 106, "end" to 107, "down" to 108, "pagedown" to 109,
            "insert" to 110, "delete" to 111, "leftmeta" to 125, "rightmeta" to 126,
            "minus" to 12, "equal" to 13, "leftbracket" to 26, "rightbracket" to 27,
            "semicolon" to 39, "apostrophe" to 40, "grave" to 41, "backslash" to 43,
            "comma" to 51, "period" to 52, "slash" to 53,
        ))
        for (n in 1..10) put("f$n", 58 + n)
        put("f11", 87); put("f12", 88)
    }

    /** Null disables the entire control: never turn an invalid chord into a different one. */
    fun codes(value: Value): List<Int>? {
        val keys = if (value is Lst) value.items else listOf(value)
        if (keys.isEmpty() || keys.size > 16) return null
        return keys.map { (it as? Str)?.v?.lowercase()?.let(named::get) ?: return null }.distinct()
    }

    fun validTarget(value: Value) = value is Str && value.v.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}"))
}
