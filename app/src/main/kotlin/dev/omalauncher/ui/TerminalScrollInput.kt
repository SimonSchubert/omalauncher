package dev.omalauncher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import dev.omalauncher.wayland.AXIS_SOURCE_FINGER
import dev.omalauncher.wayland.XdgToplevel
import kotlin.math.abs

internal enum class TerminalDrag { Waiting, Scroll, PassThrough }

/** Decide once, after slop, without stealing a sideways swipe or an arrangement long press. */
internal fun terminalDrag(
    dx: Float,
    dy: Float,
    slop: Float,
    heldMillis: Long,
    longPressMillis: Long,
): TerminalDrag = when {
    heldMillis >= longPressMillis -> TerminalDrag.PassThrough
    dx * dx + dy * dy <= slop * slop -> TerminalDrag.Waiting
    abs(dy) > abs(dx) -> TerminalDrag.Scroll
    else -> TerminalDrag.PassThrough
}

/**
 * On the terminal's container, not its overlay: the initial pass also sees presses over the
 * Android TextureView. Only an unfocused terminal uses this. Its keyboard focus never changes.
 */
internal fun Modifier.terminalScrollInput(
    wm: WindowManager,
    window: XdgToplevel,
) = pointerInput(wm, window) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.isConsumed || down.type != PointerType.Touch) return@awaitEachGesture
        val hit = window.renderer?.hitTest(down.position.x, down.position.y) ?: return@awaitEachGesture
        var scrolling = false
        var touch = false
        var last = down.position
        val id = down.id.value.toInt()
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                if (!change.pressed) {
                    if (scrolling) change.consume()
                    break
                }
                val delta = change.position - down.position
                if (!scrolling) {
                    val decision = terminalDrag(
                        dx = delta.x,
                        dy = delta.y,
                        slop = viewConfiguration.touchSlop,
                        heldMillis = change.uptimeMillis - down.uptimeMillis,
                        longPressMillis = viewConfiguration.longPressTimeoutMillis,
                    )
                    when (decision) {
                        TerminalDrag.Waiting -> continue
                        TerminalDrag.PassThrough -> break
                        TerminalDrag.Scroll -> {
                            touch = wm.wantsTouch(hit.surface)
                            if (touch) wm.touchDown(hit, id) else wm.pointerMove(hit)
                            scrolling = true
                        }
                    }
                }
                // Before the pager/drawer see a move, cancel their drag and the overlay's tap.
                change.consume()
                if (touch) {
                    val logical = delta / hit.scale
                    wm.touchMotion(id, hit.x + logical.x, hit.y + logical.y)
                } else {
                    wm.scroll(0f, -(change.position.y - last.y) / hit.scale, AXIS_SOURCE_FINGER)
                }
                last = change.position
            }
        } finally {
            // Also runs when the page, window or modifier disappears during the gesture.
            if (scrolling) {
                if (touch) wm.touchUp(id) else wm.scrollStop()
            }
        }
    }
}
