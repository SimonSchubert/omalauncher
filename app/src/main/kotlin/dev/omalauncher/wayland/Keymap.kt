package dev.omalauncher.wayland

import java.io.File

/**
 * The compiled US keymap plus a pool of spare keys bound to whatever characters the soft keyboard
 * typed that US keys can't, like æ or 你 (the way wtype does it). Slot keys sit above evdev's
 * KEY_MAX, so no real key ever lands on one.
 */
class Keymap(
    base: String,
    private val dir: File,
) {
    private val keycodes: String
    private val symbols: String
    private val rest: String

    /** Code points bound to slots, least recently used first; a slot is its index in [bound]. */
    private val bound = LinkedHashMap<Int, Int>(SLOTS, 0.75f, true)

    private var generation = 0

    init {
        // Files left by an earlier run.
        dir.deleteRecursively()
        dir.mkdirs()
    }

    /** The keymap file to send, NUL-terminated since clients parse it as a C string. */
    var file: File = write(base)
        private set

    init {
        // Split right before the closing "};" of xkb_keycodes and xkb_symbols, where slots go.
        val keycodesEnd = base.indexOf("\n};", base.indexOf("xkb_keycodes"))
        val symbolsEnd = base.indexOf("\n};", base.indexOf("xkb_symbols"))
        require(keycodesEnd in 0 until symbolsEnd) { "unexpected keymap layout" }
        keycodes =
            base.substring(0, keycodesEnd).replace(Regex("maximum\\s*=\\s*\\d+;")) {
                "maximum = ${FIRST_SLOT + SLOTS - 1 + 8};"
            }
        symbols = base.substring(keycodesEnd, symbolsEnd)
        rest = base.substring(symbolsEnd)
    }

    /** Returns the evdev code that types [codePoint], and whether [file] changed to bind it. */
    fun slotFor(codePoint: Int): Pair<Int, Boolean> {
        bound[codePoint]?.let { return FIRST_SLOT + it to false }
        val slot =
            if (bound.size < SLOTS) {
                bound.size
            } else {
                val oldest = bound.keys.first()
                bound.remove(oldest)!!
            }
        bound[codePoint] = slot
        file = write(render())
        return FIRST_SLOT + slot to true
    }

    private fun render() =
        buildString {
            append(keycodes)
            for (slot in bound.values) append("\n\t<OW$slot> = ${FIRST_SLOT + slot + 8};")
            append(symbols)
            for ((cp, slot) in bound) append("\n\tkey <OW$slot> { [ U${"%04X".format(cp)} ] };")
            append(rest)
        }

    /** Writes a new file per keymap; fds opened on the previous one stay valid after it's deleted. */
    private fun write(text: String): File {
        val f = File(dir, "keymap-${generation++}.xkb")
        f.writeBytes(text.toByteArray() + 0)
        File(dir, "keymap-${generation - 2}.xkb").delete()
        return f
    }

    private companion object {
        /** Past evdev's KEY_MAX (0x2ff). */
        const val FIRST_SLOT = 0x300
        const val SLOTS = 32
    }
}
