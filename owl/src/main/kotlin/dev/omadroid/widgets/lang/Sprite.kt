package dev.omadroid.widgets.lang

/**
 * Pixel art for `sprite(rows, x, y, pixel)`: rows of letters, one art pixel
 * each, where `.` and spaces are empty. Kept as runs of the same letter along
 * a row, so a row costs a few rectangles rather than one per pixel.
 */
class Sprite private constructor(
    /** Per run: column, row and length, flattened. */
    val runs: IntArray,
    /** Per run: its letter. */
    val letters: CharArray,
) {
    val size get() = letters.size

    companion object {
        /** [rows] is a list of strings, or one string of lines (leading and trailing spaces on each line are ignored). */
        fun parse(rows: Value): Sprite {
            val lines =
                when (rows) {
                    is Lst -> rows.items.map { it.text() }
                    is Str -> rows.v.lines().map { it.trim() }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
                    else -> emptyList()
                }
            val runs = ArrayList<Int>()
            val letters = StringBuilder()
            lines.forEachIndexed { j, line ->
                var i = 0
                while (i < line.length) {
                    val c = line[i]
                    var k = i + 1
                    while (k < line.length && line[k] == c) k++
                    if (c != '.' && c != ' ') {
                        runs += i
                        runs += j
                        runs += k - i
                        letters.append(c)
                    }
                    i = k
                }
            }
            return Sprite(runs.toIntArray(), letters.toString().toCharArray())
        }
    }
}
