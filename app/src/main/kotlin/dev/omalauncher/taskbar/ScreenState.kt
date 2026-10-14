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
 * What's on a [width]×[height] screen, from its app [windows], topmost first. Windows under 5% of the screen are
 * ignored. A window lying inside one below it is floating (a pop-up). The rest are the base: one base window covering
 * half the screen or more is the app in front (home or single), whatever floats over it; two that don't overlap, each
 * 20% or more and together 85% or more, are split screen. Floating over a split, or anything else, is
 * [ScreenState.Unknown]: which half is which isn't sure any more.
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
    val seen = windows.filter { share(it) >= 0.05f }
    val floating = seen.filterIndexed { i, w -> seen.drop(i + 1).any { w.bounds.isInside(it.bounds) } }
    val base = seen.filter { it !in floating }
    when (base.size) {
        1 -> {
            val only = base.first()
            if (share(only) < 0.5f) return ScreenState.Unknown
            return if (only.packageName in home) ScreenState.Home else ScreenState.Single(only.packageName)
        }
        2 -> {
            val (a, b) = base
            if (floating.isNotEmpty() || a.bounds.overlaps(b.bounds)) return ScreenState.Unknown
            if (share(a) < 0.2f || share(b) < 0.2f || share(a) + share(b) < 0.85f) return ScreenState.Unknown
            if (a.packageName in home || b.packageName in home) return ScreenState.Unknown
            val sideBySide = abs(a.bounds.left - b.bounds.left) > abs(a.bounds.top - b.bounds.top)
            val aFirst = if (sideBySide) a.bounds.left <= b.bounds.left else a.bounds.top <= b.bounds.top
            val (first, second) = if (aFirst) a to b else b to a
            return ScreenState.Split(first.packageName, second.packageName, sideBySide)
        }
        else -> return ScreenState.Unknown
    }
}

private fun ZoneBox.area() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)

private fun ZoneBox.overlaps(o: ZoneBox) = left < o.right && o.left < right && top < o.bottom && o.top < bottom

private fun ZoneBox.isInside(o: ZoneBox) = left >= o.left - 1f && top >= o.top - 1f && right <= o.right + 1f && bottom <= o.bottom + 1f
