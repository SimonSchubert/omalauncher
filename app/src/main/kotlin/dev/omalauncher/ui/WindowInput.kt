package dev.omalauncher.ui

import android.os.SystemClock
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val WHEEL_STEP = 15f

/**
 * Tern binds wl_touch but does not turn finger drags into terminal scrolling.
 * Use the phone's tap/hold/drag-to-pointer mapping for it instead.
 */
internal fun prefersPointerGestures(appId: String): Boolean =
    appId == "tern" || appId == "so.stencil.tern"

/** The recognizer and session currently receiving touch, readable from the focus watcher. */
private class GestureHolder {
    var gesture: TernTouchGesture? = null
    var session: TernGestureSession? = null
}

/**
 * Routes pointer input over a Linux window's view to its surfaces. Mouse input
 * is forwarded as-is. Generic touch maps tap to left click, long press to
 * right click and drag to scroll; clients with wl_touch get real touch.
 * Tern instead gets native tap/select/scroll/pinch/tab gestures.
 */
fun Modifier.linuxWindowInput(
    wm: WindowManager,
    window: XdgToplevel,
) = pointerInput(wm, window) {
    coroutineScope {
        val holder = GestureHolder()
        // Focus can move to another window without another touch event ever arriving;
        // clean up the in-flight session the moment this window loses focus.
        val watcher =
            launch {
                snapshotFlow { wm.focused }.collect { focused ->
                    if (focused !== window) {
                        // Quarantine the claimed touch stream and drop the session immediately.
                        holder.gesture?.cancel()
                        holder.session?.invalidate()
                    }
                }
            }
        try {
            handlePointer(
                wm,
                window,
                viewConfiguration.touchSlop,
                viewConfiguration.longPressTimeoutMillis,
                density,
                holder,
            )
        } finally {
            watcher.cancel()
            holder.gesture?.cancel()
            holder.session?.invalidate()
        }
    }
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
    density: Float,
    holder: GestureHolder,
) = awaitPointerEventScope {
    var mouseButtons = 0
    while (true) {
        val event = awaitPointerEvent()
        // Any unconsumed mouse contact takes the ordinary mouse path, even when it rides
        // along with a touch event; handleMouse consumes exactly that contact.
        if (event.changes.any { it.type == PointerType.Mouse && !it.isConsumed }) {
            mouseButtons = handleMouse(wm, window, event, mouseButtons)
            continue
        }
        val change = event.changes.firstOrNull() ?: continue
        if (event.type != PointerEventType.Press || change.isConsumed) continue

        // Tern's native mapping is claimed before the raw-touch and generic paths,
        // which stay untouched for every other client. Only real touch claims it.
        if (change.type == PointerType.Touch && prefersPointerGestures(window.appId)) {
            mouseButtons =
                handleTernTouch(wm, window, event, density, size.width.toFloat(), touchSlop, longPressMs, holder, mouseButtons)
            continue
        }

        val start = change.position
        val hit = window.hitTest(start.x, start.y)
        change.consume()
        if (!wm.onPress(window, hit) || hit == null) continue

        // Binding wl_touch alone does not mean the client implements touch scrolling.
        // Keep native gestures for clients such as foot and GTK, but let Tern use drag-to-scroll.
        if (wm.wantsTouch(hit.surface)) {
            val id = change.id.value.toInt()
            wm.touchDown(hit, id)
            while (true) {
                val next = awaitPointerEvent()
                val c = next.changes.firstOrNull { it.id == change.id } ?: break
                // Taken by the home screen (a long press lifts a tile): the touch ends here.
                if (c.isConsumed || !c.pressed) break
                c.consume()
                val d = (c.position - start) / wm.scale.toFloat()
                wm.touchMotion(id, hit.x + d.x, hit.y + d.y)
            }
            wm.touchUp(id)
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
                if (scrolling) wm.scrollStop() else if (!longPressed) wm.click(hit, BTN_LEFT)
                break
            }
            if (!scrolling && !longPressed && (c.position - start).getDistance() > touchSlop) {
                scrolling = true
                wm.pointerMove(hit)
            }
            if (scrolling) {
                val d = c.position - last
                wm.scroll(-d.x / wm.scale, -d.y / wm.scale, AXIS_SOURCE_FINGER)
            }
            last = c.position
        }
    }
}

/**
 * Runs one claimed Tern touch gesture from its press [event]. Every touch change
 * in the claimed stream is consumed (lifts included; mouse and stylus are left
 * alone); [TernTouchGesture.frame] is fed pressed touch contacts, plus the original
 * contact's final release position when all fingers lift. The press event is
 * included so simultaneously arriving fingers are counted. The hold deadline is
 * absolute, so a burst of jitter never extends it. A mouse event interleaved
 * with the touch cancels the gesture and goes down the ordinary mouse path.
 *
 * A normal all-lift completes FIFO via [TernGestureSession.finish]; any other
 * exit (stolen input, mouse, coroutine cancellation) invalidates the session.
 */
private suspend fun AwaitPointerEventScope.handleTernTouch(
    wm: WindowManager,
    window: XdgToplevel,
    event: PointerEvent,
    density: Float,
    width: Float,
    touchSlop: Float,
    longPressMs: Long,
    holder: GestureHolder,
    mouseButtons: Int,
): Int {
    val down = event.changes.firstOrNull {
        it.type == PointerType.Touch && it.pressed && !it.previousPressed && !it.isConsumed
    } ?: return mouseButtons
    // A parent-owned or abandoned stream must fully lift before another gesture
    // can begin; a new finger must not revive an already-cancelled contact.
    if (event.changes.any { it.type == PointerType.Touch && (it.isConsumed || it.previousPressed) }) {
        return mouseButtons
    }
    val start = down.position
    val hit = window.hitTest(start.x, start.y)
    for (c in event.changes) if (c.type == PointerType.Touch) c.consume()
    if (!wm.onPress(window, hit) || hit == null) return mouseButtons

    val session = wm.ternGestureSession(window, hit, start.x, start.y)
    val gesture =
        TernTouchGesture(
            pointerId = down.id.value,
            startX = start.x,
            startY = start.y,
            startMillis = down.uptimeMillis,
            touchSlop = touchSlop,
            longPressMillis = longPressMs,
            density = density,
            width = width,
            sink = session,
        )
    holder.gesture = gesture
    holder.session = session
    var allUp = false
    var buttons = mouseButtons
    try {
        // Two fingers can already be down in the press event; feed it before awaiting more.
        var startCount = 0
        var id0 = -1L
        var x0 = 0f
        var y0 = 0f
        var id1 = -1L
        var x1 = 0f
        var y1 = 0f
        for (c in event.changes) {
            if (c.type != PointerType.Touch || !c.pressed) continue
            when (startCount) {
                0 -> {
                    id0 = c.id.value
                    x0 = c.position.x
                    y0 = c.position.y
                }
                1 -> {
                    id1 = c.id.value
                    x1 = c.position.x
                    y1 = c.position.y
                }
            }
            startCount++
        }
        gesture.frame(down.uptimeMillis, startCount, id0, x0, y0, id1, x1, y1)
        allUp = gesture.finished

        while (!allUp) {
            val remaining = gesture.timeoutMillis(SystemClock.uptimeMillis())
            val next =
                if (remaining != null) {
                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
            if (next == null) {
                gesture.tick(SystemClock.uptimeMillis())
                if (gesture.finished) {
                    allUp = true
                    break
                }
                continue
            }

            var count = 0
            var firstId = -1L
            var firstX = 0f
            var firstY = 0f
            var secondId = -1L
            var secondX = 0f
            var secondY = 0f
            var releasedId = -1L
            var releasedX = 0f
            var releasedY = 0f
            var touchFrame = false
            var mouse = false
            var stolen = false
            for (c in next.changes) {
                if (c.type == PointerType.Mouse) {
                    if (!c.isConsumed) mouse = true
                    continue
                }
                if (c.type != PointerType.Touch) continue
                touchFrame = true
                if (c.isConsumed) {
                    stolen = true
                    if (c.pressed) count++
                    continue
                }
                c.consume()
                if (!c.pressed) {
                    if (c.id == down.id) {
                        releasedId = c.id.value
                        releasedX = c.position.x
                        releasedY = c.position.y
                    }
                    continue
                }
                when (count) {
                    0 -> {
                        firstId = c.id.value
                        firstX = c.position.x
                        firstY = c.position.y
                    }
                    1 -> {
                        secondId = c.id.value
                        secondX = c.position.x
                        secondY = c.position.y
                    }
                }
                count++
            }
            if (mouse) {
                gesture.cancel()
                session.invalidate()
                buttons = handleMouse(wm, window, next, buttons)
            }
            if (stolen) {
                gesture.cancel()
                session.invalidate()
            }
            // Mouse-only events do not imply that the held fingers have lifted.
            if (!touchFrame) continue
            if (count == 0) {
                firstId = releasedId
                firstX = releasedX
                firstY = releasedY
            }
            val stamp = next.changes.firstOrNull()?.uptimeMillis ?: SystemClock.uptimeMillis()
            gesture.frame(stamp, count, firstId, firstX, firstY, secondId, secondX, secondY)
            if (gesture.finished) {
                allUp = true
                break
            }
        }
    } finally {
        gesture.cancel()
        if (allUp) session.finish() else session.invalidate()
        holder.gesture = null
        holder.session = null
    }
    return buttons
}

private fun handleMouse(
    wm: WindowManager,
    window: XdgToplevel,
    event: PointerEvent,
    previousButtons: Int,
): Int {
    // Pick the actual unconsumed mouse contact, never the first (possibly touch) contact;
    // an already-consumed mouse change must not dispatch.
    val change = event.changes.firstOrNull { it.type == PointerType.Mouse && !it.isConsumed } ?: return previousButtons
    wm.cancelTernGestures()
    change.consume()
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
