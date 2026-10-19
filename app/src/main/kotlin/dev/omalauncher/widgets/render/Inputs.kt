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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.TileField
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.num
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

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
            .pointerInput(p.el, p.env, min, max, step) {
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
 * A one-line text field. `text` sets what it shows, and replaces what's
 * typed whenever it changes; `onChange` gets the text as it's typed and
 * `onSubmit` when the keyboard's done key is pressed, both as `value`.
 * `clear: true` empties it after a submit. `underline: false` leaves out its line, for a field framed by what's around it. `focus: true` focuses it, raising
 * the keyboard, when the screen opens or when `focus` turns true; only on a
 * screen, see [LocalInputFocus].
 */
@Composable
internal fun InputNode(
    p: Props,
    mod: Modifier,
    sized: Boolean,
) {
    val given = p.observe("text")?.takeIf { it != Null }?.toString()
    val placeholder = p.observe("placeholder")?.toString().orEmpty()
    val clear = p.observe("clear")?.truthy == true
    val underline = p.observe("underline")?.truthy != false
    val color = p.color("color") { Palette.foreground }
    val style = WidgetMono.copy(fontSize = p.observe("fontSize")?.num()?.sp ?: WidgetMono.fontSize)
    val onChange = p.el.handlers["onChange"]
    val onSubmit = p.el.handlers["onSubmit"]
    val focus = LocalFocusManager.current
    val want = LocalInputFocus.current && p.observe("focus")?.truthy == true
    val requester = remember { FocusRequester() }
    LaunchedEffect(want) { if (want) requester.requestFocus() }

    var field by remember(p.el, p.env) { mutableStateOf(TextFieldValue(given.orEmpty())) }
    var focused by remember { mutableStateOf(false) }
    // On a home tile: home lifts the tile above the keyboard while this has it, and takes the focus back on a tap outside.
    val tiles = LocalHomeTiles.current
    val tile = remember(tiles, p.inst) { tiles?.let { TileField(p.inst) { focus.clearFocus() } } }
    DisposableEffect(tile) { onDispose { tile?.let { tiles?.unfocused(it) } } }
    LaunchedEffect(given) { if (given != null && given != field.text) field = TextFieldValue(given, TextRange(given.length)) }

    fun edit(v: TextFieldValue) {
        val changed = v.text != field.text
        field = v
        if (changed) onChange?.run(p.env, listOf(Str(v.text)))
    }

    BasicTextField(
        value = field,
        onValueChange = ::edit,
        modifier =
            (if (sized) mod else mod.fillMaxWidth())
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
        singleLine = true,
        textStyle = style.copy(color = color()),
        cursorBrush = SolidColor(Palette.accent),
        keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Send else ImeAction.Done),
        keyboardActions =
            KeyboardActions(onAny = {
                onSubmit?.run(p.env, listOf(Str(field.text)))
                if (clear) edit(TextFieldValue(""))
                focus.clearFocus()
            }),
        decorationBox = { inner ->
            Box(Modifier.padding(vertical = 6.dp)) {
                if (field.text.isEmpty()) BasicText(placeholder, style = style, maxLines = 1, color = ColorProducer { Palette.muted })
                inner()
            }
        },
    )
}
