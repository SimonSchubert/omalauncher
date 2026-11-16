package dev.omalauncher.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import dev.omalauncher.wayland.AXIS_SOURCE_FINGER
import dev.omalauncher.wayland.AXIS_SOURCE_WHEEL
import dev.omalauncher.wayland.BTN_LEFT
import dev.omalauncher.wayland.BTN_MIDDLE
import dev.omalauncher.wayland.BTN_RIGHT
import dev.omalauncher.wayland.SurfaceHit
import dev.omalauncher.wayland.XdgToplevel
import kotlin.math.roundToInt

private const val WHEEL_STEP = 15f

/**
 * Routes pointer input over a Linux window's view to its surfaces. Mouse input
 * is forwarded as-is. Touch is mapped for a phone: tap = left click, long
 * press = right click, drag = scroll; clients with wl_touch get real touch.
 * A tap on the row of the window's text cursor (a terminal's prompt) also
 * calls [onCursorTap], after the program gets it.
 */
fun Modifier.linuxWindowInput(
    wm: WindowManager,
    window: XdgToplevel,
    onCursorTap: () -> Unit = {},
) = pointerInput(wm, window) {
    handlePointer(wm, window, viewConfiguration.touchSlop, viewConfiguration.longPressTimeoutMillis, onCursorTap)
}

private fun XdgToplevel.hitTest(
    x: Float,
    y: Float,
) = renderer?.hitTest(x, y)

private suspend fun PointerInputScope.handlePointer(
    wm: WindowManager,
    window: XdgToplevel,
    touchSlop: Float,
    longPressMs: Long,
    onCursorTap: () -> Unit,
) = awaitPointerEventScope {
    var mouseButtons = 0
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull() ?: continue
        if (change.type == PointerType.Mouse) {
            mouseButtons = handleMouse(wm, window, event, mouseButtons)
            change.consume()
            continue
        }
        if (event.type != PointerEventType.Press || change.isConsumed) continue

        val start = change.position
        val hit = window.hitTest(start.x, start.y)
        change.consume()
        if (!wm.onPress(window, hit) || hit == null) continue

        // Clients with wl_touch (foot, GTK) get real touch: foot turns hold-and-drag into selection.
        if (wm.wantsTouch(hit.surface)) {
            val id = change.id.value.toInt()
            wm.touchDown(hit, id)
            var moved = false
            var tapped = false
            while (true) {
                val next = awaitPointerEvent()
                val c = next.changes.firstOrNull { it.id == change.id } ?: break
                // Taken by the home screen (a long press lifts a tile): the touch ends here.
                if (c.isConsumed) break
                if (!c.pressed) {
                    tapped = !moved && c.uptimeMillis - change.uptimeMillis < longPressMs
                    break
                }
                c.consume()
                if ((c.position - start).getDistance() > touchSlop) moved = true
                val d = (c.position - start) / hit.scale
                wm.touchMotion(id, hit.x + d.x, hit.y + d.y)
            }
            wm.touchUp(id)
            if (tapped && wm.onTextCursorRow(hit)) onCursorTap()
            continue
        }

        var scrolling = false
        var longPressed = false
        var last = start
        while (true) {
            val next =
                if (!scrolling && !longPressed) {
                    withTimeoutOrNull(longPressMs) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
            if (next == null) {
                longPressed = true
                wm.click(hit, BTN_RIGHT)
                continue
            }
            val c = next.changes.firstOrNull { it.id == change.id } ?: break
            if (c.isConsumed) {
                if (scrolling) wm.scrollStop()
                break
            }
            c.consume()
            if (!c.pressed) {
                if (scrolling) {
                    wm.scrollStop()
                } else if (!longPressed) {
                    wm.click(hit, BTN_LEFT)
                    if (wm.onTextCursorRow(hit)) onCursorTap()
                }
                break
            }
            if (!scrolling && !longPressed && (c.position - start).getDistance() > touchSlop) {
                scrolling = true
                wm.pointerMove(hit)
            }
            if (scrolling) {
                val d = c.position - last
                wm.scroll(-d.x / hit.scale, -d.y / hit.scale, AXIS_SOURCE_FINGER)
            }
            last = c.position
        }
    }
}

private fun handleMouse(
    wm: WindowManager,
    window: XdgToplevel,
    event: PointerEvent,
    previousButtons: Int,
): Int {
    val change = event.changes.first()
    when (event.type) {
        PointerEventType.Exit -> {
            wm.pointerMove(null)
            return previousButtons
        }
        PointerEventType.Scroll -> {
            val d = change.scrollDelta
            wm.scroll(d.x * WHEEL_STEP, d.y * WHEEL_STEP, AXIS_SOURCE_WHEEL, d.y.roundToInt())
            return previousButtons
        }
        else -> Unit
    }
    val hit: SurfaceHit? = window.hitTest(change.position.x, change.position.y)
    val buttons =
        (if (event.buttons.isPrimaryPressed) 1 else 0) or
            (if (event.buttons.isSecondaryPressed) 2 else 0) or
            (if (event.buttons.isTertiaryPressed) 4 else 0)
    val pressed = buttons and previousButtons.inv()
    if (pressed != 0 && !wm.onPress(window, hit)) return buttons
    // Keep the grab on the pressed surface while dragging.
    if (previousButtons == 0 || wm.server.input.pointerFocus == hit?.surface) wm.pointerMove(hit)
    for ((bit, code) in BUTTONS) {
        if (pressed and bit != 0) wm.pointerButton(code, true)
        if (previousButtons and bit != 0 && buttons and bit == 0) wm.pointerButton(code, false)
    }
    return buttons
}

private val BUTTONS = listOf(1 to BTN_LEFT, 2 to BTN_RIGHT, 4 to BTN_MIDDLE)
