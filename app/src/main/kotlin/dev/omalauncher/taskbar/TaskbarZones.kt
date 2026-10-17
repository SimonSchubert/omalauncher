package dev.omalauncher.taskbar

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
 * names an app by package.
 */
fun taskbarZones(
    state: ScreenState,
    dragged: String,
    width: Float,
    height: Float,
    canPopUp: Boolean,
    oneUi: Boolean,
    labelOf: (String) -> String,
): List<DropZone> =
    when (state) {
        is ScreenState.Single -> dropZones(width, height, canSplit = state.packageName != dragged, canPopUp, splitsSideBySide(width, height, oneUi))
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
 * What dropping [dragged] in [zone] opens over [state]. Over one app, as home does with that app as the partner. Over a
 * split, the half's app is replaced: the other half's app is brought forward first, so the dragged one opens next to
 * it, in the half dropped on. [appOf] finds the app for a package; null when it can't, and then nothing opens.
 */
fun <A> planTaskbarOpen(
    zone: DropZone,
    state: ScreenState,
    dragged: A,
    draggedPackage: String,
    appOf: (String) -> A?,
): OpenPlan<A>? =
    when (zone.kind) {
        ZoneKind.FullScreen -> OpenPlan.AsTapped(dragged)
        ZoneKind.PopUp -> OpenPlan.Windowed(dragged, zone.preview)
        else ->
            when (state) {
                is ScreenState.Single -> appOf(state.packageName)?.let { planOpen(zone, dragged, it) }
                is ScreenState.Split -> {
                    val keep = if (zone.kind == ZoneKind.SplitTop || zone.kind == ZoneKind.SplitLeft) state.second else state.first
                    if (keep == draggedPackage) null else appOf(keep)?.let { OpenPlan.Split(it, dragged) }
                }
                ScreenState.Home, ScreenState.Unknown -> null
            }
    }
