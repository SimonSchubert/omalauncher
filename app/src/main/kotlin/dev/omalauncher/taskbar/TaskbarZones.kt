package dev.omalauncher.taskbar

import dev.omalauncher.ui.splitdrag.ADJACENT_DELAY_MS
import dev.omalauncher.ui.splitdrag.DropZone
import dev.omalauncher.ui.splitdrag.OpenPlan
import dev.omalauncher.ui.splitdrag.ZoneBox
import dev.omalauncher.ui.splitdrag.ZoneKind
import dev.omalauncher.ui.splitdrag.dropZones
import dev.omalauncher.ui.splitdrag.planOpen
import dev.omalauncher.ui.splitdrag.splitsSideBySide

/**
 * Where an app dragged off the taskbar can go on a [width]×[height] screen showing [state]. Over one app: home's zones,
 * splitting with that app (not with itself). Over a split: each half, replacing its app; nothing else, as One UI keeps a
 * split and puts any app opened during it into the half under it (no full screen, no pop-up). Nothing at all when the
 * [dragged] app is already in the split: it can't be in both halves. Otherwise full screen and pop-up only. [labelOf]
 * names an app by package (or a Linux window by its id); [canPartner] says whether an app can be relaunched or brought
 * back as a split partner (else no split over it).
 */
fun taskbarZones(
    state: ScreenState,
    dragged: String,
    width: Float,
    height: Float,
    canPopUp: Boolean,
    oneUi: Boolean,
    labelOf: (String) -> String,
    canPartner: (String) -> Boolean = { true },
): List<DropZone> =
    when (state) {
        is ScreenState.Single -> dropZones(width, height, canSplit = state.packageName != dragged && canPartner(state.packageName), canPopUp, splitsSideBySide(width, height, oneUi))
        is ScreenState.Split ->
            if (dragged == state.first || dragged == state.second) emptyList() else replaceZones(state, width, height, labelOf)
        ScreenState.Home, ScreenState.Unknown -> dropZones(width, height, canSplit = false, canPopUp, sideBySide = true)
    }

private fun replaceZones(
    state: ScreenState.Split,
    width: Float,
    height: Float,
    labelOf: (String) -> String,
): List<DropZone> {
    val halves =
        if (state.sideBySide) {
            listOf(
                Triple(ZoneKind.SplitLeft, state.first, ZoneBox(0f, 0f, width / 2, height)),
                Triple(ZoneKind.SplitRight, state.second, ZoneBox(width / 2, 0f, width, height)),
            )
        } else {
            listOf(
                Triple(ZoneKind.SplitTop, state.first, ZoneBox(0f, 0f, width, height / 2)),
                Triple(ZoneKind.SplitBottom, state.second, ZoneBox(0f, height / 2, width, height)),
            )
        }
    return halves.map { (kind, app, half) -> DropZone(kind, "Replace ${labelOf(app)}", half, half) }
}

/**
 * What dropping [dragged] in [zone] opens over [state]: full screen, a pop-up, or over one app a split with it, as home
 * does with that app as the partner. [appOf] finds the app for a package; null when it can't, and then nothing opens. Over
 * a split nothing is planned: a half is replaced by the trampoline (see [TaskbarTrampoline.replace]). Nor next to a Linux
 * window, which isn't an app to relaunch: see [splitHalves].
 */
fun <A> planTaskbarOpen(
    zone: DropZone,
    state: ScreenState,
    dragged: A,
    appOf: (String) -> A?,
): OpenPlan<A>? =
    when (zone.kind) {
        ZoneKind.FullScreen -> OpenPlan.AsTapped(dragged)
        ZoneKind.PopUp -> OpenPlan.Windowed(dragged, zone.preview)
        else -> (state as? ScreenState.Single)?.let { s -> appOf(s.packageName)?.let { planOpen(zone, dragged, it) } }
    }

/** What one half of a split the trampoline puts together holds. [A] is an app and [I] an intent, as in [TrampolineStep]. */
sealed interface SplitHalf<out A, out I> {
    /** An Android app, opened (or brought back) by its launcher entry. */
    data class App<A>(
        val app: A,
    ) : SplitHalf<A, Nothing>

    /** A Linux window's activity not open yet, started by [intent]: a window that just showed up. */
    data class Fresh<I>(
        val intent: I,
    ) : SplitHalf<Nothing, I>

    /**
     * A Linux window already in this launcher's task [taskId]. First in the split, the task comes to the front; second,
     * the window gets a new activity by [reopen] (its intent, in a new task) and the old task goes once that one is in.
     */
    data class Task<I>(
        val taskId: Int,
        val reopen: I,
    ) : SplitHalf<Nothing, I>
}

/**
 * The trampoline's steps that put [dragged], dropped in a split zone of [kind], next to [partner]: the dragged one first
 * for the top or left half, after the partner for the bottom or right one, as [planOpen] does. Null for any other zone.
 */
fun <A, I> splitHalves(
    kind: ZoneKind,
    dragged: SplitHalf<A, I>,
    partner: SplitHalf<A, I>,
): List<TrampolineStep<A, I>>? =
    when (kind) {
        ZoneKind.SplitTop, ZoneKind.SplitLeft -> halfSteps(dragged, partner)
        ZoneKind.SplitBottom, ZoneKind.SplitRight -> halfSteps(partner, dragged)
        ZoneKind.FullScreen, ZoneKind.PopUp -> null
    }

/** [first] in front, then [adjacent] next to it; see [SplitHalf]. */
private fun <A, I> halfSteps(
    first: SplitHalf<A, I>,
    adjacent: SplitHalf<A, I>,
): List<TrampolineStep<A, I>> {
    val front =
        when (first) {
            is SplitHalf.App -> TrampolineStep.Open(first.app, adjacent = false, delayMs = 0)
            is SplitHalf.Fresh -> TrampolineStep.Start(first.intent, adjacent = false, delayMs = 0)
            is SplitHalf.Task -> TrampolineStep.Front(first.taskId, delayMs = 0)
        }
    val next =
        when (adjacent) {
            is SplitHalf.App -> listOf(TrampolineStep.Open(adjacent.app, adjacent = true, delayMs = ADJACENT_DELAY_MS))
            is SplitHalf.Fresh -> listOf(TrampolineStep.Start(adjacent.intent, adjacent = true, delayMs = ADJACENT_DELAY_MS))
            is SplitHalf.Task ->
                listOf(
                    TrampolineStep.Start(adjacent.reopen, adjacent = true, delayMs = ADJACENT_DELAY_MS),
                    TrampolineStep.Remove(adjacent.taskId, delayMs = HANDOVER_DELAY_MS),
                )
        }
    return listOf(front) + next
}

/** How long a Linux window's old task stays after its new activity starts, so that one is in its half first. */
const val HANDOVER_DELAY_MS = 600L
