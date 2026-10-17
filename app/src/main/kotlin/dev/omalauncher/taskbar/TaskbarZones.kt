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
 * window, which isn't an app to relaunch: see [linuxPartnerSteps].
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

/**
 * The trampoline's steps that put [dragged], dropped in a split zone of [kind], next to a Linux window in its task
 * [taskId]; null for any other zone. The bottom or right half: the window's task comes back to the front, then the app
 * opens next to it. The top or left half: the app opens full screen, then the window gets a new activity next to it, by
 * [reopen] (the window's own intent, in a new task), and its old task goes; the new activity takes the window over, so
 * that doesn't close it.
 */
fun <A, I> linuxPartnerSteps(
    kind: ZoneKind,
    dragged: A,
    taskId: Int,
    reopen: I,
): List<TrampolineStep<A, I>>? =
    when (kind) {
        ZoneKind.SplitBottom, ZoneKind.SplitRight ->
            listOf(TrampolineStep.Front(taskId, delayMs = 0), TrampolineStep.Open(dragged, adjacent = true, delayMs = ADJACENT_DELAY_MS))
        ZoneKind.SplitTop, ZoneKind.SplitLeft ->
            listOf(
                TrampolineStep.Open(dragged, adjacent = false, delayMs = 0),
                TrampolineStep.Start(reopen, adjacent = true, delayMs = ADJACENT_DELAY_MS),
                TrampolineStep.Remove(taskId, delayMs = HANDOVER_DELAY_MS),
            )
        ZoneKind.FullScreen, ZoneKind.PopUp -> null
    }

/** How long a Linux window's old task stays after its new activity starts, so that one is in its half first. */
const val HANDOVER_DELAY_MS = 600L
