package dev.omalauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import dev.omalauncher.wayland.AXIS_SOURCE_FINGER
import dev.omalauncher.wayland.BTN_LEFT
import dev.omalauncher.wayland.BTN_MIDDLE
import dev.omalauncher.wayland.BTN_RIGHT
import dev.omalauncher.wayland.XdgToplevel
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Touchpad mode for one window: the screen moves a cursor instead of touching
 * where the finger is. One finger moves it, a tap clicks where it is, tap then
 * touch and drag drags with the left button; two fingers scroll, or right-click
 * with a tap; three drag with the middle button (pan in 3D views), or
 * middle-click with a tap. Desktop apps built for a mouse (slicers, CAD) then
 * work without a mouse. A finger landing outside a menu doesn't close it; a
 * click outside it does, as with a mouse.
 */
class TouchpadState(
    on: Boolean,
) {
    var on by mutableStateOf(on)

    /** Where the cursor is, in the window view's pixels; null until first used. */
    var cursor by mutableStateOf<Offset?>(null)

    /** The window view's size, which the cursor stays inside. */
    var bounds = IntSize.Zero

    internal var lastTapUp = 0L
    internal var lastTapValid = false
}

private const val TAP_MS = 220L
private const val TAP_GAP_MS = 300L
private const val SPEED = 1.2f
private const val ACCEL = 0.8f

/** Handles one touch sequence, from its first finger down to its last up. */
internal suspend fun AwaitPointerEventScope.touchpadGesture(
    wm: WindowManager,
    window: XdgToplevel,
    state: TouchpadState,
    first: PointerEvent,
    touchSlop: Float,
) {
    val scale = wm.scale.toFloat()
    val start = first.changes.first().uptimeMillis
    var lastTime = start
    var maxFingers = 0
    var travel = 0f
    var scrolled = false
    var held = 0
    val cursor0 = state.cursor ?: Offset(state.bounds.width / 2f, state.bounds.height / 2f).also { state.cursor = it }

    fun hitAtCursor() = state.cursor?.let { window.renderer?.hitTest(it.x, it.y) }

    fun press(button: Int): Boolean {
        val hit = hitAtCursor()
        // Focus, and a press outside a menu closes it, as a mouse click there would.
        if (!wm.onPress(window, hit) || hit == null) return false
        wm.pointerMove(hit)
        wm.pointerButton(button, true)
        held = button
        return true
    }

    fun release() {
        if (held != 0) wm.pointerButton(held, false)
        held = 0
    }

    fun click(button: Int) {
        if (press(button)) release()
    }

    fun move(
        d: Offset,
        dt: Long,
    ) {
        val speed = SPEED * (1 + ACCEL * min(d.getDistance() / scale / max(dt, 1L), 2.5f))
        val c = (state.cursor ?: cursor0) + d * speed
        val clamped = Offset(c.x.coerceIn(0f, state.bounds.width - 1f), c.y.coerceIn(0f, state.bounds.height - 1f))
        state.cursor = clamped
        wm.pointerMove(window.renderer?.hitTest(clamped.x, clamped.y))
    }

    wm.pointerMove(hitAtCursor())
    // Tap, then touch again soon after: the left button stays down for a drag, or makes a double click.
    val dragging = state.lastTapValid && start - state.lastTapUp < TAP_GAP_MS && press(BTN_LEFT)
    var event = first
    while (true) {
        val fingers = event.changes.count { it.pressed }
        maxFingers = max(maxFingers, fingers)
        if (fingers >= 2 && held == BTN_LEFT && dragging) release()
        val moving = event.changes.filter { it.pressed && it.previousPressed }
        val time = event.changes.maxOf { it.uptimeMillis }
        if (moving.isNotEmpty()) {
            val d = moving.fold(Offset.Zero) { acc, c -> acc + (c.position - c.previousPosition) } / moving.size.toFloat()
            travel += hypot(d.x, d.y)
            when {
                maxFingers == 1 -> move(d, time - lastTime)
                maxFingers == 2 && travel > touchSlop -> {
                    scrolled = true
                    wm.scroll(-d.x / scale, -d.y / scale, AXIS_SOURCE_FINGER)
                }
                maxFingers >= 3 && travel > touchSlop -> {
                    if (held == 0) press(BTN_MIDDLE)
                    move(d, time - lastTime)
                }
            }
        }
        lastTime = time
        event.changes.forEach { it.consume() }
        if (fingers == 0) break
        event = awaitPointerEvent()
    }

    val tap = lastTime - start < TAP_MS && travel < touchSlop
    state.lastTapValid = false
    when {
        maxFingers == 1 && held == BTN_LEFT -> {
            release()
            // The second tap of a double click can start a triple.
            state.lastTapValid = tap
        }
        maxFingers == 1 && tap -> {
            click(BTN_LEFT)
            state.lastTapValid = true
        }
        maxFingers == 2 -> if (scrolled) wm.scrollStop() else if (lastTime - start < TAP_MS * 3 / 2) click(BTN_RIGHT)
        maxFingers >= 3 -> if (held != 0) release() else if (lastTime - start < TAP_MS * 3 / 2) click(BTN_MIDDLE)
    }
    release()
    state.lastTapUp = lastTime
}

private val ARROW = listOf(0f to 0f, 0f to 16f, 4f to 12.5f, 6.8f to 18.6f, 9.2f to 17.6f, 6.5f to 11.6f, 11.5f to 11.6f)

/** The cursor: an arrow with its tip at [TouchpadState.cursor]. */
@Composable
fun TouchpadCursor(
    state: TouchpadState,
    modifier: Modifier = Modifier,
) {
    val at = state.cursor
    if (!state.on || at == null) return
    val density = LocalDensity.current.density
    Canvas(modifier) {
        val path = Path().apply {
            ARROW.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * density, y * density) else lineTo(x * density, y * density) }
            close()
        }
        translate(at.x, at.y) {
            drawPath(path, Color.White)
            drawPath(path, Color.Black, style = Stroke(width = 1.2f * density))
        }
    }
}
