package dev.omalauncher.widgets.render

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.TileField
import dev.omalauncher.ui.keyStepped
import dev.omalauncher.ui.leavesUpDown
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.num
import kotlinx.coroutines.delay
import kotlin.math.roundToLong
import dev.omalauncher.widgets.lang.textOrNull

/**
 * A horizontal slider. It shows `value` until a finger is on it, then the
 * finger's position; `onChange` gets the new value when the finger lifts (or,
 * with `live: true`, at every step while dragging). After letting go it holds
 * the chosen value for a moment, so a source that catches up later (the
 * phone's volume) doesn't make the thumb jump back first.
 */
@Composable
internal fun SliderNode(
    p: Props,
    mod: Modifier,
    sized: Boolean,
) {
    // Finite, or a step rounds NaN in at(), which throws.
    val min = p.observe("min")?.num()?.takeIf { it.isFinite() } ?: 0.0
    val max = (p.observe("max")?.num()?.takeIf { it.isFinite() } ?: 100.0).let { if (it <= min) min + 1 else it }
    val step = p.observe("step")?.num()?.takeIf { it > 0 }
    val live = p.observe("live")?.truthy == true
    val value = p.observe("value")?.num()
    val color = p.color("color") { Palette.accent }
    val track = p.color("track") { Palette.surface }
    val onChange = p.el.handlers["onChange"]

    var held by remember { mutableStateOf<Double?>(null) }
    var touching by remember { mutableStateOf(false) }
    val sent = remember { arrayOfNulls<Double>(1) }
    LaunchedEffect(value) { if (!touching) held = null }
    LaunchedEffect(touching, held) {
        if (!touching && held != null) {
            delay(1500)
            held = null
        }
    }

    fun at(
        x: Float,
        width: Int,
    ): Double {
        val f = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        val v = min + f * (max - min)
        return if (step == null) v else (min + ((v - min) / step).roundToLong() * step).coerceIn(min, max)
    }

    fun send(v: Double) {
        if (sent[0] == v) return
        sent[0] = v
        onChange?.run(p.env, listOf(Num(v)))
    }

    Spacer(
        mod
            // A weight or width only sizes it across; it keeps its height unless given one.
            .then(if (sized) Modifier else Modifier.fillMaxWidth())
            .then(if (p.has("height") || p.has("size") || p.has("fill")) Modifier else Modifier.height(28.dp))
            // Keys step it by its step, else a twentieth of its range or with Shift a hundredth, sending each one: a
            // key has no lift to wait for. At either end the arrow moves on.
            .then(
                if (onChange == null) {
                    Modifier
                } else {
                    Modifier.keyStepped(vertical = false) { by, fine ->
                        val from = held ?: value ?: min
                        val to = (from + by * (step ?: ((max - min) / if (fine) 100 else 20))).coerceIn(min, max)
                        if (to == from) return@keyStepped false
                        held = to
                        send(to)
                        true
                    }
                },
            ).pointerInput(p.el, p.env, min, max, step) {
                detectTapGestures { o ->
                    sent[0] = null
                    held = at(o.x, size.width).also(::send)
                }
            }.pointerInput(p.el, p.env, min, max, step, live) {
                // Consumed, so dragging the thumb doesn't also turn the home screen's page.
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        touching = true
                        sent[0] = null
                        held = at(o.x, size.width).also { if (live) send(it) }
                    },
                    onDragEnd = {
                        touching = false
                        held?.let(::send)
                    },
                    onDragCancel = { touching = false },
                ) { change, _ ->
                    change.consume()
                    held = at(change.position.x, size.width).also { if (live) send(it) }
                }
            }.drawBehind {
                val v = held ?: value ?: min
                val f = ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)
                val line = 4.dp.toPx()
                val thumbW = 10.dp.toPx()
                val thumbH = minOf(size.height, 20.dp.toPx())
                val cy = size.height / 2
                drawRect(track(), Offset(0f, cy - line / 2), Size(size.width, line))
                drawRect(color(), Offset(0f, cy - line / 2), Size(size.width * f, line))
                val r = CornerRadius(3.dp.toPx() * LauncherStyle.cornerScale)
                drawRoundRect(color(), Offset((size.width - thumbW) * f, cy - thumbH / 2), Size(thumbW, thumbH), r)
            },
    )
}

/**
 * Up and Down leave a field of more lines only from its first or last line, so in between they move through the text.
 * [state] is the field's laid-out text and selection, read as a key comes.
 */
@Composable
private fun Modifier.leavesFromEdgeLines(state: () -> Pair<TextLayoutResult?, TextRange>): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val (layout, selection) = state()
        val line = layout?.getLineForOffset(selection.end.coerceIn(0, layout.layoutInput.text.length))
        when (e.key) {
            Key.DirectionUp -> line == 0 && focusManager.moveFocus(FocusDirection.Up)
            Key.DirectionDown -> layout != null && line == layout.lineCount - 1 && focusManager.moveFocus(FocusDirection.Down)
            else -> false
        }
    }
}

/**
 * A text field, one line unless `lines` says more (it wraps and grows to that many, then scrolls). `text` sets what it shows, and replaces what's
 * typed whenever it changes; `onChange` gets the text as it's typed and
 * `onSubmit` when the keyboard's done key is pressed, both as `value`.
 * `clear: true` empties it after a submit. `underline: false` leaves out its line, for a field framed by what's around it. `password: true` shows dots
 * and asks the keyboard for a password, which it neither suggests nor learns. `focus: true` focuses it, raising
 * the keyboard, when the screen opens or when `focus` turns true; only on a
 * screen, see [LocalInputFocus].
 */
@Composable
internal fun InputNode(
    p: Props,
    mod: Modifier,
    sized: Boolean,
) {
    val given = p.observe("text").textOrNull()
    val placeholder = p.observe("placeholder")?.toString().orEmpty()
    val clear = p.observe("clear")?.truthy == true
    val underline = p.observe("underline")?.truthy != false
    val password = p.observe("password")?.truthy == true
    // Lines it grows to as what's typed wraps, then scrolls inside (owl 18); a password stays on one.
    val lines = if (password) 1 else p.observe("lines")?.num()?.toInt()?.coerceIn(1, 100) ?: 1
    val multiLine = lines > 1
    val color = p.color("color") { Palette.foreground }
    val style = WidgetMono.copy(fontSize = p.observe("fontSize")?.num()?.sp ?: WidgetMono.fontSize)
    val onChange = p.el.handlers["onChange"]
    val onSubmit = p.el.handlers["onSubmit"]
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val inputMode = LocalInputModeManager.current
    val want = LocalInputFocus.current && p.observe("focus")?.truthy == true
    val requester = remember { FocusRequester() }
    LaunchedEffect(want) { if (want) requester.requestFocus() }

    var field by remember(p.el, p.env) { mutableStateOf(TextFieldValue(given.orEmpty())) }
    var focused by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // On a home tile: home lifts the tile above the keyboard while this has it, and takes the focus back on a tap outside.
    val tiles = LocalHomeTiles.current
    val tile = remember(tiles, p.inst, focus, keyboard) {
        tiles?.let {
            TileField(p.inst) {
                focus.clearFocus()
                // The terminal's KeyboardSink did not open this field's IME, so its hide() is a no-op.
                keyboard?.hide()
            }
        }
    }
    DisposableEffect(tile) { onDispose { tile?.let { tiles?.unfocused(it) } } }
    LaunchedEffect(given) { if (given != null && given != field.text) field = TextFieldValue(given, TextRange(given.length)) }

    fun edit(v: TextFieldValue) {
        val changed = v.text != field.text
        field = v
        if (changed) onChange?.run(p.env, listOf(Str(v.text)))
    }

    fun submit() {
        onSubmit?.run(p.env, listOf(Str(field.text)))
        if (clear) edit(TextFieldValue(""))
        // Typed on a keyboard, the keys stay here, as on a soft keyboard Done puts it away.
        if (inputMode.inputMode != InputMode.Keyboard) focus.clearFocus()
    }

    BasicTextField(
        value = field,
        onValueChange = ::edit,
        modifier =
            (if (sized) mod else mod.fillMaxWidth())
                .then(if (multiLine) Modifier.leavesFromEdgeLines { layout to field.selection } else Modifier.leavesUpDown())
                // Enter sends where there's onSubmit, so Shift+Enter is the new line, as in chat apps. A keyboard's Enter has
                // to be caught here: a field of more lines takes it as a new line and never as the keyboard's action.
                .onPreviewKeyEvent { e ->
                    val enter = multiLine && onSubmit != null && (e.key == Key.Enter || e.key == Key.NumPadEnter)
                    if (enter && e.type == KeyEventType.KeyDown) {
                        if (e.isShiftPressed) {
                            val sel = field.selection
                            edit(TextFieldValue(field.text.replaceRange(sel.min, sel.max, "\n"), TextRange(sel.min + 1)))
                        } else {
                            submit()
                        }
                    }
                    enter
                }
                .focusRequester(requester)
                .onFocusChanged {
                    focused = it.isFocused
                    tile?.let { t -> if (focused) tiles?.focused(t) else tiles?.unfocused(t) }
                }
                .drawBehind {
                    if (!underline) return@drawBehind
                    val w = 1.dp.toPx()
                    drawRect(if (focused) Palette.accent else Palette.border, Offset(0f, size.height - w), Size(size.width, w))
                },
        singleLine = !multiLine,
        maxLines = lines,
        onTextLayout = { layout = it },
        textStyle = style.copy(color = color()),
        cursorBrush = SolidColor(Palette.accent),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Unspecified,
                // Without onSubmit, a field of more lines keeps the keyboard's Enter, for new lines.
                imeAction = if (onSubmit != null) ImeAction.Send else if (multiLine) ImeAction.Default else ImeAction.Done,
            ),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardActions =
            KeyboardActions(onAny = { submit() }),
        decorationBox = { inner ->
            Box(Modifier.padding(vertical = 6.dp)) {
                if (field.text.isEmpty()) BasicText(placeholder, style = style, maxLines = lines, color = ColorProducer { Palette.muted })
                inner()
            }
        },
    )
}
