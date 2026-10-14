package dev.omalauncher.taskbar

import dev.omalauncher.ui.splitdrag.ZoneBox
import kotlin.math.abs

/** An app's window on screen, as the accessibility service sees it. */
data class AppWindow(
    val packageName: String,
    val bounds: ZoneBox,
)

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
 * What's on a [width]×[height] screen, from its app [windows], topmost first. A window over 85% of the screen is the
 * one app in front, whatever floats over it; two windows of 35–65% that don't overlap are split screen. A floating
 * window over a split makes it [ScreenState.Unknown]: which half is which isn't sure any more.
 */
fun screenState(
    windows: List<AppWindow>,
    width: Float,
    height: Float,
    home: Set<String>,
): ScreenState {
    val screen = width * height
    if (screen <= 0f) return ScreenState.Unknown
    fun share(w: AppWindow) = w.bounds.area() / screen
    val big = windows.filter { share(it) >= 0.3f }
    val floating = windows.size - big.size
    val top = big.firstOrNull() ?: return ScreenState.Unknown
    if (share(top) >= 0.85f) return if (top.packageName in home) ScreenState.Home else ScreenState.Single(top.packageName)
    if (big.size != 2 || floating > 0) return ScreenState.Unknown
    val (a, b) = big
    if (share(a) > 0.65f || share(b) > 0.65f || share(a) < 0.35f || share(b) < 0.35f || a.bounds.overlaps(b.bounds)) return ScreenState.Unknown
    if (a.packageName in home || b.packageName in home) return ScreenState.Unknown
    val sideBySide = abs(a.bounds.left - b.bounds.left) > abs(a.bounds.top - b.bounds.top)
    val aFirst = if (sideBySide) a.bounds.left <= b.bounds.left else a.bounds.top <= b.bounds.top
    val (first, second) = if (aFirst) a to b else b to a
    return ScreenState.Split(first.packageName, second.packageName, sideBySide)
}

private fun ZoneBox.area() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)

private fun ZoneBox.overlaps(o: ZoneBox) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
