package dev.omalauncher.ui

import kotlin.math.abs
import kotlin.math.sqrt

private const val PINCH_RATIO = 1.12
private const val EDGE_MIN_DP = 24f
private const val EDGE_MAX_DP = 56f
private const val TAB_THRESHOLD_DP = 60f

/**
 * Recognizes the phone-style touch gestures Tern needs (tap, drag-scroll,
 * hold-to-select, two-finger pinch and edge tab swipes) from the raw pressed
 * contacts the adapter passes each frame. All state is primitive; no per-frame
 * collections or objects are allocated. Coordinates are physical view pixels.
 */
internal class TernTouchGesture(
    pointerId: Long,
    private val startX: Float,
    private val startY: Float,
    startMillis: Long,
    private val touchSlop: Float,
    longPressMillis: Long,
    private val density: Float,
    private val width: Float,
    private val sink: Sink,
) {
    interface Sink {
        fun click(x: Float, y: Float)
        fun selectStart(x: Float, y: Float)
        fun selectMove(x: Float, y: Float)
        fun selectEnd()
        fun scroll(dx: Float, dy: Float)
        fun scrollStop()
        fun zoom(steps: Int)
        fun tab(direction: Int)
    }

    private enum class State {
        Pending,
        Scroll,
        Selection,
        EdgeCandidate,
        TabCommitted,
        Pinch,
        Blocked,
        Done,
    }

    private val activeId = pointerId
    private val deadline = startMillis + longPressMillis

    private var state =
        if (startX.isFinite() && startY.isFinite()) State.Pending else State.Blocked
    private var lastX = startX
    private var lastY = startY
    private var pressedCount = 1
    private var direction = 0
    private var pinchA = 0L
    private var pinchB = 0L
    private var baseline = 0.0
    private var isFinished = false

    val finished: Boolean get() = isFinished

    fun frame(
        nowMillis: Long,
        count: Int,
        firstId: Long,
        firstX: Float,
        firstY: Float,
        secondId: Long = -1L,
        secondX: Float = 0f,
        secondY: Float = 0f,
    ) {
        pressedCount = count
        if (isFinished) return
        if (count <= 0) {
            finishZero(nowMillis, firstId, firstX, firstY)
            return
        }
        if (state == State.Blocked) return
        if (!firstX.isFinite() || !firstY.isFinite()) {
            enterBlocked()
            return
        }
        if (count == 1) {
            if (firstId != activeId) {
                enterBlocked()
                return
            }
            if (state == State.Pending && nowMillis >= deadline) activateSelection()
            handleSingle(firstId, firstX, firstY)
            return
        }
        if (!secondX.isFinite() || !secondY.isFinite()) {
            enterBlocked()
            return
        }
        if (count == 2) {
            handlePair(firstId, firstX, firstY, secondId, secondX, secondY)
            return
        }
        enterBlocked()
    }

    fun tick(nowMillis: Long) {
        if (isFinished || state != State.Pending) return
        if (nowMillis >= deadline) activateSelection()
    }

    fun timeoutMillis(nowMillis: Long): Long? {
        if (isFinished || state != State.Pending) return null
        return (deadline - nowMillis).coerceAtLeast(0L)
    }

    fun cancel() {
        if (isFinished || state == State.Blocked) return
        endActive()
        if (pressedCount <= 0) {
            state = State.Done
            isFinished = true
        } else {
            state = State.Blocked
        }
    }

    private fun handleSingle(id: Long, x: Float, y: Float) {
        if (state == State.Blocked || state == State.Done) return
        if (id != activeId) {
            enterBlocked()
            return
        }
        if (state == State.TabCommitted) {
            lastX = x
            lastY = y
            return
        }
        if (state == State.Pinch) {
            enterBlocked()
            return
        }
        when (state) {
            State.Pending -> {
                val dx = x - startX
                val dy = y - startY
                if (sqrt(dx * dx + dy * dy) > touchSlop) beginMovement(x, y)
            }
            State.Scroll -> emitScrollTo(x, y)
            State.Selection -> emitSelectMove(x, y)
            State.EdgeCandidate -> updateCandidate(x, y)
            else -> Unit
        }
    }

    private fun beginMovement(x: Float, y: Float) {
        val dx = x - startX
        val dy = y - startY
        if (inEdgeStrip(startX) && abs(dx) > abs(dy)) {
            state = State.EdgeCandidate
            lastX = x
            lastY = y
            updateCandidate(x, y)
        } else {
            state = State.Scroll
            emitScroll(startX - x, startY - y)
            lastX = x
            lastY = y
        }
    }

    private fun emitScrollTo(x: Float, y: Float) {
        emitScroll(lastX - x, lastY - y)
        lastX = x
        lastY = y
    }

    private fun emitScroll(dx: Float, dy: Float) {
        if (dx != 0f || dy != 0f) sink.scroll(dx, dy)
    }

    private fun emitSelectMove(x: Float, y: Float) {
        if (x != lastX || y != lastY) {
            sink.selectMove(x, y)
            lastX = x
            lastY = y
        }
    }

    private fun updateCandidate(x: Float, y: Float) {
        val dx = x - startX
        val dy = y - startY
        val absDx = abs(dx)
        val absDy = abs(dy)
        if (absDx >= tabThreshold() && absDx > absDy) {
            direction = if (dx < 0f) 1 else -1
            state = State.TabCommitted
            lastX = x
            lastY = y
            return
        }
        if (absDy >= absDx) {
            state = State.Scroll
            emitScroll(startX - x, startY - y)
            lastX = x
            lastY = y
            return
        }
        lastX = x
        lastY = y
    }

    private fun handlePair(aId: Long, ax: Float, ay: Float, bId: Long, bx: Float, by: Float) {
        if (state == State.Pinch) {
            updatePinch(aId, ax, ay, bId, bx, by)
            return
        }
        enterPinch(aId, ax, ay, bId, bx, by)
    }

    private fun enterPinch(aId: Long, ax: Float, ay: Float, bId: Long, bx: Float, by: Float) {
        if (aId == bId || (aId != activeId && bId != activeId)) {
            enterBlocked()
            return
        }
        val distance = pairDistance(ax, ay, bx, by)
        if (!distance.isFinite() || distance <= 0.0) {
            enterBlocked()
            return
        }
        endActive()
        pinchA = aId
        pinchB = bId
        baseline = distance
        state = State.Pinch
    }

    private fun updatePinch(aId: Long, ax: Float, ay: Float, bId: Long, bx: Float, by: Float) {
        val samePair =
            (aId == pinchA && bId == pinchB) || (aId == pinchB && bId == pinchA)
        if (!samePair) {
            enterBlocked()
            return
        }
        val distance = pairDistance(ax, ay, bx, by)
        if (!distance.isFinite() || distance <= 0.0) {
            enterBlocked()
            return
        }
        var steps = 0
        while (distance / baseline >= PINCH_RATIO) {
            baseline *= PINCH_RATIO
            steps++
        }
        while (distance / baseline <= 1.0 / PINCH_RATIO) {
            baseline /= PINCH_RATIO
            steps--
        }
        if (steps != 0) sink.zoom(steps)
    }

    private fun activateSelection() {
        state = State.Selection
        sink.selectStart(startX, startY)
        lastX = startX
        lastY = startY
    }

    private fun enterBlocked() {
        if (state == State.Blocked || state == State.Done) return
        endActive()
        state = State.Blocked
    }

    private fun endActive() {
        when (state) {
            State.Scroll -> sink.scrollStop()
            State.Selection -> sink.selectEnd()
            else -> Unit
        }
    }

    private fun finishZero(nowMillis: Long, finalId: Long, finalX: Float, finalY: Float) {
        if (finalId != -1L && finalId != activeId) {
            endActive()
            finishDone()
            return
        }
        if (finalId == activeId) {
            if (finalX.isFinite() && finalY.isFinite()) {
                processFinalCoordinate(nowMillis, finalX, finalY)
            } else {
                endActive()
                finishDone()
                return
            }
        }
        when (state) {
            State.Pending -> {
                if (nowMillis >= deadline) {
                    activateSelection()
                    sink.selectEnd()
                } else {
                    sink.click(startX, startY)
                }
            }
            State.Scroll -> sink.scrollStop()
            State.EdgeCandidate -> {
                emitScroll(startX - lastX, startY - lastY)
                sink.scrollStop()
            }
            State.Selection -> sink.selectEnd()
            State.TabCommitted -> emitTabIfValid()
            else -> Unit
        }
        finishDone()
    }

    private fun processFinalCoordinate(nowMillis: Long, x: Float, y: Float) {
        when (state) {
            State.Pending -> {
                if (nowMillis >= deadline) activateSelection()
                handleSingle(activeId, x, y)
            }
            State.Scroll, State.Selection, State.EdgeCandidate, State.TabCommitted ->
                handleSingle(activeId, x, y)
            else -> Unit
        }
    }

    private fun emitTabIfValid() {
        if (direction == 0) return
        val dx = lastX - startX
        val dy = lastY - startY
        val absDx = abs(dx)
        if (absDx < tabThreshold() || absDx <= abs(dy)) return
        val sign = if (dx < 0f) 1 else -1
        if (sign == direction) sink.tab(direction)
    }

    private fun finishDone() {
        state = State.Done
        isFinished = true
    }

    private fun pairDistance(ax: Float, ay: Float, bx: Float, by: Float): Double {
        val dx = ax.toDouble() - bx.toDouble()
        val dy = ay.toDouble() - by.toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    private fun tabThreshold(): Float = TAB_THRESHOLD_DP * density

    private fun inEdgeStrip(x: Float): Boolean {
        val min = EDGE_MIN_DP * density
        val max = EDGE_MAX_DP * density
        val rightMin = width - max
        val rightMax = width - min
        return (x >= min && x <= max) || (x >= rightMin && x <= rightMax)
    }
}
