package dev.omalauncher.widgets

import kotlin.math.abs

/** A tentative layout, never persisted until release. Null means no arrangement was found.
 * Moves colliding tiles and their neighbours, keeping every tile's size. The search is bounded
 * so a fragmented full page cannot stall touch input. [before] prefers earlier reading-order
 * cells when moving a tile forward; moving backward pushes neighbours toward later cells.
 */
internal fun reorderCells(
    cells: Map<String, Cell>,
    target: Cell,
    cols: Int,
    rows: Int,
    before: Boolean = false,
): Map<String, Cell>? {
    fun fits(c: Cell) = c.x >= 0 && c.y >= 0 && c.w > 0 && c.h > 0 && c.right <= cols && c.bottom <= rows
    if (!fits(target) || cells.values.any { !fits(it) }) return null
    if (cells.values.sumOf { it.w * it.h } + target.w * target.h > cols * rows) return null
    var attempts = 2000

    fun move(key: String, plan: Map<String, Cell>, locked: Set<String>): Map<String, Cell>? {
        if (--attempts < 0) return null
        val original = cells.getValue(key)
        val candidates = (0..rows - original.h).flatMap { y ->
            (0..cols - original.w).map { x -> original.copy(x = x, y = y) }
        }.sortedWith(compareBy<Cell>(
            { if ((it.y * cols + it.x < original.y * cols + original.x) == before) 0 else 1 },
            { abs(it.x - original.x) + abs(it.y - original.y) },
            { it.y * cols + it.x },
        ))
        for (candidate in candidates) {
            if (--attempts < 0) return null
            if (candidate.overlaps(target)) continue
            val collisions = plan.filter { (other, c) -> other != key && c.overlaps(candidate) }.keys
            if (collisions.any { it in locked }) continue
            var next: Map<String, Cell>? = plan + (key to candidate)
            for (other in collisions) {
                next = move(other, next ?: break, locked + key)
                if (next == null) break
            }
            if (next != null) return next
        }
        return null
    }

    var plan = cells
    for (key in cells.keys.sortedBy { cells.getValue(it).y * cols + cells.getValue(it).x }) {
        if (plan.getValue(key).overlaps(target)) plan = move(key, plan, emptySet()) ?: return null
    }
    // Older layouts can already overlap after fitting to a smaller display. Never commit that.
    if (plan.any { (key, cell) -> plan.any { (other, c) -> other != key && c.overlaps(cell) } }) return null
    return plan
}
