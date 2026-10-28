package dev.omalauncher.ui

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.omalauncher.wayland.GuestImages
import dev.omalauncher.wayland.Keys
import dev.omalauncher.widgets.lang.KeyNames
import kotlin.concurrent.thread

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
    private val images: GuestImages,
) {
    /** Launcher shortcuts triggered with Meta (Super), like Omarchy's Super bindings. */
    var onShortcut: (Int) -> Boolean = { false }

    val ctrl = StickyModifier(Keys.KEY_LEFTCTRL)
    val alt = StickyModifier(Keys.KEY_LEFTALT)
    val shift = StickyModifier(Keys.KEY_LEFTSHIFT)
    private val sticky = listOf(ctrl, alt, shift)

    /** The modifiers the extra keys latch, by the names `latch(...)` and `android.modifiers` use. */
    val latches = mapOf("ctrl" to ctrl, "alt" to alt, "shift" to shift)

    /** A key sent whole on its press because modifiers were latched; its release is already sent. */
    private var swallowUp: Int? = null

    fun handle(event: KeyEvent): Boolean {
        if (event.isMetaPressed && event.action == KeyEvent.ACTION_DOWN && onShortcut(event.keyCode)) return true
        // Volume buttons stay Android's; their scancodes would otherwise reach the window as KEY_VOLUMEUP/DOWN. So do
        // Back and a gamepad's buttons: B is Back once nothing takes it, the way out of a window or a terminal tile.
        if (event.keyCode in volumeKeys || event.keyCode == KeyEvent.KEYCODE_BACK || KeyEvent.isGamepadButton(event.keyCode)) return false
        val code = Keys.evdev(event) ?: event.scanCode.takeIf { it > 0 } ?: return false
        if (wm.focused == null) return false
        if (!Keys.isModifier(code)) {
            holdImplied(event.metaState)
        } else if (event.action == KeyEvent.ACTION_DOWN) {
            // Its own press came after all, later than the key it modifies: from here on it's lifted by its release.
            implied -= code
        }
        forward(event, code)
        if (event.action == KeyEvent.ACTION_UP) liftImplied()
        return true
    }

    private fun forward(
        event: KeyEvent,
        code: Int,
    ) {
        if (event.action == KeyEvent.ACTION_UP && code == swallowUp) {
            swallowUp = null
            return
        }
        // One pressed before the window had the keys, like the Esc of Super+Esc in the window left: its lift is no key.
        if (event.action == KeyEvent.ACTION_UP && code !in down) return
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && latched() && !Keys.isModifier(code)) {
            tap(code)
            swallowUp = code
            return
        }
        // Key repeat is ours (clients get rate 0): replay Android's repeats as release + press.
        if (event.repeatCount > 0) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                key(code, false)
                key(code, true)
            }
            return
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> key(code, true)
            KeyEvent.ACTION_UP -> key(code, false)
        }
    }

    fun type(text: CharSequence) {
        liftImplied()
        for (cp in text.codePoints().toArray()) {
            val mapped = if (cp <= 0xffff) KeyNames.forChar(cp.toChar()) else null
            if (mapped == null) {
                // Not on a US key, like æ, 你 or an emoji: the keymap grows a key for it.
                if (!Character.isISOControl(cp)) wm.server.postInput { typeCodePoint(cp) }
                continue
            }
            val (code, shift) = mapped
            withLatched {
                if (shift) key(Keys.KEY_LEFTSHIFT, true)
                key(code, true)
                key(code, false)
                if (shift) key(Keys.KEY_LEFTSHIFT, false)
            }
        }
    }

    /**
     * An image the keyboard inserts, like a screenshot from Gboard's clipboard: saved into Linux
     * and pasted as its path, which agents attach. A paste rather than typing, since they only
     * take a path that comes as one. [done] runs once it's read.
     */
    fun insertImage(
        uri: Uri,
        mime: String,
        done: () -> Unit,
    ) = insertImages(listOf(uri), mime, done)

    /** Images picked with [pickImages], pasted as [insertImage] does, their paths apart by spaces. */
    fun insertImages(
        uris: List<Uri>,
        mime: String? = null,
        done: () -> Unit = {},
    ) {
        thread(name = "keyboard-image", isDaemon = true) {
            val paths = uris.mapNotNull { images.save(it, mime)?.let(images::guestPath) }
            done()
            if (paths.isEmpty()) return@thread
            wm.server.insertText(paths.joinToString(" "))
            main.post { tap(Keys.KEY_INSERT, Keys.KEY_LEFTSHIFT) }
        }
    }

    /** Android's photo picker for the activity in front, home's or a Linux window's; set as it resumes. */
    var imagePicker: ImagePicker? = null

    /** pickImages(): Android's photo picker, whose picks go into the window that has the keyboard, see [ImagePicker]. */
    fun pickImages() {
        imagePicker?.launch()
    }

    private val main = Handler(Looper.getMainLooper())

    /** Presses and releases [code], with [held] and any latched modifiers held around it. */
    fun tap(
        code: Int,
        held: Int? = null,
    ) = withLatched {
        held?.let { key(it, true) }
        key(code, true)
        key(code, false)
        held?.let { key(it, false) }
    }

    /** `pressKey("ctrl+c")`: the key [combo] names, with its modifiers and any latched ones held around it. */
    fun press(combo: KeyNames.Combo) {
        // A latched one is held already.
        val held = combo.held.filter { code -> sticky.none { it.code == code && it.latch != Latch.Off } }
        withLatched {
            held.forEach { key(it, true) }
            key(combo.code, true)
            key(combo.code, false)
            held.asReversed().forEach { key(it, false) }
        }
    }

    /** `latch("ctrl", how)`: on for the next key ("once"), until set off ("locked"), or "off"; null toggles once and off. */
    fun latch(
        name: String,
        how: String?,
    ) {
        val m = latches[name] ?: return
        m.latch =
            when (how) {
                "once" -> Latch.Once
                "locked" -> Latch.Locked
                "off" -> Latch.Off
                else -> if (m.latch == Latch.Off) Latch.Once else Latch.Off
            }
    }

    /** Drops every latch, so a modifier left on doesn't surprise the next keystroke. */
    fun clearLatches() = sticky.forEach { it.latch = Latch.Off }

    private fun latched() = sticky.any { it.latch != Latch.Off }

    private inline fun withLatched(block: () -> Unit) {
        val held = sticky.filter { it.latch != Latch.Off }
        held.forEach { key(it.code, true) }
        block()
        held.forEach { key(it.code, false) }
        held.filter { it.latch == Latch.Once }.forEach { it.latch = Latch.Off }
    }

    /** Modifiers held for a key's meta state alone, their own press never seen. */
    private val implied = mutableSetOf<Int>()

    /**
     * Holds the Shift, Ctrl and Alt that [metaState] says are down but whose press never came, until the key they
     * modify is released. Some keyboard apps keep a hardware Shift for themselves, like FlorisBoard, which uses it for
     * its own shift key: only the shifted letter comes through, and without this Shift+X typed x (#160). Others send
     * a Ctrl+C as one key with Ctrl in its meta state.
     */
    private fun holdImplied(metaState: Int) {
        for ((mask, left, right) in impliedModifiers) {
            if (metaState and mask != 0 && left !in down && right !in down) {
                key(left, true)
                implied += left
            }
        }
    }

    /** Lifts the modifiers [holdImplied] pressed, so none stays held for the next key or the soft keyboard's text. */
    private fun liftImplied() = implied.toList().forEach { key(it, false) }

    /** Lifts every key the window was told is down, as it loses the keys with some still held. */
    fun releaseHeld() = down.toList().forEach { key(it, false) }

    /** The keys pressed and not released yet, as the window was told. */
    private val down = mutableSetOf<Int>()

    private fun key(
        code: Int,
        pressed: Boolean,
    ) {
        if (pressed) {
            down += code
        } else {
            down -= code
            implied -= code
        }
        wm.server.postInput { key(code, pressed) }
    }
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

    /** Whether a soft keyboard is on screen now; Back closes it without [hide], so [wanted] can say otherwise. */
    val showing get() = ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    // Focusable only while it has the keyboard: one that always is takes the window's focus on its own, and the keys'
    // arrows then never reach home's tiles.
    init {
        isFocusable = false
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
        // So Gboard's clipboard and screenshot suggestions can insert an image, see [KeyboardBridge.insertImage].
        outAttrs.contentMimeTypes = arrayOf("image/*")
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

            override fun commitContent(
                info: InputContentInfo,
                flags: Int,
                opts: Bundle?,
            ): Boolean {
                val description = info.description
                val mime = (0 until description.mimeTypeCount).map(description::getMimeType).firstOrNull { it.startsWith("image/") }
                if (mime == null) return false
                if (flags and INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) {
                    runCatching { info.requestPermission() }.onFailure { return false }
                }
                bridge.insertImage(info.contentUri, mime) { info.releasePermission() }
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
        isFocusable = true
        isFocusableInTouchMode = true
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
        clearFocus()
        isFocusable = false
    }
}

private val cjkLanguages = setOf("zh", "ja", "ko")

/** Each modifier's meta state bit with its left and right evdev codes. */
private val impliedModifiers =
    listOf(
        Triple(KeyEvent.META_SHIFT_ON, Keys.KEY_LEFTSHIFT, 54),
        Triple(KeyEvent.META_CTRL_ON, Keys.KEY_LEFTCTRL, 97),
        Triple(KeyEvent.META_ALT_ON, Keys.KEY_LEFTALT, 100),
    )

private val volumeKeys = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)
