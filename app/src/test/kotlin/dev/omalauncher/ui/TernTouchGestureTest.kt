package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TernTouchGestureTest {
    private class Recorder : TernTouchGesture.Sink {
        val clicks = ArrayList<Pair<Float, Float>>()
        val selectStarts = ArrayList<Pair<Float, Float>>()
        val selectMoves = ArrayList<Pair<Float, Float>>()
        var selectEnds = 0
        val scrolls = ArrayList<Pair<Float, Float>>()
        var scrollStops = 0
        val zooms = ArrayList<Int>()
        val tabs = ArrayList<Int>()
        val events = ArrayList<String>()

        override fun click(x: Float, y: Float) {
            clicks.add(x to y)
        }

        override fun selectStart(x: Float, y: Float) {
            selectStarts.add(x to y)
        }

        override fun selectMove(x: Float, y: Float) {
            events.add("selectMove")
            selectMoves.add(x to y)
        }

        override fun selectEnd() {
            events.add("selectEnd")
            selectEnds++
        }

        override fun scroll(dx: Float, dy: Float) {
            events.add("scroll")
            scrolls.add(dx to dy)
        }

        override fun scrollStop() {
            events.add("scrollStop")
            scrollStops++
        }

        override fun zoom(steps: Int) {
            zooms.add(steps)
        }

        override fun tab(direction: Int) {
            tabs.add(direction)
        }
    }

    private fun newGesture(
        sink: Recorder,
        density: Float = 1f,
        width: Float = 1000f,
        touchSlop: Float = 10f,
        longPressMillis: Long = 500L,
        startMillis: Long = 0L,
        startX: Float = 500f,
        startY: Float = 400f,
        pointerId: Long = 1L,
    ) = TernTouchGesture(
        pointerId,
        startX,
        startY,
        startMillis,
        touchSlop,
        longPressMillis,
        density,
        width,
        sink,
    )

    private fun assertScroll(sink: Recorder, index: Int, dx: Float, dy: Float) {
        assertEquals(dx, sink.scrolls[index].first, 0.001f)
        assertEquals(dy, sink.scrolls[index].second, 0.001f)
    }

    @Test fun stationaryTapClicksOnceOnRelease() {
        val sink = Recorder()
        val g = newGesture(sink)
        g.frame(10L, 1, 1L, 500f, 400f)
        assertFalse(g.finished)
        assertTrue(sink.clicks.isEmpty())

        g.frame(20L, 0, -1L, 0f, 0f)
        g.frame(30L, 0, -1L, 0f, 0f)

        assertEquals(1, sink.clicks.size)
        assertEquals(500f, sink.clicks[0].first, 0.001f)
        assertEquals(400f, sink.clicks[0].second, 0.001f)
        assertTrue(g.finished)
    }

    @Test fun movementScrollsBothAxesIncludingOriginToFirstMoved() {
        val sink = Recorder()
        val g = newGesture(sink)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.frame(5L, 1, 1L, 520f, 390f)

        assertEquals(1, sink.scrolls.size)
        assertScroll(sink, 0, -20f, 10f)

        g.frame(6L, 1, 1L, 540f, 380f)
        assertEquals(2, sink.scrolls.size)
        assertScroll(sink, 1, -20f, 10f)

        g.frame(7L, 0, -1L, 0f, 0f)
        assertEquals(1, sink.scrollStops)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun scrollStopIsExactlyOnceOnCancel() {
        val sink = Recorder()
        val g = newGesture(sink)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.frame(5L, 1, 1L, 560f, 400f)
        assertEquals(0, sink.scrollStops)

        g.cancel()
        assertEquals(1, sink.scrollStops)
        assertFalse(g.finished)

        g.frame(10L, 0, -1L, 0f, 0f)
        assertEquals(1, sink.scrollStops)
        assertTrue(g.finished)
    }

    @Test fun secondFingerStopsScrollThenPinches() {
        val sink = Recorder()
        val g = newGesture(sink)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.frame(5L, 1, 1L, 560f, 400f)
        assertEquals(0, sink.scrollStops)

        g.frame(10L, 2, 1L, 560f, 400f, 2L, 760f, 400f)
        assertEquals(1, sink.scrollStops)
        assertTrue(sink.zooms.isEmpty())

        g.frame(15L, 0, -1L, 0f, 0f)
        assertEquals(1, sink.scrollStops)
        assertTrue(g.finished)
    }

    @Test fun holdDeadlineIsAbsoluteAndJitterDoesNotExtend() {
        val sink = Recorder()
        val g = newGesture(sink, startMillis = 0L, longPressMillis = 500L)
        g.frame(0L, 1, 1L, 500f, 400f)

        assertEquals(500L, g.timeoutMillis(0L))
        assertEquals(300L, g.timeoutMillis(200L))

        g.frame(200L, 1, 1L, 501f, 401f)
        assertEquals(300L, g.timeoutMillis(200L))
        assertEquals(500L, g.timeoutMillis(0L))

        g.tick(499L)
        assertTrue(sink.selectStarts.isEmpty())
        g.tick(500L)
        assertEquals(1, sink.selectStarts.size)
        assertEquals(500f, sink.selectStarts[0].first, 0.001f)

        g.frame(501L, 0, -1L, 0f, 0f)
        assertEquals(1, sink.selectEnds)
        assertTrue(g.finished)
    }

    @Test fun holdSuppressesPendingDeadline() {
        val sink = Recorder()
        val g = newGesture(sink)
        g.frame(0L, 1, 1L, 500f, 400f)
        assertEquals(500L, g.timeoutMillis(0L))
        g.tick(500L)
        assertNull(g.timeoutMillis(500L))
        assertNull(g.timeoutMillis(600L))
        g.tick(600L)
        assertEquals(1, sink.selectStarts.size)
    }

    @Test fun selectionEmitsMoveAndSingleRelease() {
        val sink = Recorder()
        val g = newGesture(sink)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.tick(500L)
        g.frame(510L, 1, 1L, 520f, 410f)

        assertEquals(1, sink.selectStarts.size)
        assertEquals(1, sink.selectMoves.size)
        assertEquals(520f, sink.selectMoves[0].first, 0.001f)
        assertEquals(410f, sink.selectMoves[0].second, 0.001f)

        g.frame(520L, 0, -1L, 0f, 0f)
        assertEquals(1, sink.selectEnds)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun selectionReleaseIsExactlyOnceOnCancel() {
        val sink = Recorder()
        val g = newGesture(sink)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.tick(500L)
        g.cancel()
        g.cancel()
        assertEquals(1, sink.selectEnds)

        g.frame(600L, 0, -1L, 0f, 0f)
        assertEquals(1, sink.selectEnds)
        assertTrue(g.finished)
    }

    @Test fun selectionTransitionsToPinchReleasingOnce() {
        val sink = Recorder()
        val g = newGesture(sink)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.tick(500L)
        assertEquals(1, sink.selectStarts.size)

        g.frame(510L, 2, 1L, 500f, 400f, 2L, 700f, 400f)
        assertEquals(1, sink.selectEnds)
        assertTrue(sink.zooms.isEmpty())

        g.frame(520L, 0, -1L, 0f, 0f)
        assertEquals(1, sink.selectEnds)
        assertTrue(sink.clicks.isEmpty())
    }

    @Test fun pinchZoomsMultipleStepsAndRetainsBaseline() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 400f, startY = 400f)
        g.frame(0L, 1, 1L, 400f, 400f)
        g.frame(5L, 2, 1L, 400f, 400f, 2L, 600f, 400f)
        assertTrue(sink.zooms.isEmpty())

        g.frame(10L, 2, 1L, 400f, 400f, 2L, 700f, 400f)
        assertEquals(listOf(3), sink.zooms)

        g.frame(15L, 2, 1L, 400f, 400f, 2L, 610f, 400f)
        assertEquals(listOf(3, -2), sink.zooms)

        g.frame(20L, 0, -1L, 0f, 0f)
        assertTrue(g.finished)
        assertTrue(sink.clicks.isEmpty())
    }

    @Test fun pinchAcceptsReversedPairOrder() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 400f, startY = 400f)
        g.frame(0L, 1, 1L, 400f, 400f)
        g.frame(5L, 2, 1L, 400f, 400f, 2L, 600f, 400f)

        g.frame(10L, 2, 2L, 600f, 400f, 1L, 400f, 400f)
        assertTrue(sink.zooms.isEmpty())

        g.frame(15L, 2, 2L, 700f, 400f, 1L, 400f, 400f)
        assertEquals(listOf(3), sink.zooms)
    }

    @Test fun pinchLiftQuarantinesResidualFinger() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 400f, startY = 400f)
        g.frame(0L, 1, 1L, 400f, 400f)
        g.frame(5L, 2, 1L, 400f, 400f, 2L, 600f, 400f)
        g.frame(10L, 2, 1L, 400f, 400f, 2L, 700f, 400f)
        assertEquals(listOf(3), sink.zooms)

        g.frame(15L, 1, 1L, 400f, 400f)
        g.frame(20L, 1, 1L, 650f, 650f)
        assertTrue(sink.scrolls.isEmpty())
        assertTrue(sink.clicks.isEmpty())
        assertEquals(0, sink.scrollStops)

        g.frame(25L, 0, -1L, 0f, 0f)
        assertTrue(g.finished)
        assertTrue(sink.scrolls.isEmpty())
    }

    @Test fun contactReplacementQuarantines() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 400f, startY = 400f)
        g.frame(0L, 1, 1L, 400f, 400f)
        g.frame(5L, 2, 1L, 400f, 400f, 2L, 600f, 400f)
        g.frame(10L, 2, 1L, 400f, 400f, 3L, 600f, 400f)

        g.frame(15L, 2, 1L, 400f, 400f, 3L, 600f, 400f)
        g.frame(20L, 0, -1L, 0f, 0f)
        assertTrue(sink.zooms.isEmpty())
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun duplicateIdsQuarantine() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 400f, startY = 400f)
        g.frame(0L, 1, 1L, 400f, 400f)
        g.frame(5L, 2, 1L, 400f, 400f, 1L, 600f, 400f)
        g.frame(10L, 2, 1L, 400f, 400f, 1L, 800f, 400f)
        assertTrue(sink.zooms.isEmpty())
    }

    @Test fun thirdFingerQuarantines() {
        val sink = Recorder()
        val g = newGesture(sink)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.frame(5L, 3, 1L, 500f, 400f, 2L, 600f, 400f)
        g.frame(10L, 3, 1L, 500f, 400f, 2L, 700f, 400f)
        assertTrue(sink.zooms.isEmpty())

        g.frame(15L, 0, -1L, 0f, 0f)
        assertTrue(g.finished)
        assertTrue(sink.clicks.isEmpty())
    }

    @Test fun invalidCoordinatesQuarantineWithoutTap() {
        val sink = Recorder()
        val g = newGesture(sink)
        g.frame(0L, 1, 1L, Float.NaN, 400f)
        g.frame(10L, 1, 1L, 500f, 400f)
        g.frame(20L, 0, -1L, 0f, 0f)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun zeroPinchDistanceQuarantines() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 400f, startY = 400f)
        g.frame(0L, 1, 1L, 400f, 400f)
        g.frame(5L, 2, 1L, 400f, 400f, 2L, 400f, 400f)
        g.frame(10L, 2, 1L, 400f, 400f, 2L, 700f, 400f)
        assertTrue(sink.zooms.isEmpty())
    }

    @Test fun edgeSwipeLeftEmitsNextOnRelease() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 40f, startY = 400f)
        g.frame(0L, 1, 1L, 40f, 400f)
        g.frame(5L, 1, 1L, -30f, 400f)
        assertTrue(sink.tabs.isEmpty())

        g.frame(6L, 0, -1L, 0f, 0f)
        assertEquals(listOf(1), sink.tabs)
        assertTrue(sink.scrolls.isEmpty())
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun edgeSwipeRightEmitsPreviousOnRelease() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 960f, startY = 400f)
        g.frame(0L, 1, 1L, 960f, 400f)
        g.frame(5L, 1, 1L, 1030f, 400f)
        assertTrue(sink.tabs.isEmpty())

        g.frame(6L, 0, -1L, 0f, 0f)
        assertEquals(listOf(-1), sink.tabs)
        assertTrue(g.finished)
    }

    @Test fun verticalFirstStrokeInStripScrollsNeverTabs() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 40f, startY = 400f)
        g.frame(0L, 1, 1L, 40f, 400f)
        g.frame(5L, 1, 1L, 35f, 470f)

        assertEquals(1, sink.scrolls.size)
        assertScroll(sink, 0, 5f, -70f)

        g.frame(6L, 1, 1L, 200f, 470f)
        assertScroll(sink, 1, -165f, 0f)

        g.frame(7L, 0, -1L, 0f, 0f)
        assertEquals(1, sink.scrollStops)
        assertTrue(sink.tabs.isEmpty())
    }

    @Test fun subThresholdCandidateReleasesAsScrollNotTap() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 40f, startY = 400f)
        g.frame(0L, 1, 1L, 40f, 400f)
        g.frame(5L, 1, 1L, 80f, 400f)
        assertTrue(sink.scrolls.isEmpty())

        g.frame(6L, 0, -1L, 0f, 0f)
        assertEquals(1, sink.scrolls.size)
        assertScroll(sink, 0, -40f, 0f)
        assertEquals(1, sink.scrollStops)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(sink.tabs.isEmpty())
    }

    @Test fun candidateTurningVerticalScrollsFromOrigin() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 40f, startY = 400f)
        g.frame(0L, 1, 1L, 40f, 400f)
        g.frame(5L, 1, 1L, 60f, 405f)
        assertTrue(sink.scrolls.isEmpty())

        g.frame(6L, 1, 1L, 60f, 500f)
        assertEquals(1, sink.scrolls.size)
        assertScroll(sink, 0, -20f, -100f)

        g.frame(7L, 0, -1L, 0f, 0f)
        assertEquals(1, sink.scrollStops)
        assertTrue(sink.tabs.isEmpty())
    }

    @Test fun candidateCancelDiscardsWithoutCallbacks() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 40f, startY = 400f)
        g.frame(0L, 1, 1L, 40f, 400f)
        g.frame(5L, 1, 1L, 60f, 405f)
        g.cancel()

        assertTrue(sink.scrolls.isEmpty())
        assertEquals(0, sink.scrollStops)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(sink.tabs.isEmpty())

        g.frame(10L, 0, -1L, 0f, 0f)
        assertTrue(g.finished)
        assertTrue(sink.tabs.isEmpty())
    }

    @Test fun committedTabCancelNeverEmits() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 40f, startY = 400f)
        g.frame(0L, 1, 1L, 40f, 400f)
        g.frame(5L, 1, 1L, -30f, 400f)
        g.cancel()

        g.frame(10L, 0, -1L, 0f, 0f)
        assertTrue(sink.tabs.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun outsideStripHorizontalMovementScrolls() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 500f, startY = 400f)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.frame(5L, 1, 1L, 560f, 400f)

        assertEquals(1, sink.scrolls.size)
        assertScroll(sink, 0, -60f, 0f)
        assertTrue(sink.tabs.isEmpty())
    }

    @Test fun edgeReservedStripRespectsDensityAndWidth() {
        val outsideLow = Recorder()
        val gLow = newGesture(outsideLow, density = 2f, width = 800f, startX = 47f, startY = 400f)
        gLow.frame(0L, 1, 1L, 47f, 400f)
        gLow.frame(5L, 1, 1L, 97f, 400f)
        assertEquals(1, outsideLow.scrolls.size)

        val insideLow = Recorder()
        val gInside = newGesture(insideLow, density = 2f, width = 800f, startX = 48f, startY = 400f)
        gInside.frame(0L, 1, 1L, 48f, 400f)
        gInside.frame(5L, 1, 1L, 98f, 400f)
        assertTrue(insideLow.scrolls.isEmpty())

        val insideLowEdge = Recorder()
        val gInsideEdge = newGesture(insideLowEdge, density = 2f, width = 800f, startX = 112f, startY = 400f)
        gInsideEdge.frame(0L, 1, 1L, 112f, 400f)
        gInsideEdge.frame(5L, 1, 1L, 162f, 400f)
        assertTrue(insideLowEdge.scrolls.isEmpty())

        val outsideHigh = Recorder()
        val gHigh = newGesture(outsideHigh, density = 2f, width = 800f, startX = 113f, startY = 400f)
        gHigh.frame(0L, 1, 1L, 113f, 400f)
        gHigh.frame(5L, 1, 1L, 163f, 400f)
        assertEquals(1, outsideHigh.scrolls.size)
    }

    @Test fun edgeCommitRespectsDensityScaledThreshold() {
        val sink = Recorder()
        val g = newGesture(sink, density = 2f, width = 800f, startX = 48f, startY = 400f)
        g.frame(0L, 1, 1L, 48f, 400f)
        g.frame(5L, 1, 1L, 168f, 400f)
        assertTrue(sink.tabs.isEmpty())

        g.frame(6L, 0, -1L, 0f, 0f)
        assertEquals(listOf(-1), sink.tabs)
    }

    @Test fun changingSingleContactIdQuarantines() {
        val sink = Recorder()
        val g = newGesture(sink)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.frame(5L, 1, 2L, 500f, 400f)
        g.frame(10L, 0, 2L, 0f, 0f)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun zeroCountAfterPendingPastDeadlineBecomesSelection() {
        val sink = Recorder()
        val g = newGesture(sink, startMillis = 0L, longPressMillis = 500L)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.frame(600L, 0, -1L, 0f, 0f)
        assertEquals(1, sink.selectStarts.size)
        assertEquals(1, sink.selectEnds)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun replacementPairFromPendingQuarantinesWithoutCallbacks() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 400f, startY = 400f)
        g.frame(0L, 1, 1L, 400f, 400f)
        g.frame(5L, 2, 5L, 400f, 400f, 6L, 600f, 400f)
        assertTrue(sink.zooms.isEmpty())
        assertEquals(0, sink.scrollStops)
        assertEquals(0, sink.selectEnds)

        g.frame(10L, 1, 5L, 400f, 400f)
        g.frame(15L, 0, 5L, 0f, 0f)
        assertTrue(sink.zooms.isEmpty())
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun replacementPairDuringSelectionReleasesSelectionOnce() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 400f, startY = 400f)
        g.frame(0L, 1, 1L, 400f, 400f)
        g.tick(500L)
        assertEquals(1, sink.selectStarts.size)

        g.frame(510L, 2, 5L, 400f, 400f, 6L, 600f, 400f)
        assertEquals(1, sink.selectEnds)
        assertTrue(sink.zooms.isEmpty())

        g.frame(520L, 1, 5L, 600f, 400f)
        g.frame(530L, 0, 5L, 0f, 0f)
        assertEquals(1, sink.selectEnds)
        assertTrue(g.finished)
    }

    @Test fun replacementSingleAfterTabCommitDiscardsTab() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 40f, startY = 400f)
        g.frame(0L, 1, 1L, 40f, 400f)
        g.frame(5L, 1, 1L, -30f, 400f)

        g.frame(10L, 1, 2L, 100f, 400f)
        g.frame(15L, 1, 2L, 150f, 400f)
        g.frame(20L, 0, 2L, 0f, 0f)
        assertTrue(sink.tabs.isEmpty())
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun replacementSingleAtHoldDeadlineHasNoPhantomSelection() {
        val sink = Recorder()
        val g = newGesture(sink, startMillis = 0L, longPressMillis = 500L)
        g.frame(0L, 1, 1L, 500f, 400f)

        g.frame(600L, 1, 2L, 500f, 400f)
        assertTrue(sink.selectStarts.isEmpty())
        assertEquals(0, sink.selectEnds)

        g.frame(610L, 0, 2L, 0f, 0f)
        assertTrue(sink.selectStarts.isEmpty())
        assertEquals(0, sink.selectEnds)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun validLateStationaryFrameStartsAndBalancesSelection() {
        val sink = Recorder()
        val g = newGesture(sink, startMillis = 0L, longPressMillis = 500L)
        g.frame(0L, 1, 1L, 500f, 400f)

        g.frame(600L, 1, 1L, 500f, 400f)
        assertEquals(1, sink.selectStarts.size)
        assertTrue(sink.selectMoves.isEmpty())

        g.frame(610L, 0, -1L, 0f, 0f)
        assertEquals(1, sink.selectEnds)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun invalidConstructorCoordinatesNeverEmit() {
        val sink = Recorder()
        val g = newGesture(sink, startX = Float.NaN, startY = 400f)
        assertFalse(g.finished)
        assertNull(g.timeoutMillis(0L))

        g.tick(600L)
        assertTrue(sink.selectStarts.isEmpty())

        g.frame(10L, 1, 1L, 500f, 400f)
        g.frame(20L, 0, -1L, 0f, 0f)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(sink.selectStarts.isEmpty())
        assertTrue(sink.scrolls.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun largePinchAggregatesStepsBeyondCap() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 1f, startY = 1f)
        g.frame(0L, 1, 1L, 1f, 1f)
        g.frame(5L, 2, 1L, 1f, 1f, 2L, 2f, 1f)
        assertTrue(sink.zooms.isEmpty())

        g.frame(10L, 2, 1L, 1f, 1f, 2L, 2001f, 1f)
        assertEquals(1, sink.zooms.size)
        assertTrue(sink.zooms[0] > 64)
        assertEquals(67, sink.zooms[0])
    }

    @Test fun pinchReversalHysteresisRetainsBaseline() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 400f, startY = 400f)
        g.frame(0L, 1, 1L, 400f, 400f)
        g.frame(5L, 2, 1L, 400f, 400f, 2L, 500f, 400f)
        assertTrue(sink.zooms.isEmpty())

        g.frame(10L, 2, 1L, 400f, 400f, 2L, 550f, 400f)
        assertEquals(listOf(3), sink.zooms)

        g.frame(15L, 2, 1L, 400f, 400f, 2L, 545f, 400f)
        assertEquals(listOf(3), sink.zooms)

        g.frame(20L, 2, 1L, 400f, 400f, 2L, 520f, 400f)
        assertEquals(listOf(3, -1), sink.zooms)
    }

    @Test fun quickDownThenMovedUpScrollsWithoutClick() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 500f, startY = 400f)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.frame(50L, 0, 1L, 540f, 430f)

        assertEquals(1, sink.scrolls.size)
        assertScroll(sink, 0, -40f, -30f)
        assertEquals(1, sink.scrollStops)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun finalUpScrollDeltaIsIncremental() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 500f, startY = 400f)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.frame(5L, 1, 1L, 520f, 400f)
        assertEquals(1, sink.scrolls.size)

        g.frame(10L, 0, 1L, 540f, 420f)
        assertEquals(2, sink.scrolls.size)
        assertScroll(sink, 1, -20f, -20f)
        assertEquals(1, sink.scrollStops)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun quickInnerEdgeMovedUpTabsOnce() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 40f, startY = 400f)
        g.frame(0L, 1, 1L, 40f, 400f)
        g.frame(50L, 0, 1L, -30f, 400f)

        assertEquals(listOf(1), sink.tabs)
        assertTrue(sink.scrolls.isEmpty())
        assertEquals(0, sink.scrollStops)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun quickInnerEdgeMovedUpVerticalScrollsNotTabs() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 40f, startY = 400f)
        g.frame(0L, 1, 1L, 40f, 400f)
        g.frame(50L, 0, 1L, 40f, 480f)

        assertEquals(1, sink.scrolls.size)
        assertScroll(sink, 0, 0f, -80f)
        assertEquals(1, sink.scrollStops)
        assertTrue(sink.tabs.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun selectionFinalUpMovesBeforeSingleRelease() {
        val sink = Recorder()
        val g = newGesture(sink, startMillis = 0L, longPressMillis = 500L)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.tick(500L)
        assertEquals(1, sink.selectStarts.size)

        g.frame(510L, 0, 1L, 560f, 440f)
        assertEquals(listOf("selectMove", "selectEnd"), sink.events)
        assertEquals(560f, sink.selectMoves[0].first, 0.001f)
        assertEquals(440f, sink.selectMoves[0].second, 0.001f)
        assertEquals(1, sink.selectEnds)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun zeroCountSentinelIgnoresNaNFinalCoordinates() {
        val sink = Recorder()
        val g = newGesture(sink)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.frame(10L, 0, -1L, Float.NaN, Float.NaN)

        assertEquals(1, sink.clicks.size)
        assertEquals(500f, sink.clicks[0].first, 0.001f)
        assertEquals(400f, sink.clicks[0].second, 0.001f)
        assertTrue(g.finished)
    }

    @Test fun replacementFinalUpReleasesSelectionExactlyOnce() {
        val sink = Recorder()
        val g = newGesture(sink, startMillis = 0L, longPressMillis = 500L)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.tick(500L)
        assertEquals(1, sink.selectStarts.size)

        g.frame(510L, 0, 99L, 500f, 400f)
        assertEquals(1, sink.selectEnds)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(sink.tabs.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun invalidFinalUpCoordinatesTerminateWithoutClick() {
        val sink = Recorder()
        val g = newGesture(sink)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.frame(10L, 0, 1L, Float.NaN, 400f)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)

        g.frame(20L, 0, 1L, 500f, 400f)
        assertTrue(sink.clicks.isEmpty())
    }

    @Test fun invalidFinalUpDuringScrollStopsExactlyOnce() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 500f, startY = 400f)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.frame(5L, 1, 1L, 560f, 400f)
        g.frame(10L, 0, 1L, Float.NaN, Float.NaN)

        assertEquals(1, sink.scrollStops)
        assertTrue(g.finished)
    }

    @Test fun pinchFinalUpWithCoordinateNeverClicksOrScrolls() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 400f, startY = 400f)
        g.frame(0L, 1, 1L, 400f, 400f)
        g.frame(5L, 2, 1L, 400f, 400f, 2L, 600f, 400f)
        g.frame(10L, 2, 1L, 400f, 400f, 2L, 700f, 400f)
        assertTrue(sink.zooms.isNotEmpty())

        g.frame(15L, 0, 1L, 900f, 900f)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(sink.scrolls.isEmpty())
        assertEquals(0, sink.scrollStops)
        assertTrue(g.finished)
    }

    @Test fun committedTabReverseViaMoveCancelsTab() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 40f, startY = 400f)
        g.frame(0L, 1, 1L, 40f, 400f)
        g.frame(5L, 1, 1L, -30f, 400f)
        assertTrue(sink.tabs.isEmpty())

        g.frame(10L, 1, 1L, 40f, 400f)
        g.frame(15L, 0, -1L, 0f, 0f)
        assertTrue(sink.tabs.isEmpty())
        assertTrue(sink.clicks.isEmpty())
        assertTrue(sink.scrolls.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun committedTabReverseViaFinalUpCancelsTab() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 40f, startY = 400f)
        g.frame(0L, 1, 1L, 40f, 400f)
        g.frame(5L, 1, 1L, -30f, 400f)
        g.frame(10L, 0, 1L, 40f, 400f)

        assertTrue(sink.tabs.isEmpty())
        assertTrue(sink.clicks.isEmpty())
        assertTrue(sink.scrolls.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun committedTabVerticalFinalRejected() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 40f, startY = 400f)
        g.frame(0L, 1, 1L, 40f, 400f)
        g.frame(5L, 1, 1L, -30f, 400f)
        g.frame(10L, 0, 1L, -30f, 600f)

        assertTrue(sink.tabs.isEmpty())
        assertTrue(sink.clicks.isEmpty())
        assertTrue(sink.scrolls.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun committedTabOppositeFinalCancels() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 40f, startY = 400f)
        g.frame(0L, 1, 1L, 40f, 400f)
        g.frame(5L, 1, 1L, -30f, 400f)
        g.frame(10L, 0, 1L, 140f, 400f)

        assertTrue(sink.tabs.isEmpty())
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun committedTabValidFinalUpEmitsOnce() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 40f, startY = 400f)
        g.frame(0L, 1, 1L, 40f, 400f)
        g.frame(5L, 1, 1L, -30f, 400f)
        g.frame(10L, 0, 1L, -60f, 400f)

        assertEquals(listOf(1), sink.tabs)
        assertTrue(sink.clicks.isEmpty())
        assertTrue(g.finished)
    }

    @Test fun zeroDeltaFinalUpDoesNotEmitScrollButStopsOnce() {
        val sink = Recorder()
        val g = newGesture(sink, startX = 500f, startY = 400f)
        g.frame(0L, 1, 1L, 500f, 400f)
        g.frame(5L, 1, 1L, 560f, 400f)
        assertEquals(1, sink.scrolls.size)

        g.frame(10L, 0, 1L, 560f, 400f)
        assertEquals(1, sink.scrolls.size)
        assertEquals(1, sink.scrollStops)
        assertTrue(g.finished)
    }
}
