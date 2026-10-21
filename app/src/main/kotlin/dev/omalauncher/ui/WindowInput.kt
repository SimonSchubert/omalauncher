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
import dev.omalauncher.wayland.Input
import dev.omalauncher.wayland.SurfaceHit
import dev.omalauncher.wayland.XdgToplevel
import kotlin.math.roundToInt

private const val WHEEL_STEP = 15f

/**
 * Routes pointer input over a Linux window's view to its surfaces. Mouse input
 * is forwarded as-is. Touch is mapped for a phone: tap = left click, long
 * press = right click, drag = scroll; clients with wl_touch get real touch.
 */
fun Modifier.linuxWindowInput(
    wm: WindowManager,
    window: XdgToplevel,
) = pointerInput(wm, window) {
    handlePointer(wm, window, viewConfiguration.touchSlop, viewConfiguration.longPressTimeoutMillis)
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
) = awaitPointerEventScope {
    var mouseButtons = 0
    var lease = wm.inputLease(window)
    val input = wm.server.input
    try {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            if (!lease.open) {
                mouseButtons = 0
                // A new press may take focus again; stale motion from the old gesture cannot.
                if (event.type != PointerEventType.Press) continue
                lease = wm.inputLease(window)
            }
            if (change.type == PointerType.Mouse) {
                mouseButtons = handleMouse(wm, window, event, mouseButtons, lease)
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
                lease.post { input.touchDown(hit.surface, id, hit.x, hit.y) }
                try {
                    while (true) {
                        val next = awaitPointerEvent()
                        val c = next.changes.firstOrNull { it.id == change.id } ?: break
                        // Taken by the home screen (a long press lifts a tile): the touch ends here.
                        if (c.isConsumed || !c.pressed) break
                        c.consume()
                        val d = (c.position - start) / wm.scale.toFloat()
                        lease.post { input.touchMotion(id, hit.x + d.x, hit.y + d.y) }
                    }
                } finally {
                    lease.post { input.touchUp(id) }
                }
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
                    lease.post { input.click(hit, BTN_RIGHT) }
                    continue
                }
                val c = next.changes.firstOrNull { it.id == change.id } ?: break
                if (c.isConsumed) {
                    if (scrolling) lease.post { input.pointerAxisStop() }
                    break
                }
                c.consume()
                if (!c.pressed) {
                    if (scrolling) lease.post { input.pointerAxisStop() }
                    else if (!longPressed) lease.post { input.click(hit, BTN_LEFT) }
                    break
                }
                if (!scrolling && !longPressed && (c.position - start).getDistance() > touchSlop) {
                    scrolling = true
                    lease.post { input.pointerMotion(hit.surface, hit.x, hit.y) }
                }
                if (scrolling) {
                    val d = c.position - last
                    lease.post { input.pointerAxis(-d.x / wm.scale, -d.y / wm.scale, AXIS_SOURCE_FINGER) }
                }
                last = c.position
            }
        }
    } finally {
        // Includes pointerInput cancellation on Back, page change, view removal and Android focus loss.
        lease.close()
    }
}

private fun handleMouse(
    wm: WindowManager,
    window: XdgToplevel,
    event: PointerEvent,
    previousButtons: Int,
    lease: InputLease,
): Int {
    val input = wm.server.input
    val change = event.changes.first()
    when (event.type) {
        PointerEventType.Exit -> {
            if (previousButtons == 0) lease.post { input.pointerMotion(null, 0f, 0f) }
            return previousButtons
        }
        PointerEventType.Scroll -> {
            val d = change.scrollDelta
            lease.post { input.pointerAxis(d.x * WHEEL_STEP, d.y * WHEEL_STEP, AXIS_SOURCE_WHEEL, d.y.roundToInt()) }
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
    lease.post {
        // Keep the grab on the pressed surface while dragging.
        if (previousButtons == 0 || input.pointerFocus == hit?.surface) input.pointerMotion(hit?.surface, hit?.x ?: 0f, hit?.y ?: 0f)
        for ((bit, code) in BUTTONS) {
            if (pressed and bit != 0) input.pointerButton(code, true)
            if (previousButtons and bit != 0 && buttons and bit == 0) input.pointerButton(code, false)
        }
    }
    return buttons
}

private val BUTTONS = listOf(1 to BTN_LEFT, 2 to BTN_RIGHT, 4 to BTN_MIDDLE)

private fun Input.click(hit: SurfaceHit, button: Int) {
    pointerMotion(hit.surface, hit.x, hit.y)
    pointerButton(button, true)
    pointerButton(button, false)
}
