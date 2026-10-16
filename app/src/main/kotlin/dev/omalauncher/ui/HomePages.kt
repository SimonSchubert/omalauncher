package dev.omalauncher.ui

/**
 * The page numbers the home pager shows. [pages] are the pages holding something, in order.
 * While [arranging] and the last page holds something, one empty page follows it to drop tiles on.
 * Unfolded, pages show in pairs ([per] = 2), so an odd count gets an empty partner.
 */
internal fun homePageNumbers(
    pages: List<Int>,
    lastHasItems: Boolean,
    arranging: Boolean,
    per: Int,
): List<Int> {
    val withDrop = if (arranging && lastHasItems) pages + (pages.last() + 1) else pages
    return if (withDrop.size % per == 0) withDrop else withDrop + (withDrop.last() + 1)
}
