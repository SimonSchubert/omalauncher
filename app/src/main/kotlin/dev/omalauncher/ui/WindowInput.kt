package dev.omalauncher.ui

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import dev.omalauncher.wayland.AXIS_SOURCE_FINGER
import dev.omalauncher.wayland.AXIS_SOURCE_WHEEL
import dev.omalauncher.wayland.BTN_LEFT
import dev.omalauncher.wayland.BTN_MIDDLE
import dev.omalauncher.wayland.BTN_RIGHT
import dev.omalauncher.wayland.Client
import dev.omalauncher.wayland.SurfaceHit
import dev.omalauncher.wayland.TouchInputEvent
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
    var fallback: FallbackTouch? = null
    val touchStream = Any()
    val touchIds = wm.touchPointerIds
    val touches = LinkedHashMap<PointerId, ActiveTouch>()

    fun cancelClientTouches(
        client: Client,
        updates: MutableList<TouchInputEvent>,
    ) {
        val ids = ArrayList<Int>()
        val iterator = touches.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.hit.surface.client === client) {
                ids += entry.value.id
                touchIds.release(touchStream, entry.key.value)
                iterator.remove()
            }
        }
        if (ids.isNotEmpty()) updates += TouchInputEvent.Cancel(ids)
    }

    fun cancelAllTouches(updates: MutableList<TouchInputEvent>) {
        if (touches.isEmpty()) return
        val ids = ArrayList<Int>(touches.size)
        for ((pointer, touch) in touches) {
            ids += touch.id
            touchIds.release(touchStream, pointer.value)
        }
        touches.clear()
        updates += TouchInputEvent.Cancel(ids)
    }

    try {
        while (true) {
            val waiting = fallback
            val event =
                if (waiting != null && !waiting.scrolling && !waiting.longPressed) {
                    val remaining = (waiting.longPressAt - SystemClock.uptimeMillis()).coerceAtLeast(0)
                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
            if (event == null) {
                val pending = fallback
                if (pending != null && !pending.scrolling && !pending.longPressed) {
                    pending.longPressed = true
                    wm.click(pending.hit, BTN_RIGHT)
                }
                continue
            }

            val first = event.changes.firstOrNull()
            if (first?.type == PointerType.Mouse) {
                mouseButtons = handleMouse(wm, window, event, mouseButtons)
                first.consume()
                continue
            }

            var updates: ArrayList<TouchInputEvent>? = null
            fun touchUpdates(): ArrayList<TouchInputEvent> =
                updates ?: ArrayList<TouchInputEvent>().also { updates = it }

            if (event.isAndroidCancel()) {
                if (touches.isNotEmpty()) {
                    val cancelled = ArrayList<TouchInputEvent>()
                    cancelAllTouches(cancelled)
                    wm.touchInput(cancelled)
                }
                fallback?.let { if (it.scrolling) wm.scrollStop() }
                fallback = null
                event.changes.forEach { it.consume() }
                continue
            }

            for (change in event.changes) {
                val touch = touches[change.id]
                if (touch != null) {
                    if (change.isConsumed) {
                        cancelClientTouches(touch.hit.surface.client, touchUpdates())
                        continue
                    }
                    change.consume()
                    if (change.pressed) {
                        if (change.position != change.previousPosition) {
                            val d = (change.position - touch.start) / wm.scale.toFloat()
                            touchUpdates() += TouchInputEvent.Motion(touch.id, touch.hit.x + d.x, touch.hit.y + d.y)
                        }
                    } else {
                        touchUpdates() += TouchInputEvent.Up(touch.id)
                        touches.remove(change.id)
                        touchIds.release(touchStream, change.id.value)
                    }
                    continue
                }

                val gesture = fallback
                if (gesture?.pointer == change.id) {
                    if (change.isConsumed) {
                        if (gesture.scrolling) wm.scrollStop()
                        fallback = null
                        continue
                    }
                    change.consume()
                    if (!change.pressed) {
                        if (gesture.scrolling) {
                            wm.scrollStop()
                        } else if (!gesture.longPressed) {
                            wm.click(gesture.hit, BTN_LEFT)
                        }
                        fallback = null
                        continue
                    }
                    if (!gesture.scrolling && !gesture.longPressed &&
                        (change.position - gesture.start).getDistance() > touchSlop
                    ) {
                        gesture.scrolling = true
                        wm.pointerMove(gesture.hit)
                    }
                    if (gesture.scrolling) {
                        val d = change.position - gesture.last
                        wm.scroll(-d.x / wm.scale, -d.y / wm.scale, AXIS_SOURCE_FINGER)
                    }
                    gesture.last = change.position
                    continue
                }

                if (!change.changedToDown() || change.isConsumed) continue
                val start = change.position
                val hit = window.hitTest(start.x, start.y)
                change.consume()
                if (!wm.onPress(window, hit) || hit == null) continue
                if (wm.wantsTouch(hit.surface)) {
                    val id = touchIds.idFor(touchStream, change.id.value)
                    touches[change.id] = ActiveTouch(id, hit, start)
                    touchUpdates() += TouchInputEvent.Down(hit.surface, id, hit.x, hit.y)
                } else if (fallback == null) {
                    fallback =
                        FallbackTouch(
                            pointer = change.id,
                            hit = hit,
                            start = start,
                            last = start,
                            longPressAt = SystemClock.uptimeMillis() + longPressMs,
                        )
                }
            }
            updates?.let(wm::touchInput)
        }
    } finally {
        if (touches.isNotEmpty()) {
            val updates = ArrayList<TouchInputEvent>()
            cancelAllTouches(updates)
            wm.touchInput(updates)
        }
        fallback?.let { if (it.scrolling) wm.scrollStop() }
    }
}

private fun PointerEvent.isAndroidCancel() =
    (motionEvent as? MotionEvent)?.actionMasked == MotionEvent.ACTION_CANCEL

private data class ActiveTouch(
    val id: Int,
    val hit: SurfaceHit,
    val start: Offset,
)

private data class FallbackTouch(
    val pointer: PointerId,
    val hit: SurfaceHit,
    val start: Offset,
    var last: Offset,
    val longPressAt: Long,
    var scrolling: Boolean = false,
    var longPressed: Boolean = false,
)

/** Keeps simultaneous pointer IDs distinct within and across input overlays. */
internal class TouchPointerIds {
    private data class PointerKey(val stream: Any, val pointerId: Long)

    private val byPointer = HashMap<PointerKey, Int>()
    private val inUse = HashSet<Int>()
    private var nextCandidate = 0

    fun idFor(
        stream: Any,
        pointerId: Long,
    ): Int {
        val key = PointerKey(stream, pointerId)
        byPointer[key]?.let { return it }
        val original = pointerId.toInt()
        val id =
            if (inUse.add(original)) {
                original
            } else {
                var candidate = nextCandidate
                while (!inUse.add(candidate)) candidate++
                nextCandidate = candidate + 1
                candidate
            }
        byPointer[key] = id
        return id
    }

    fun release(
        stream: Any,
        pointerId: Long,
    ) {
        byPointer.remove(PointerKey(stream, pointerId))?.let(inUse::remove)
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
