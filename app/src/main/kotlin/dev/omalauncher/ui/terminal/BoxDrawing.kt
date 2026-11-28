package dev.omalauncher.ui.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Box-drawing lines (U+2500–257F) and block elements (U+2580–259F) drawn as shapes filling their cell, as foot and
 * Ghostty draw them: a font's glyphs stop short of a cell taller than the font, and borders (Claude Code's boxes,
 * btop's panels) show gaps between rows.
 */
internal object BoxDrawing {
    fun handles(cp: Int) = cp in 0x256d..0x2570 || cp in 0x2580..0x259f || cp in 0x2500..0x257f && ARMS[cp - 0x2500] != null

    /**
     * Up, right, down and left arm of a line character: 0 none, 1 light, 2 heavy, 3 double; null for one drawn
     * otherwise (arcs, diagonals) or a block element.
     */
    private fun arms(cp: Int): IntArray? {
        val i = cp - 0x2500
        if (i in ARMS.indices) return ARMS[i]?.let { s -> IntArray(4) { s[it] - '0' } }
        return null
    }

    // 2500-257F; null where the character is not a plain line (arcs, diagonals). Up, right, down, left.
    private val ARMS: Array<String?> =
        arrayOf(
            // 2500
            "0101", "0202", "1010", "2020", "0101", "0202", "1010", "2020", "0101", "0202", "1010", "2020",
            "0110", "0210", "0120", "0220",
            // 2510
            "0011", "0012", "0021", "0022", "1100", "1200", "2100", "2200", "1001", "1002", "2001", "2002",
            "1110", "1210", "2110", "1120",
            // 2520
            "2120", "2210", "1220", "2220", "1011", "1012", "2011", "1021", "2021", "2012", "1022", "2022",
            "0111", "0112", "0211", "0212",
            // 2530
            "0121", "0122", "0221", "0222", "1101", "1102", "1201", "1202", "2101", "2102", "2201", "2202",
            "1111", "1112", "1211", "1212",
            // 2540
            "2111", "1121", "2121", "2112", "2211", "1122", "1221", "2212", "1222", "2122", "2221", "2222",
            "0101", "0202", "1010", "2020",
            // 2550
            "0303", "3030", "0310", "0130", "0330", "0013", "0031", "0033", "1300", "3100", "3300", "1003",
            "3001", "3003", "1310", "3130",
            // 2560
            "3330", "1013", "3031", "3033", "0313", "0131", "0333", "1303", "3101", "3303", "1313", "3131",
            "3333", null, null, null,
            // 2570
            null, null, null, null, "0001", "1000", "0100", "0010", "0002", "2000", "0200", "0020",
            "0201", "1020", "0102", "2010",
        )

    /** Draws [cp], one that [handles] takes, in the cell at [x], [y] of [w] x [h] with [paint]'s colour. */
    fun draw(
        canvas: Canvas,
        cp: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        paint: Paint,
    ) {
        val light = max(1f, (w / 8f).roundToInt().toFloat())
        val heavy = light * 2
        val style = paint.style
        val alpha = paint.alpha
        paint.style = Paint.Style.FILL
        try {
            val arms = arms(cp)
            when {
                arms != null -> lines(canvas, arms, x, y, w, h, light, heavy, paint)
                cp in 0x256d..0x2570 -> arc(canvas, cp, x, y, w, h, light, paint)
                cp in 0x2580..0x259f -> block(canvas, cp, x, y, w, h, paint, alpha)
            }
        } finally {
            paint.style = style
            paint.alpha = alpha
        }
    }

    private fun lines(
        c: Canvas,
        arms: IntArray,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        light: Float,
        heavy: Float,
        p: Paint,
    ) {
        val cx = x + (w / 2).roundToInt()
        val cy = y + (h / 2).roundToInt()
        val (up, right, down, left) = arms
        fun thick(kind: Int) = if (kind == 2) heavy else light
        // How far an arm reaches past the centre, so lines of different weights meet without notches.
        val reachX = maxOf(thick(up), thick(down)) / 2 * (if (up != 0 || down != 0) 1 else 0)
        val reachY = maxOf(thick(left), thick(right)) / 2 * (if (left != 0 || right != 0) 1 else 0)
        val gap = light

        fun vertical(
            kind: Int,
            top: Float,
            bottom: Float,
        ) {
            if (kind == 3) {
                c.drawRect(cx - gap - light / 2, top, cx - gap + light / 2, bottom, p)
                c.drawRect(cx + gap - light / 2, top, cx + gap + light / 2, bottom, p)
            } else {
                val t = thick(kind)
                c.drawRect(cx - t / 2, top, cx + t / 2, bottom, p)
            }
        }

        fun horizontal(
            kind: Int,
            from: Float,
            to: Float,
        ) {
            if (kind == 3) {
                c.drawRect(from, cy - gap - light / 2, to, cy - gap + light / 2, p)
                c.drawRect(from, cy + gap - light / 2, to, cy + gap + light / 2, p)
            } else {
                val t = thick(kind)
                c.drawRect(from, cy - t / 2, to, cy + t / 2, p)
            }
        }
        val doubleReach = if (arms.any { it == 3 }) gap + light / 2 else 0f
        if (up != 0) vertical(up, y, cy + maxOf(reachY, doubleReach))
        if (down != 0) vertical(down, cy - maxOf(reachY, doubleReach), y + h)
        if (left != 0) horizontal(left, x, cx + maxOf(reachX, doubleReach))
        if (right != 0) horizontal(right, cx - maxOf(reachX, doubleReach), x + w)
    }

    private val oval = RectF()

    /** ╭ ╮ ╯ ╰: a quarter circle joining the two arms, as Claude Code's boxes have. */
    private fun arc(
        c: Canvas,
        cp: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        light: Float,
        p: Paint,
    ) {
        val cx = x + (w / 2).roundToInt()
        val cy = y + (h / 2).roundToInt()
        val r = minOf(w, h) / 2
        p.style = Paint.Style.STROKE
        p.strokeWidth = light
        val path = android.graphics.Path()
        when (cp) {
            // ╭ down and right
            0x256d -> {
                path.moveTo(cx, y + h)
                path.lineTo(cx, cy + r)
                oval.set(cx, cy, cx + 2 * r, cy + 2 * r)
                path.arcTo(oval, 180f, 90f)
                path.lineTo(x + w, cy)
            }
            // ╮ down and left
            0x256e -> {
                path.moveTo(cx, y + h)
                path.lineTo(cx, cy + r)
                oval.set(cx - 2 * r, cy, cx, cy + 2 * r)
                path.arcTo(oval, 0f, -90f)
                path.lineTo(x, cy)
            }
            // ╯ up and left
            0x256f -> {
                path.moveTo(cx, y)
                path.lineTo(cx, cy - r)
                oval.set(cx - 2 * r, cy - 2 * r, cx, cy)
                path.arcTo(oval, 0f, 90f)
                path.lineTo(x, cy)
            }
            // ╰ up and right
            else -> {
                path.moveTo(cx, y)
                path.lineTo(cx, cy - r)
                oval.set(cx, cy - 2 * r, cx + 2 * r, cy)
                path.arcTo(oval, 180f, -90f)
                path.lineTo(x + w, cy)
            }
        }
        c.drawPath(path, p)
    }

    private fun block(
        c: Canvas,
        cp: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        p: Paint,
        alpha: Int,
    ) {
        fun rect(
            l: Float,
            t: Float,
            r: Float,
            b: Float,
        ) = c.drawRect(x + l * w, y + t * h, x + r * w, y + b * h, p)
        when (cp) {
            0x2580 -> rect(0f, 0f, 1f, .5f)
            in 0x2581..0x2588 -> rect(0f, 1f - (cp - 0x2580) / 8f, 1f, 1f)
            in 0x2589..0x258f -> rect(0f, 0f, (0x2590 - cp) / 8f, 1f)
            0x2590 -> rect(.5f, 0f, 1f, 1f)
            in 0x2591..0x2593 -> {
                p.alpha = alpha * (cp - 0x2590) / 4
                rect(0f, 0f, 1f, 1f)
            }
            0x2594 -> rect(0f, 0f, 1f, 1 / 8f)
            0x2595 -> rect(7 / 8f, 0f, 1f, 1f)
            else -> {
                // Quadrants: upper left, upper right, lower left, lower right.
                val q =
                    when (cp) {
                        0x2596 -> 0b0010
                        0x2597 -> 0b0001
                        0x2598 -> 0b1000
                        0x2599 -> 0b1011
                        0x259a -> 0b1001
                        0x259b -> 0b1110
                        0x259c -> 0b1101
                        0x259d -> 0b0100
                        0x259e -> 0b0110
                        else -> 0b0111
                    }
                if (q and 0b1000 != 0) rect(0f, 0f, .5f, .5f)
                if (q and 0b0100 != 0) rect(.5f, 0f, 1f, .5f)
                if (q and 0b0010 != 0) rect(0f, .5f, .5f, 1f)
                if (q and 0b0001 != 0) rect(.5f, .5f, 1f, 1f)
            }
        }
    }
}
