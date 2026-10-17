package dev.omalauncher.taskbar

import dev.omalauncher.ui.splitdrag.ZoneBox
import kotlin.math.abs

/** An app's window on screen, as the accessibility service sees it. */
data class AppWindow(
    val packageName: String,
    val bounds: ZoneBox,
)

/** Whether this launcher's home screen is started, so the taskbar knows when our own package is home. */
object HomeVisibility {
    @Volatile var shown = false
}

/** What's on screen, for the taskbar to know what a dragged app can go next to or replace. */
sealed interface ScreenState {
    /** The launcher (or another home app) is in front: the taskbar hides, home has its own strip. */
    data object Home : ScreenState

    /** Nothing the taskbar can read for sure: only full screen and pop-up are offered. */
    data object Unknown : ScreenState

    /** One app fills the screen (a pop-up may float over it). */
    data class Single(
        val packageName: String,
    ) : ScreenState

    /** Split screen: [first] is the top or left half, [second] the bottom or right one. */
    data class Split(
        val first: String,
        val second: String,
        val sideBySide: Boolean,
    ) : ScreenState
}

/**
 * What's on a [width]×[height] screen, from its app [windows], topmost first. Windows under 5% of the screen are
 * ignored. Two windows that don't overlap, each 20% or more and together 85% or more, are split screen, whatever floats
 * over them (pop-ups, dialogs): a pop-up opened before the split stays over it on One UI. Otherwise the largest window,
 * if it covers half the screen or more, is the app in front (home or single), again whatever floats over it. Anything
 * else is [ScreenState.Unknown]. Apps in [opaque] are on screen but can't be a split partner (other launchers, this
 * launcher's own windows other than home): one in front, or in either half of a split, is Unknown too.
 */
fun screenState(
    windows: List<AppWindow>,
    width: Float,
    height: Float,
    home: Set<String>,
    opaque: Set<String> = emptySet(),
): ScreenState {
    val screen = width * height
    if (screen <= 0f) return ScreenState.Unknown
    fun share(w: AppWindow) = w.bounds.area() / screen
    val seen = windows.filter { share(it) >= 0.05f }
    val big = seen.filter { share(it) >= 0.2f }
    val pair =
        big.indices.asSequence()
            .flatMap { i -> (i + 1 until big.size).asSequence().map { j -> big[i] to big[j] } }
            .firstOrNull { (a, b) -> !a.bounds.overlaps(b.bounds) && share(a) + share(b) >= 0.85f }
    if (pair != null) {
        val (a, b) = pair
        if (a.packageName in home || b.packageName in home) return ScreenState.Unknown
        if (a.packageName in opaque || b.packageName in opaque) return ScreenState.Unknown
        val sideBySide = abs(a.bounds.left - b.bounds.left) > abs(a.bounds.top - b.bounds.top)
        val aFirst = if (sideBySide) a.bounds.left <= b.bounds.left else a.bounds.top <= b.bounds.top
        val (first, second) = if (aFirst) a to b else b to a
        return ScreenState.Split(first.packageName, second.packageName, sideBySide)
    }
    val largest = seen.maxByOrNull { share(it) } ?: return ScreenState.Unknown
    if (share(largest) < 0.5f) return ScreenState.Unknown
    return when (largest.packageName) {
        in home -> ScreenState.Home
        in opaque -> ScreenState.Unknown
        else -> ScreenState.Single(largest.packageName)
    }
}

private fun ZoneBox.area() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)

private fun ZoneBox.overlaps(o: ZoneBox) = left < o.right && o.left < right && top < o.bottom && o.top < bottom

