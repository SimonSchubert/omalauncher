package dev.omalauncher.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.wayland.Keys

/** How a modifier on the extra-keys row is held: for the next key only, or until tapped again. */
enum class Latch { Off, Once, Locked }

/** A modifier the extra-keys row latches, since the soft keyboard has none of its own. */
class StickyModifier(
    val code: Int,
) {
    var latch by mutableStateOf(Latch.Off)
}

/** Translates Android key events and IME text into Wayland keyboard input for the focused window. */
class KeyboardBridge(
    private val wm: WindowManager,
) {
    /** Launcher shortcuts triggered with Meta (Super), like Omarchy's Super bindings. */
    var onShortcut: (Int) -> Boolean = { false }

    val ctrl = StickyModifier(Keys.KEY_LEFTCTRL)
    val alt = StickyModifier(Keys.KEY_LEFTALT)
    val shift = StickyModifier(Keys.KEY_LEFTSHIFT)
    private val sticky = listOf(ctrl, alt, shift)

    fun modifier(mod: Mod) =
        when (mod) {
            Mod.Ctrl -> ctrl
            Mod.Alt -> alt
            Mod.Shift -> shift
        }

    /**
     * A latched physical key was sent as a complete press, so its later Android release is swallowed.
     * Keep all outstanding codes, not just the last one: hardware keyboards can overlap key presses.
     */
    private val swallowUps = HashSet<Int>()

    fun handle(event: KeyEvent): Boolean {
        if (event.isMetaPressed && event.action == KeyEvent.ACTION_DOWN && onShortcut(event.keyCode)) return true
        // Volume buttons stay Android's; their scancodes would otherwise reach the window as KEY_VOLUMEUP/DOWN.
        if (event.keyCode in volumeKeys) return false
        val code = Keys.evdev(event) ?: event.scanCode.takeIf { it > 0 } ?: return false
        if (wm.focused == null) {
            // A focus switch can prevent the paired Android key-up reaching this bridge.
            swallowUps.clear()
            return false
        }
        if (event.action == KeyEvent.ACTION_UP && swallowUps.remove(code)) return true
        if (event.action == KeyEvent.ACTION_DOWN && code in swallowUps) {
            // The one-shot chord was sent completely. Ignore hardware auto-repeat:
            // after consuming Once, replaying it would type an unmodified character.
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && latched() && !Keys.isModifier(code)) {
            tap(code)
            swallowUps += code
            return true
        }
        // Key repeat is ours (clients get rate 0): replay Android's repeats as release + press.
        if (event.repeatCount > 0) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                key(code, false)
                key(code, true)
            }
            return true
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> key(code, true)
            KeyEvent.ACTION_UP -> key(code, false)
        }
        return true
    }

    fun type(text: CharSequence) {
        for (cp in text.codePoints().toArray()) {
            val mapped = if (cp <= 0xffff) Keys.forChar(cp.toChar()) else null
            if (mapped == null) {
                // Not on a US key, like æ, 你 or an emoji: the keymap grows a key for it.
                if (!Character.isISOControl(cp)) wm.server.postInput { typeCodePoint(cp) }
                continue
            }
            val (code, shifted) = mapped
            tap(code, if (shifted) listOf(Keys.KEY_LEFTSHIFT) else emptyList())
        }
    }

    /**
     * Presses and releases [code], with [held] and any latched modifiers held around it.
     * A modifier both latched and in [held] is pressed once: a second press would be lost,
     * and its release would let go of the latch's too early.
     */
    fun tap(
        code: Int,
        held: List<Int> = emptyList(),
    ) = withLatched { latched ->
        val extra = held.distinct().filterNot { it in latched }
        extra.forEach { key(it, true) }
        key(code, true)
        key(code, false)
        extra.asReversed().forEach { key(it, false) }
    }

    /** Sends [strokes] one after another, like typing them: a latch for the next key ends after the first. */
    fun send(strokes: List<Stroke>) {
        for (s in strokes) if (s.text != null) type(s.text) else tap(s.code, s.held)
    }

    /** Drops every latch, so a modifier left on doesn't surprise the next keystroke. */
    fun clearLatches() {
        sticky.forEach { it.latch = Latch.Off }
        swallowUps.clear()
    }

    private fun latched() = sticky.any { it.latch != Latch.Off }

    private inline fun withLatched(block: (latched: Set<Int>) -> Unit) {
        val held = sticky.filter { it.latch != Latch.Off }
        held.forEach { key(it.code, true) }
        block(held.mapTo(HashSet()) { it.code })
        held.forEach { key(it.code, false) }
        held.filter { it.latch == Latch.Once }.forEach { it.latch = Latch.Off }
    }

    private fun key(
        code: Int,
        pressed: Boolean,
    ) = wm.server.postInput { key(code, pressed) }
}

/** An invisible view that owns the soft keyboard and feeds it into [KeyboardBridge]. */
class KeyboardSink(
    context: Context,
    private val bridge: KeyboardBridge,
) : View(context) {
    /**
     * Only claim to be a text editor while the soft keyboard is wanted. A focused
     * editor makes Android route hardware keys through the keyboard app first,
     * which hands some back asynchronously and reorders or doubles keystrokes.
     */
    private var softKeyboard = false

    /** Whether the soft keyboard is wanted: shown by us and not hidden by us since. */
    val wanted get() = softKeyboard

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** Whether the field was last offered as plain text, for a Chinese, Japanese or Korean keyboard. */
    private var cjk = false

    /** Restarts the field when the keyboard switches between a CJK language and another one. */
    private val subtypeObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (softKeyboard && cjkKeyboard() != cjk) imm().restartInput(this@KeyboardSink)
            }
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val uri = Settings.Secure.getUriFor(Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE)
        context.contentResolver.registerContentObserver(uri, false, subtypeObserver)
    }

    override fun onDetachedFromWindow() {
        context.contentResolver.unregisterContentObserver(subtypeObserver)
        super.onDetachedFromWindow()
    }

    override fun onCheckIsTextEditor() = softKeyboard

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        cjk = cjkKeyboard()
        // Like Termux: no suggestions or autocorrect, raw keys where the IME allows. Gboard
        // drops pinyin and the like in such a field, so those get plain text, autocorrect and all.
        outAttrs.inputType =
            if (cjk) {
                InputType.TYPE_CLASS_TEXT
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            /**
             * Text the IME is still composing, like pinyin before a candidate is picked. A window
             * can't show it, and the IME's candidate bar does, so it's only typed once finished.
             */
            private var composing: CharSequence = ""

            override fun setComposingText(
                text: CharSequence,
                newCursorPosition: Int,
            ): Boolean {
                composing = text
                return true
            }

            override fun finishComposingText(): Boolean {
                bridge.type(composing)
                composing = ""
                return true
            }

            override fun commitText(
                text: CharSequence,
                newCursorPosition: Int,
            ): Boolean {
                composing = ""
                bridge.type(text)
                return true
            }

            override fun deleteSurroundingText(
                beforeLength: Int,
                afterLength: Int,
            ): Boolean {
                repeat(beforeLength.coerceAtLeast(1)) { bridge.tap(Keys.KEY_BACKSPACE) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                // Text with no key code, which some IMEs send as one event.
                if (event.action == KeyEvent.ACTION_MULTIPLE && event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                    event.characters?.let(bridge::type)
                } else {
                    bridge.handle(event)
                }
                return true
            }
        }
    }

    private fun imm() = context.getSystemService(InputMethodManager::class.java)

    /** Whether the keyboard is set to Chinese, Japanese or Korean. */
    private fun cjkKeyboard(): Boolean {
        val subtype = imm().currentInputMethodSubtype ?: return false
        @Suppress("DEPRECATION")
        val language = subtype.languageTag.ifEmpty { subtype.locale }.substringBefore('-').substringBefore('_')
        return language in cjkLanguages
    }

    fun toggle() = if (softKeyboard) hide() else show()

    fun show() {
        val imm = imm()
        softKeyboard = true
        requestFocus()
        imm.restartInput(this)
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hide() {
        if (!softKeyboard) return
        val imm = imm()
        imm.hideSoftInputFromWindow(windowToken, 0)
        softKeyboard = false
        bridge.clearLatches()
        imm.restartInput(this)
    }
}

private val cjkLanguages = setOf("zh", "ja", "ko")

private val volumeKeys = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)
