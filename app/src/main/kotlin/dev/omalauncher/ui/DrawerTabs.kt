package dev.omalauncher.ui

import kotlin.math.abs

/** The drawer's tabs, each listing one profile's apps while the bar is empty. */
enum class DrawerTab(val label: String) {
    Personal("personal"),
    Work("work"),
    Private("private"),
}

/** Private Space keeps its tab while locked or empty, unless hidden in launcher settings. */
internal fun drawerTabs(work: Boolean, privateSpace: Boolean = false, privateShown: Boolean = true) =
    listOfNotNull(DrawerTab.Personal, DrawerTab.Work.takeIf { work }, DrawerTab.Private.takeIf { privateSpace && privateShown })

internal fun swipedTab(tabs: List<DrawerTab>, current: DrawerTab, dx: Float, threshold: Float): DrawerTab {
    if (abs(dx) < threshold) return current
    val i = tabs.indexOf(current)
    return tabs.getOrNull(if (dx < 0) i + 1 else i - 1) ?: current
}
