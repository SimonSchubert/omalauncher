package dev.omalauncher.terminal

import dev.omalauncher.wayland.Keys.KEY_CAPSLOCK
import dev.omalauncher.wayland.Keys.KEY_LEFTALT
import dev.omalauncher.wayland.Keys.KEY_LEFTCTRL
import dev.omalauncher.wayland.Keys.KEY_LEFTMETA
import dev.omalauncher.wayland.Keys.KEY_LEFTSHIFT
import dev.omalauncher.wayland.Keys.KEY_RIGHTALT
import dev.omalauncher.wayland.Keys.KEY_RIGHTCTRL
import dev.omalauncher.wayland.Keys.KEY_RIGHTMETA
import dev.omalauncher.wayland.Keys.KEY_RIGHTSHIFT

/**
 * Keys pressed and released as evdev codes on a US layout, as the launcher sends them to a Linux window, turned into
 * what a built-in terminal sends its program. Keeps which modifiers are held and Caps Lock. One thread.
 */
class TerminalKeyboard {
    /** What a key press does in the terminal. */
    sealed interface Action {
        /** Bytes for the program, encoded as it asked: see [Vt.key]. */
        data class Send(
            val bytes: ByteArray,
        ) : Action

        /** Ctrl+Shift+C, as in foot. */
        data object Copy : Action

        /** Ctrl+Shift+V, as in foot. */
        data object Paste : Action
    }

    /**
     * The modifier keys down now, by code. A set, as a Wayland keyboard keeps it: a key pressed twice (Shift implied
     * by a key's meta state, then its own press) is up again after one release, so it can't stay held.
     */
    private val held = mutableSetOf<Int>()
    private var capsLock = false

    /** [Vt.SHIFT] and the others, held now. */
    val mods get() =
        (if (KEY_LEFTSHIFT in held || KEY_RIGHTSHIFT in held) Vt.SHIFT else 0) or
            (if (KEY_LEFTCTRL in held || KEY_RIGHTCTRL in held) Vt.CTRL else 0) or
            (if (KEY_LEFTALT in held || KEY_RIGHTALT in held) Vt.ALT else 0) or
            (if (KEY_LEFTMETA in held || KEY_RIGHTMETA in held) Vt.SUPER else 0)

    /** Evdev [code] went down or up; what it does, if anything. Releases do nothing: no program asks for them yet. */
    fun key(
        vt: Vt,
        code: Int,
        pressed: Boolean,
    ): Action? {
        when (code) {
            KEY_LEFTSHIFT, KEY_RIGHTSHIFT, KEY_LEFTCTRL, KEY_RIGHTCTRL, KEY_LEFTALT, KEY_RIGHTALT, KEY_LEFTMETA, KEY_RIGHTMETA ->
                if (pressed) held += code else held -= code
            KEY_CAPSLOCK -> if (pressed) capsLock = !capsLock
            else -> return if (pressed) press(vt, code) else null
        }
        return null
    }

    /** Forgets held modifiers, as the terminal loses the keyboard. */
    fun reset() {
        held.clear()
    }

    private fun press(
        vt: Vt,
        code: Int,
    ): Action? {
        val k = byCode[code] ?: return null
        val mods = mods
        if (mods and (Vt.CTRL or Vt.SHIFT) == Vt.CTRL or Vt.SHIFT && mods and (Vt.ALT or Vt.SUPER) == 0) {
            if (k.key == VtKey.C) return Action.Copy
            if (k.key == VtKey.V) return Action.Paste
        }
        val letter = k.plain?.isLetter() == true
        val shifted = (mods and Vt.SHIFT != 0) xor (letter && capsLock)
        val char = if (shifted) k.shifted ?: k.plain else k.plain
        // Text only where the key types it: Ctrl makes a control character, which the encoder works out itself.
        val text = char?.takeIf { mods and (Vt.CTRL or Vt.SUPER) == 0 }?.toString()
        // Shift that picked the character is part of it: the kitty protocol sends Shift+; as ":", not as Shift+;.
        val consumed = if (text != null && mods and Vt.SHIFT != 0 && k.shifted != null) Vt.SHIFT else 0
        return vt.key(k.key, mods, text, k.plain?.code ?: 0, consumed)?.let { Action.Send(it) }
    }

    private class Key(
        val key: VtKey,
        val plain: Char? = null,
        val shifted: Char? = null,
    )

    companion object {
        /** Evdev codes of a US keyboard, as `wayland/Keys.kt` and `KeyNames` use them. */
        private val byCode: Map<Int, Key> =
            buildMap {
                put(1, Key(VtKey.ESCAPE))
                val digits = listOf(VtKey.DIGIT_1, VtKey.DIGIT_2, VtKey.DIGIT_3, VtKey.DIGIT_4, VtKey.DIGIT_5, VtKey.DIGIT_6, VtKey.DIGIT_7, VtKey.DIGIT_8, VtKey.DIGIT_9, VtKey.DIGIT_0)
                "1234567890".forEachIndexed { i, c -> put(2 + i, Key(digits[i], c, "!@#$%^&*()"[i])) }
                put(12, Key(VtKey.MINUS, '-', '_'))
                put(13, Key(VtKey.EQUAL, '=', '+'))
                put(14, Key(VtKey.BACKSPACE))
                put(15, Key(VtKey.TAB))
                for ((row, start) in listOf("qwertyuiop" to 16, "asdfghjkl" to 30, "zxcvbnm" to 44)) {
                    row.forEachIndexed { i, c -> put(start + i, Key(VtKey.valueOf(c.uppercase()), c, c.uppercaseChar())) }
                }
                put(26, Key(VtKey.BRACKET_LEFT, '[', '{'))
                put(27, Key(VtKey.BRACKET_RIGHT, ']', '}'))
                put(28, Key(VtKey.ENTER))
                put(96, Key(VtKey.ENTER))
                put(39, Key(VtKey.SEMICOLON, ';', ':'))
                put(40, Key(VtKey.QUOTE, '\'', '"'))
                put(41, Key(VtKey.BACKQUOTE, '`', '~'))
                put(43, Key(VtKey.BACKSLASH, '\\', '|'))
                put(51, Key(VtKey.COMMA, ',', '<'))
                put(52, Key(VtKey.PERIOD, '.', '>'))
                put(53, Key(VtKey.SLASH, '/', '?'))
                put(57, Key(VtKey.SPACE, ' ', ' '))
                val f = listOf(VtKey.F1, VtKey.F2, VtKey.F3, VtKey.F4, VtKey.F5, VtKey.F6, VtKey.F7, VtKey.F8, VtKey.F9, VtKey.F10)
                f.forEachIndexed { i, key -> put(59 + i, Key(key)) }
                put(87, Key(VtKey.F11))
                put(88, Key(VtKey.F12))
                put(102, Key(VtKey.HOME))
                put(103, Key(VtKey.ARROW_UP))
                put(104, Key(VtKey.PAGE_UP))
                put(105, Key(VtKey.ARROW_LEFT))
                put(106, Key(VtKey.ARROW_RIGHT))
                put(107, Key(VtKey.END))
                put(108, Key(VtKey.ARROW_DOWN))
                put(109, Key(VtKey.PAGE_DOWN))
                put(110, Key(VtKey.INSERT))
                put(111, Key(VtKey.DELETE))
            }
    }
}
