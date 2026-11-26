package dev.omalauncher.ui.terminal

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip

/**
 * Every glyph a terminal shows, drawn once in white into a bitmap, so a row is one drawVertices call: a quad per
 * cell textured from here and tinted with its colour, however many colours the row has (btop's graphs, a colour
 * test). Slots are a cell wide or two (wide characters) and a cell tall, a pixel apart. A white block at the top
 * left textures backgrounds and lines. Colour emoji keep their own colours: [COLORED] marks them, to be tinted white.
 * Starts small and doubles when full, up to [MAX]; past that it starts over. Terminals with the same font and size
 * share one ([join]). Main thread.
 */
internal class GlyphAtlas private constructor(
    private val key: Any,
    private val cellW: Int,
    private val cellH: Int,
) {
    var bitmap: Bitmap = createBitmap(START, START)
        private set
    private var canvas = Canvas(bitmap)

    /** Draws with the atlas as its texture; colours multiply it. */
    val paint = Paint().apply { isFilterBitmap = false }

    /** Goes up whenever slots move or the bitmap is replaced: rows drawn before must be drawn again. */
    var generation = 0
        private set

    /** Each terminal drawing from here, as what to call when [generation] goes up. */
    private val users = ArrayList<() -> Unit>()

    private var x = 0
    private var y = 0

    /** Slots of one-code-point glyphs by [key], and of longer ones by their text, a map per face and width. */
    private val singles = LongIntMap()
    private val clusters = Array(8) { HashMap<String, Int>() }
    private var pixels = IntArray(0)

    init {
        start()
    }

    /** An empty [bitmap] but for the white block. */
    private fun start() {
        canvas.drawRect(0f, 0f, WHITE.toFloat(), WHITE.toFloat(), Paint().apply { color = Color.WHITE })
        x = WHITE + GAP
        y = 0
        paint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    /** No longer [moved]'s atlas; the last one to leave frees it. */
    fun leave(moved: () -> Unit) {
        users -= moved
        if (users.isEmpty() && shared[key] === this) shared.remove(key)
    }

    private fun moved() {
        generation++
        for (user in users) user()
    }

    /** Full at its largest: every slot is drawn again, into a bitmap of that size. */
    private fun startOver() {
        singles.clear()
        clusters.forEach { it.clear() }
        // A new one, not this one erased: rows other terminals drew show the old one until they're drawn again.
        bitmap = createBitmap(bitmap.width, bitmap.height)
        canvas = Canvas(bitmap)
        start()
        moved()
    }

    private fun bits(
        face: Int,
        span: Int,
    ) = face shl 1 or (span - 1)

    private fun key(
        cp: Int,
        face: Int,
        span: Int,
    ) = cp.toLong() shl 3 or bits(face, span).toLong()

    /**
     * The slot of code point [cp], or of [text] for a cell of several, in face [face] (0-3) [span] cells wide (1 or
     * 2): packed as [slotX], [slotY] and [COLORED], or -1 while it has none ([add]). Called for every cell drawn, so
     * it allocates nothing.
     */
    fun find(
        cp: Int,
        text: String?,
        face: Int,
        span: Int,
    ): Int = if (text == null) singles[key(cp, face, span)] else clusters[bits(face, span)][text] ?: -1

    /**
     * A slot for a glyph [find] didn't have, drawn by [draw] at its top left. -1 when it can't be had (a cell
     * bigger than the atlas).
     */
    fun add(
        cp: Int,
        text: String?,
        face: Int,
        span: Int,
        draw: (Canvas, Float, Float, Float) -> Unit,
    ): Int {
        val w = cellW * span
        if (w + GAP > MAX || cellH + GAP > MAX) return -1
        if (x + w > bitmap.width) {
            x = 0
            y += cellH + GAP
        }
        if (y + cellH > bitmap.height) {
            grow()
            return add(cp, text, face, span, draw)
        }
        // The white block sits left of the first shelf's first slot, never in one.
        val sx = x
        val sy = y
        canvas.withClip(sx, sy, sx + w, sy + cellH) { draw(this, sx.toFloat(), sy.toFloat(), w.toFloat()) }
        x = sx + w + GAP
        var packed = sx shl 14 or sy
        if (colored(sx, sy, w)) packed = packed or COLORED
        if (text == null) singles[key(cp, face, span)] = packed else clusters[bits(face, span)][text] = packed
        return packed
    }

    /** Whether the glyph in a slot has colours of its own: an emoji, not white with alpha. */
    private fun colored(
        sx: Int,
        sy: Int,
        w: Int,
    ): Boolean {
        val n = w * cellH
        if (pixels.size < n) pixels = IntArray(n)
        val px = pixels
        bitmap.getPixels(px, 0, w, sx, sy, w, cellH)
        for (i in 0 until n) {
            val c = px[i]
            val r = c shr 16 and 0xff
            val g = c shr 8 and 0xff
            val b = c and 0xff
            if (r != g || g != b) return true
        }
        return false
    }

    /** Twice the size with every slot where it was; past [MAX], empty again. */
    private fun grow() {
        val size = bitmap.width * 2
        if (size > MAX) {
            startOver()
            return
        }
        val bigger = createBitmap(size, size)
        val c = Canvas(bigger)
        c.drawBitmap(bitmap, 0f, 0f, null)
        // Not recycled: rows already drawn this frame still show the old one until they're drawn again.
        bitmap = bigger
        canvas = c
        // The free space is now to the right of the old square and below it: go on below it.
        x = 0
        y = size / 2
        paint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        moved()
    }

    companion object {
        private val shared = HashMap<Any, GlyphAtlas>()

        /**
         * The atlas of [key], which stands for how glyphs are drawn (the faces and their size), with cells of
         * [cellWidth] by [cellHeight]: one for all terminals of that key. [moved] is called when its [generation]
         * goes up, also while that terminal draws; [leave] with it when done.
         */
        fun join(
            key: Any,
            cellWidth: Int,
            cellHeight: Int,
            moved: () -> Unit,
        ): GlyphAtlas = shared.getOrPut(key) { GlyphAtlas(key, cellWidth, cellHeight) }.also { it.users += moved }

        const val COLORED = 1 shl 28
        private const val START = 512
        private const val MAX = 4096
        private const val GAP = 1

        /** The white block's size; [WHITE_TEX] samples its middle. */
        private const val WHITE = 4
        const val WHITE_TEX = 2f

        fun slotX(packed: Int) = packed shr 14 and 0x3fff

        fun slotY(packed: Int) = packed and 0x3fff
    }
}

/** A map of longs to ints without boxing, -1 for a missing key; for [GlyphAtlas]'s hot path. */
internal class LongIntMap {
    private var keys = LongArray(256)
    private var values = IntArray(256)
    private var used = BooleanArray(256)
    private var size = 0

    operator fun get(key: Long): Int {
        var i = index(key, keys.size)
        while (used[i]) {
            if (keys[i] == key) return values[i]
            i = (i + 1) and (keys.size - 1)
        }
        return -1
    }

    operator fun set(
        key: Long,
        value: Int,
    ) {
        if ((size + 1) * 2 > keys.size) rehash()
        var i = index(key, keys.size)
        while (used[i]) {
            if (keys[i] == key) {
                values[i] = value
                return
            }
            i = (i + 1) and (keys.size - 1)
        }
        used[i] = true
        keys[i] = key
        values[i] = value
        size++
    }

    fun clear() {
        used.fill(false)
        size = 0
    }

    private fun index(
        key: Long,
        n: Int,
    ): Int {
        val h = key * -0x61c8864680b583ebL
        return (h xor (h ushr 32)).toInt() and (n - 1)
    }

    private fun rehash() {
        val oldKeys = keys
        val oldValues = values
        val oldUsed = used
        keys = LongArray(oldKeys.size * 2)
        values = IntArray(oldKeys.size * 2)
        used = BooleanArray(oldKeys.size * 2)
        size = 0
        for (i in oldKeys.indices) if (oldUsed[i]) set(oldKeys[i], oldValues[i])
    }
}
