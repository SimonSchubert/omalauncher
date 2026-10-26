package dev.omalauncher.ui

import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * An on-screen game pad over a Linux window, laid out like a Game Boy: a
 * D-pad, A and B, Select and Start. Phone keyboards can't hold a key (each
 * press comes as a press and release at once); games steer and run for as
 * long as one is down. Each control holds its key while a finger is on it,
 * several at once, and the keys are the user's to choose per app.
 */
enum class PadButton(
    val label: String,
    val defaultKey: Int,
) {
    Up("↑", 103),
    Down("↓", 108),
    Left("←", 105),
    Right("→", 106),
    A("A", 57), // Space
    B("B", 28), // Enter
    Select("SELECT", 15), // Tab
    Start("START", 1), // Esc
}

/** Whether the pad shows over an app's windows, and its keys; kept per app id. */
class GamePadState(
    private val prefs: SharedPreferences,
    private val appId: String,
) {
    var on by mutableStateOf(prefs.getBoolean("gamepad_$appId", false))
        private set

    /** Evdev key codes by button. */
    val keys = mutableStateMapOf<PadButton, Int>().apply { putAll(parseKeys(prefs.getString("gamepad_keys_$appId", null))) }

    var editing by mutableStateOf(false)

    fun toggle() {
        on = !on
        editing = false
        prefs.edit().putBoolean("gamepad_$appId", on).apply()
    }

    fun setKey(
        button: PadButton,
        code: Int,
    ) {
        keys[button] = code
        prefs.edit().putString("gamepad_keys_$appId", formatKeys(keys)).apply()
    }

    fun keyOf(button: PadButton) = keys[button] ?: button.defaultKey
}

internal fun parseKeys(saved: String?): Map<PadButton, Int> =
    saved.orEmpty().split(',').mapNotNull { entry ->
        val (name, code) = entry.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        val button = PadButton.entries.find { it.name == name } ?: return@mapNotNull null
        code.toIntOrNull()?.takeIf { KeyNames.name(it) != null }?.let { button to it }
    }.toMap()

internal fun formatKeys(keys: Map<PadButton, Int>) = keys.entries.joinToString(",") { "${it.key.name}=${it.value}" }

/** The keys a button can send: Linux evdev codes and what to call them. */
object KeyNames {
    /** A button that sends nothing: it's left off the pad while playing. */
    const val NONE = 0

    val all: List<Pair<String, Int>> =
        buildList {
            add("none" to NONE)
            add("↑" to 103)
            add("↓" to 108)
            add("←" to 105)
            add("→" to 106)
            add("Space" to 57)
            add("Enter" to 28)
            add("Esc" to 1)
            add("Tab" to 15)
            add("Bksp" to 14)
            add("Shift" to 42)
            add("Ctrl" to 29)
            add("Alt" to 56)
            add("PgUp" to 104)
            add("PgDn" to 109)
            add("Home" to 102)
            add("End" to 107)
            for ((row, start) in listOf("qwertyuiop" to 16, "asdfghjkl" to 30, "zxcvbnm" to 44)) {
                row.forEachIndexed { i, c -> add(c.uppercase() to start + i) }
            }
            for (d in 1..9) add("$d" to 1 + d)
            add("0" to 11)
            for (f in 1..10) add("F$f" to 58 + f)
            add("F11" to 87)
            add("F12" to 88)
        }

    private val byCode = all.associate { (name, code) -> code to name }

    fun name(code: Int) = byCode[code]
}

/** The directions a thumb at [offset] from the D-pad's centre presses, in eighths: diagonals hold two. */
internal fun dpadDirections(
    offset: Offset,
    radius: Float,
): Set<PadButton> {
    if (hypot(offset.x, offset.y) < radius * 0.2f) return emptySet()
    // 0° is right, counter-clockwise, screen y pointing down.
    val angle = (Math.toDegrees(atan2(-offset.y, offset.x).toDouble()) + 360) % 360
    return when (((angle + 22.5) / 45).toInt() % 8) {
        0 -> setOf(PadButton.Right)
        1 -> setOf(PadButton.Up, PadButton.Right)
        2 -> setOf(PadButton.Up)
        3 -> setOf(PadButton.Up, PadButton.Left)
        4 -> setOf(PadButton.Left)
        5 -> setOf(PadButton.Down, PadButton.Left)
        6 -> setOf(PadButton.Down)
        else -> setOf(PadButton.Down, PadButton.Right)
    }
}

/**
 * The pad, filling the window's view; only its controls take touches, so the
 * rest of the window still gets them. [onKey] gets evdev codes, down and up.
 * While editing, a tap on a control picks its key instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GamePad(
    state: GamePadState,
    onKey: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.on) return
    // What each control holds now, so a key is never left down: on release, on edit, and when the pad goes.
    val held = remember { mutableMapOf<PadButton, Int>() }
    fun press(
        button: PadButton,
        down: Boolean,
    ) {
        if (down) {
            if (button in held) return
            val code = state.keyOf(button).takeIf { it != KeyNames.NONE } ?: return
            held[button] = code
            onKey(code, true)
        } else {
            held.remove(button)?.let { onKey(it, false) }
        }
    }

    fun releaseAll() = held.keys.toList().forEach { press(it, false) }
    DisposableEffect(Unit) { onDispose { releaseAll() } }
    var picking by remember { mutableStateOf<PadButton?>(null) }
    LaunchedEffect(state.editing) { if (state.editing) releaseAll() }

    fun label(button: PadButton) = if (state.editing) KeyNames.name(state.keyOf(button)) ?: "?" else button.label

    Box(modifier.fillMaxSize()) {
        DPad(
            editing = state.editing,
            labels = { label(it) },
            onDirections = { now ->
                for (d in listOf(PadButton.Up, PadButton.Down, PadButton.Left, PadButton.Right)) press(d, d in now)
            },
            onEdit = { picking = it },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 40.dp),
        )
        // A up and to the right of B, as on a Game Boy.
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 40.dp).size(150.dp, 120.dp)) {
            PadKey(label(PadButton.B), Modifier.align(Alignment.BottomStart), state.editing, { press(PadButton.B, it) }, unmapped = state.keyOf(PadButton.B) == KeyNames.NONE) { picking = PadButton.B }
            PadKey(label(PadButton.A), Modifier.align(Alignment.TopEnd), state.editing, { press(PadButton.A, it) }, unmapped = state.keyOf(PadButton.A) == KeyNames.NONE) { picking = PadButton.A }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PadKey(label(PadButton.Select), Modifier, state.editing, { press(PadButton.Select, it) }, small = true, unmapped = state.keyOf(PadButton.Select) == KeyNames.NONE) { picking = PadButton.Select }
                PadKey(label(PadButton.Start), Modifier, state.editing, { press(PadButton.Start, it) }, small = true, unmapped = state.keyOf(PadButton.Start) == KeyNames.NONE) { picking = PadButton.Start }
            }
            // A pill like Select and Start, so it's found over a game; accent while remapping.
            val pill = RoundedCornerShape(50)
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .size(72.dp, 28.dp)
                    .background(Palette.overlay.copy(alpha = 0.6f), pill)
                    .border(1.5.dp, if (state.editing) Palette.accent else Palette.muted.copy(alpha = 0.6f), pill)
                    .clickable { state.editing = !state.editing },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    if (state.editing) "DONE" else "KEYS",
                    style = Mono.copy(color = if (state.editing) Palette.accent else Palette.foreground, fontSize = 10.sp),
                )
            }
        }
    }

    picking?.let { button ->
        KeyPicker(
            button = button,
            current = state.keyOf(button),
            onPick = { code ->
                state.setKey(button, code)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/** A round (or, [small], pill) button that holds its key while touched; not shown while playing when [unmapped]. */
@Composable
private fun PadKey(
    label: String,
    modifier: Modifier,
    editing: Boolean,
    onPress: (Boolean) -> Unit,
    small: Boolean = false,
    unmapped: Boolean = false,
    onEdit: () -> Unit,
) {
    if (unmapped && !editing) return
    var down by remember { mutableStateOf(false) }
    val shape = if (small) RoundedCornerShape(50) else CircleShape
    Box(
        modifier
            .size(if (small) 72.dp else 64.dp, if (small) 28.dp else 64.dp)
            .background(Palette.overlay.copy(alpha = if (down) 0.95f else 0.6f), shape)
            .border(1.5.dp, if (down) Palette.accent else Palette.muted.copy(alpha = 0.6f), shape)
            .pointerInput(editing) {
                awaitPointerEventScope {
                    while (true) {
                        val first = awaitPointerEvent().changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                        first.consume()
                        if (editing) {
                            onEdit()
                            continue
                        }
                        down = true
                        onPress(true)
                        // Held until that finger lifts, wherever it slid: a thumb drifts off a button in play.
                        while (true) {
                            val e = awaitPointerEvent()
                            e.changes.forEach { it.consume() }
                            if (e.changes.none { it.id == first.id && it.pressed }) break
                        }
                        down = false
                        onPress(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = Mono.copy(color = Palette.foreground, fontSize = if (small) 10.sp else 16.sp, textAlign = TextAlign.Center))
    }
}

/** One control for the four directions: a thumb anywhere on it presses the way it points, diagonals included. */
@Composable
private fun DPad(
    editing: Boolean,
    labels: (PadButton) -> String,
    onDirections: (Set<PadButton>) -> Unit,
    onEdit: (PadButton) -> Unit,
    modifier: Modifier,
) {
    var pressed by remember { mutableStateOf(emptySet<PadButton>()) }
    val size = 150.dp
    Box(
        modifier
            .size(size)
            .pointerInput(editing) {
                val radius = this.size.width / 2f
                val center = Offset(radius, this.size.height / 2f)
                awaitPointerEventScope {
                    val fingers = mutableMapOf<PointerId, Offset>()
                    while (true) {
                        val e = awaitPointerEvent()
                        for (c in e.changes) {
                            if (c.pressed) fingers[c.id] = c.position else fingers.remove(c.id)
                            c.consume()
                        }
                        if (editing) {
                            val tap = e.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                            // Only the four arms: a diagonal is two keys already chosen.
                            dpadDirections(tap.position - center, radius).singleOrNull()?.let(onEdit)
                            continue
                        }
                        val now = fingers.values.flatMap { dpadDirections(it - center, radius) }.toSet()
                        if (now != pressed) {
                            pressed = now
                            onDirections(now)
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val arm = this.size.width / 3
            val cross = Path().apply {
                moveTo(arm, 0f)
                lineTo(arm * 2, 0f)
                lineTo(arm * 2, arm)
                lineTo(arm * 3, arm)
                lineTo(arm * 3, arm * 2)
                lineTo(arm * 2, arm * 2)
                lineTo(arm * 2, arm * 3)
                lineTo(arm, arm * 3)
                lineTo(arm, arm * 2)
                lineTo(0f, arm * 2)
                lineTo(0f, arm)
                lineTo(arm, arm)
                close()
            }
            drawPath(cross, Palette.overlay.copy(alpha = 0.6f))
            for ((d, x, y) in listOf(Triple(PadButton.Up, 1, 0), Triple(PadButton.Down, 1, 2), Triple(PadButton.Left, 0, 1), Triple(PadButton.Right, 2, 1))) {
                if (d in pressed) drawRect(Palette.accent.copy(alpha = 0.5f), Offset(arm * x, arm * y), Size(arm, arm))
            }
            drawPath(cross, Palette.muted.copy(alpha = 0.6f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
        }
        val third = size / 3
        for ((d, x, y) in listOf(Triple(PadButton.Up, 1, 0), Triple(PadButton.Down, 1, 2), Triple(PadButton.Left, 0, 1), Triple(PadButton.Right, 2, 1))) {
            Box(Modifier.offset(third * x, third * y).size(third), contentAlignment = Alignment.Center) {
                BasicText(labels(d), style = Mono.copy(color = Palette.foreground, fontSize = if (editing) 11.sp else 16.sp))
            }
        }
    }
}

/** Every key a button can send; the current one is marked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyPicker(
    button: PadButton,
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Palette.background.copy(alpha = 0.85f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .background(Palette.surface, RoundedCornerShape(12.dp))
                .clickable(enabled = false) {}
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText("key for ${button.label}", style = Mono.copy(color = Palette.foreground, fontSize = 16.sp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((name, code) in KeyNames.all) {
                    val on = code == current
                    BasicText(
                        name,
                        Modifier
                            .border(1.dp, if (on) Palette.accent else Palette.muted.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .clickable { onPick(code) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        style = Mono.copy(color = if (on) Palette.accent else Palette.foreground),
                    )
                }
            }
        }
    }
}
