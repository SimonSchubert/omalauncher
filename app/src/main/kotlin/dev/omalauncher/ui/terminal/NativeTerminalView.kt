package dev.omalauncher.ui.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderNode
import android.os.SystemClock
import android.util.Log
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import dev.omalauncher.system.postLinuxNotification
import dev.omalauncher.terminal.Cells
import dev.omalauncher.terminal.TerminalKeyboard
import dev.omalauncher.terminal.TerminalSession
import dev.omalauncher.terminal.Vt
import dev.omalauncher.terminal.VtEvent
import dev.omalauncher.terminal.VtKey
import dev.omalauncher.ui.KeyTarget
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A built-in terminal: draws [session]'s screen and sends it keys, touches and pastes. Used by terminal windows and
 * tiles alike. Text is drawn in [faces] at [emPx] pixels to the em, as foot sizes it (TerminalFont), so a terminal
 * gets as many columns as foot would; colours are [colors], with the background left out when [transparent] (a tile's
 * glass). Keys come from the launcher's keyboard bridge as a [KeyTarget], set by whoever gives this view the keyboard.
 * Main thread.
 */
class NativeTerminalView(
    context: Context,
) : View(context),
    KeyTarget {
    /** The program shown; the view resizes it to fit. Setting another doesn't end the old one. */
    var session: TerminalSession? = null
        set(value) {
            if (value === field) return
            field?.let {
                it.onOutput = {}
                it.onEvent = {}
            }
            field = value
            keys.reset()
            if (value != null) {
                value.vt.colors(colors.foreground, colors.background, colors.cursor, colors.palette)
                value.onOutput = ::requestFrame
                value.onEvent = { e -> post { event(e) } }
                lastGrid = null
                fitGrid()
            }
            invalidateRows()
        }

    var faces: TerminalFaces = TerminalFaces.Fallback
        set(value) {
            if (value === field) return
            field = value
            measureCells()
        }

    /** The font size in pixels to the em, before pinch zoom: TerminalFont.emPx. */
    var emPx = 24f
        set(value) {
            if (value == field) return
            field = value
            measureCells()
        }

    /** Space around the cells on each side, as foot.ini's pad. */
    var padPx = 16
        set(value) {
            if (value == field) return
            field = value
            fitGrid()
            invalidateRows()
        }

    var colors: TerminalColors = TerminalColors.from(emptyMap())
        set(value) {
            if (value == field) return
            field = value
            session?.vt?.colors(value.foreground, value.background, value.cursor, value.palette)
            invalidateRows()
        }

    /** Leaves out the background where the program sets none, for a tile's glass. */
    var transparent = false
        set(value) {
            if (value == field) return
            field = value
            invalidateRows()
        }

    /** Whether this terminal has the keyboard: its cursor is solid, and a program that asked hears of focus changes. */
    var keyboardFocus = false
        set(value) {
            if (value == field) return
            field = value
            if (!value) {
                keys.reset()
                composingText = ""
            }
            session?.focus(value)
            invalidate()
        }

    /** The grid changed: columns, rows and the cell's pixels; a host starts its session at the first one. */
    var onGrid: (cols: Int, rows: Int, cellWidth: Int, cellHeight: Int) -> Unit = { _, _, _, _ -> }

    /** A tap that wasn't a link, a mouse click for the program or the end of a selection: the keyboard, say. */
    var onTap: () -> Unit = {}

    /** The program set its title (OSC 0/2). */
    var onTitle: (String) -> Unit = {}

    /** Cell size in pixels, from [faces] and [emPx]. */
    var cellWidth = 1
        private set
    var cellHeight = 1
        private set
    private var baseline = 0f

    /** Pinch zoom over [emPx]. */
    private var zoom = 1f

    private val keys = TerminalKeyboard()
    private val cells = Cells()

    /** What each row's node was recorded from, to re-record only rows that changed. */
    private var drawn = Cells()
    private var drawnClusters = arrayOfNulls<String>(0)
    private var rowNodes = arrayOfNulls<RenderNode>(0)
    private var allDirty = true
    private var lastGrid: IntArray? = null

    /** The columns and rows that fit, as [onGrid] last had them; 0 before the first layout. */
    val columns get() = lastGrid?.get(0) ?: 0
    val rows get() = lastGrid?.get(1) ?: 0

    private val framePending = AtomicBoolean(false)

    /** When the program started holding its screen (synchronized output), to give up waiting after a while. */
    private var heldSince = 0L

    private var composingText = ""

    private val paints = Array(4) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG) }
    private val fill = Paint()
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // --- Layout ---

    private fun measureCells() {
        val size = (emPx * zoom).coerceIn(MIN_EM_PX, MAX_EM_PX)
        for ((i, p) in paints.withIndex()) {
            p.typeface = faces.of(bold = i and 1 != 0, italic = i and 2 != 0)
            p.textSize = size
            p.textScaleX = 1f
        }
        val p = paints[0]
        val advance = p.measureText("M").coerceAtLeast(1f)
        cellWidth = advance.roundToInt().coerceAtLeast(1)
        val fm = p.fontMetrics
        cellHeight = ceil(fm.descent - fm.ascent).toInt().coerceAtLeast(1)
        baseline = (-fm.ascent).roundToInt().toFloat()
        // Every glyph exactly a cell wide, so a run of them drawn at once stays on the grid.
        val scaleX = cellWidth / advance
        for (q in paints) q.textScaleX = scaleX
        for ((i, q) in whites.withIndex()) {
            q.typeface = paints[i].typeface
            q.textSize = size
            q.textScaleX = scaleX
        }
        atlas.reset(cellWidth, cellHeight)
        fitGrid()
        invalidateRows()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        fitGrid()
        invalidateRows()
    }

    /** The columns and rows that fit, to the session and [onGrid] when they change. */
    private fun fitGrid() {
        if (width == 0 || height == 0) return
        val cols = ((width - 2 * padPx) / cellWidth).coerceAtLeast(1)
        val rows = ((height - 2 * padPx) / cellHeight).coerceAtLeast(1)
        val grid = intArrayOf(cols, rows, cellWidth, cellHeight)
        if (grid.contentEquals(lastGrid)) return
        lastGrid = grid
        session?.resize(cols, rows, cellWidth, cellHeight)
        onGrid(cols, rows, cellWidth, cellHeight)
    }

    // --- Drawing ---

    /** From the session's reading thread: one frame for however many reads come before it. */
    private fun requestFrame() {
        if (framePending.compareAndSet(false, true)) postInvalidateOnAnimation()
    }

    private fun invalidateRows() {
        allDirty = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        framePending.set(false)
        val vt = session?.vt
        if (vt != null && !holding(vt)) vt.cells(cells)
        val bg = if (cells.cols > 0) cells.defaultBg else colors.background
        if (!transparent) canvas.drawColor(bg)
        if (cells.cols == 0) return
        val hardware = canvas.isHardwareAccelerated
        if (rowNodes.size != cells.rows || drawn.cols != cells.cols || drawn.rows != cells.rows) newGrid()
        // Glyphs moved in the atlas (or it was replaced): rows drawn from the old one are drawn again.
        if (atlas.generation != atlasGeneration) allDirty = true
        val generation = atlas.generation
        val rowWidth = cells.cols * cellWidth
        for (row in 0 until cells.rows) {
            val top = padPx + row * cellHeight
            if (!hardware) {
                canvas.withTranslation(padPx.toFloat(), top.toFloat()) { drawRow(this, row) }
                continue
            }
            var node = rowNodes[row]
            if (node == null || allDirty || rowChanged(row)) {
                if (node == null) node = RenderNode("terminal-row").also { rowNodes[row] = it }
                node.setPosition(padPx, top, padPx + rowWidth, top + cellHeight)
                val c = node.beginRecording(rowWidth, cellHeight)
                try {
                    drawRow(c, row)
                } finally {
                    node.endRecording()
                }
                keepRow(row)
            }
            canvas.drawRenderNode(node)
        }
        allDirty = false
        atlasGeneration = generation
        // The atlas grew or started over while rows were drawn: the rows before that are drawn again next frame.
        if (atlas.generation != generation) postInvalidateOnAnimation()
        drawCursor(canvas)
        drawComposing(canvas)
        drawSelectionHandles(canvas)
        drawScrollbar(canvas, vt)
    }

    /** The screen has another number of rows or columns: every row is drawn again into nodes of its own. */
    private fun newGrid() {
        rowNodes.forEach { it?.discardDisplayList() }
        rowNodes = arrayOfNulls(cells.rows)
        drawnClusters = arrayOfNulls(cells.rows)
        drawn = Cells().apply { ensure(cells.cols * cells.rows) }
        drawn.cols = cells.cols
        drawn.rows = cells.rows
        allDirty = true
    }

    /** Whether to keep showing the last frame: the program holds its screen, for a second at most, as Ghostty waits. */
    private fun holding(vt: Vt): Boolean {
        if (!vt.held) {
            heldSince = 0L
            return false
        }
        val now = SystemClock.uptimeMillis()
        if (heldSince == 0L) heldSince = now
        if (now - heldSince >= HOLD_MAX_MS) return false
        postInvalidateDelayed(HOLD_MAX_MS - (now - heldSince))
        return cells.cols > 0
    }

    private fun rowChanged(row: Int): Boolean {
        val from = row * cells.cols
        for (i in from until from + cells.cols) {
            if (cells.codepoints[i] != drawn.codepoints[i] || cells.fg[i] != drawn.fg[i] || cells.bg[i] != drawn.bg[i] ||
                cells.flags[i] != drawn.flags[i]
            ) {
                return true
            }
        }
        return clusters(row) != drawnClusters[row]
    }

    private fun keepRow(row: Int) {
        val from = row * cells.cols
        System.arraycopy(cells.codepoints, from, drawn.codepoints, from, cells.cols)
        System.arraycopy(cells.fg, from, drawn.fg, from, cells.cols)
        System.arraycopy(cells.bg, from, drawn.bg, from, cells.cols)
        System.arraycopy(cells.flags, from, drawn.flags, from, cells.cols)
        drawnClusters[row] = clusters(row)
    }

    /** The row's cells of more than one code point, which the arrays alone don't show; null when it has none. */
    private fun clusters(row: Int): String? {
        var out: StringBuilder? = null
        val from = row * cells.cols
        for (i in from until from + cells.cols) {
            if (cells.isCluster(i)) (out ?: StringBuilder().also { out = it }).append(i).append(cells.text(i))
        }
        return out?.toString()
    }

    private fun Int.has(flag: Int) = this and flag != 0

    /** Cell [i]'s background as drawn, or null where it shows what's behind ([transparent], no colour of its own). */
    private fun backgroundOf(i: Int): Int? {
        val f = cells.flags[i]
        return when {
            f.has(Cells.SELECTED) -> colors.selectionBackground
            f.has(Cells.INVERSE) -> cells.fg[i]
            f.has(Cells.HAS_BG) || !transparent -> cells.bg[i]
            else -> null
        }
    }

    private fun foregroundOf(i: Int): Int {
        val f = cells.flags[i]
        val c =
            when {
                f.has(Cells.SELECTED) -> colors.selectionForeground
                f.has(Cells.INVERSE) -> cells.bg[i]
                else -> cells.fg[i]
            }
        return if (f.has(Cells.FAINT)) (c and 0x00ffffff) or 0x80000000.toInt() else c
    }

    private val atlas = GlyphAtlas()
    private var atlasGeneration = -1

    /** A white paint per face, for glyphs drawn into [atlas]. */
    private val whites = Array(4) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { color = Color.WHITE } }

    /** The row being built: two triangles per quad, their atlas coordinates and colours. */
    private var verts = FloatArray(0)
    private var texs = FloatArray(0)
    private var vertColors = IntArray(0)
    private var quads = 0

    private fun quad(
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        tl: Float,
        tt: Float,
        tr: Float,
        tb: Float,
        color: Int,
    ) {
        val v = quads * 12
        val k = quads * 6
        // l,t  r,t  l,b  |  r,t  r,b  l,b
        verts[v] = l; verts[v + 1] = t; verts[v + 2] = r; verts[v + 3] = t; verts[v + 4] = l; verts[v + 5] = b
        verts[v + 6] = r; verts[v + 7] = t; verts[v + 8] = r; verts[v + 9] = b; verts[v + 10] = l; verts[v + 11] = b
        texs[v] = tl; texs[v + 1] = tt; texs[v + 2] = tr; texs[v + 3] = tt; texs[v + 4] = tl; texs[v + 5] = tb
        texs[v + 6] = tr; texs[v + 7] = tt; texs[v + 8] = tr; texs[v + 9] = tb; texs[v + 10] = tl; texs[v + 11] = tb
        for (j in k until k + 6) vertColors[j] = color
        quads++
    }

    /** A plain rectangle, textured from the atlas's white block. */
    private fun rect(
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        color: Int,
    ) {
        val w = GlyphAtlas.WHITE_TEX
        quad(l, t, r, b, w, w, w, w, color)
    }

    /** The atlas slot of cell [i]'s glyph, drawn into it if new; -1 if it has none. */
    private fun glyphSlot(
        i: Int,
        face: Int,
        span: Int,
    ): Int {
        val cp = cells.codepoints[i]
        val cluster = cells.isCluster(i)
        val text = if (cluster) cells.text(i) else null
        return atlas.slot(cp, text, face, span) { c, left, top, w ->
            c.translate(left, top)
            val paint = whites[face]
            if (!cluster && BoxDrawing.handles(cp)) {
                BoxDrawing.draw(c, cp, 0f, 0f, w, cellHeight.toFloat(), paint)
            } else {
                drawGlyph(c, text ?: String(Character.toChars(cp)), 0f, w, paint)
            }
        }
    }

    /**
     * Row [row] at 0, 0 as one drawVertices call: a quad per run of background, per glyph (from [atlas], tinted
     * its colour) and per underline or strike-through. One draw however many colours the row has.
     */
    private fun drawRow(
        c: Canvas,
        row: Int,
    ) {
        val cols = cells.cols
        val from = row * cols
        val cw = cellWidth.toFloat()
        val ch = cellHeight.toFloat()
        val most = cols * 4
        if (vertColors.size < most * 6) {
            verts = FloatArray(most * 12)
            texs = FloatArray(most * 12)
            vertColors = IntArray(most * 6)
        }
        quads = 0
        // Backgrounds, a quad per run of one colour.
        var x = 0
        while (x < cols) {
            val color = backgroundOf(from + x)
            var end = x + 1
            while (end < cols && backgroundOf(from + end) == color) end++
            // The default background is the view's own, drawn once already.
            if (color != null && (transparent || color != cells.defaultBg)) rect(x * cw, 0f, end * cw, ch, color)
            x = end
        }
        // Glyphs and their lines.
        val line = max(1f, ch / 16f)
        x = 0
        while (x < cols) {
            val i = from + x
            val f = cells.flags[i]
            val span = if (f.has(Cells.WIDE)) 2 else 1
            val cp = cells.codepoints[i]
            if (f.has(Cells.SPACER)) {
                x++
                continue
            }
            val fg = foregroundOf(i)
            if (cp != 0 && cp != ' '.code && !f.has(Cells.INVISIBLE)) {
                val face = (if (f.has(Cells.BOLD)) 1 else 0) or (if (f.has(Cells.ITALIC)) 2 else 0)
                val slot = glyphSlot(i, face, span)
                if (slot != -1) {
                    val sx = GlyphAtlas.slotX(slot).toFloat()
                    val sy = GlyphAtlas.slotY(slot).toFloat()
                    // A colour emoji keeps its colours; only fading applies to it.
                    val tint = if (slot and GlyphAtlas.COLORED != 0) fg or 0x00ffffff else fg
                    quad(x * cw, 0f, (x + span) * cw, ch, sx, sy, sx + span * cw, sy + ch, tint)
                }
            }
            if (f.has(Cells.UNDERLINE) || f.has(Cells.LINK)) rect(x * cw, baseline + line, (x + span) * cw, baseline + 2 * line, fg)
            if (f.has(Cells.STRIKETHROUGH)) rect(x * cw, ch / 2 - line / 2, (x + span) * cw, ch / 2 + line / 2, fg)
            x += span
        }
        if (quads == 0) return
        c.drawVertices(Canvas.VertexMode.TRIANGLES, quads * 12, verts, 0, texs, 0, vertColors, 0, null, 0, 0, atlas.paint)
    }

    /** One character of its own (CJK, emoji, a symbol from a fallback font), squeezed into its cells if it's wider. */
    private fun drawGlyph(
        c: Canvas,
        text: String,
        left: Float,
        span: Float,
        paint: Paint,
    ) {
        val w = paint.measureText(text)
        if (w <= span * 1.05f) {
            // Narrower: centred in its cells, as foot centres a wide character.
            c.drawText(text, left + (span - w).coerceAtLeast(0f) / 2, baseline, paint)
            return
        }
        c.withScale(span / w, 1f, left, baseline) { drawText(text, left, baseline, paint) }
    }

    private fun cellLeft(col: Int) = padPx + col * cellWidth.toFloat()

    private fun cellTop(row: Int) = padPx + row * cellHeight.toFloat()

    private fun drawCursor(canvas: Canvas) {
        val x = cells.cursorX
        val y = cells.cursorY
        if (!cells.cursorVisible || x < 0 || y < 0 || x >= cells.cols || y >= cells.rows || composingText.isNotEmpty()) return
        val col = if (cells.cursorWideTail && x > 0) x - 1 else x
        val i = cells.index(col, y)
        val span = if (cells.flags[i].has(Cells.WIDE)) 2 else 1
        val l = cellLeft(col)
        val t = cellTop(y)
        val r = l + span * cellWidth
        val b = t + cellHeight
        fill.color = if (cells.cursorColor != 0) cells.cursorColor else colors.cursor
        val thin = max(2f, cellWidth / 6f)
        if (!keyboardFocus || cells.cursorStyle == Vt.CURSOR_HOLLOW) {
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = thin / 1.5f
            canvas.drawRect(l + thin / 3, t + thin / 3, r - thin / 3, b - thin / 3, fill)
            fill.style = Paint.Style.FILL
            return
        }
        when (cells.cursorStyle) {
            Vt.CURSOR_BAR -> canvas.drawRect(l, t, l + thin, b, fill)
            Vt.CURSOR_UNDERLINE -> canvas.drawRect(l, b - thin, r, b, fill)
            else -> {
                canvas.drawRect(l, t, r, b, fill)
                val text = cells.text(i)
                if (text.isNotEmpty() && text != " ") {
                    val f = cells.flags[i]
                    val paint = paints[(if (f.has(Cells.BOLD)) 1 else 0) or (if (f.has(Cells.ITALIC)) 2 else 0)]
                    paint.color = colors.cursorText
                    canvas.withTranslation(0f, t) {
                        if (BoxDrawing.handles(cells.codepoints[i]) && !cells.isCluster(i)) {
                            BoxDrawing.draw(this, cells.codepoints[i], l, 0f, r - l, cellHeight.toFloat(), paint)
                        } else {
                            drawGlyph(this, text, l, r - l, paint)
                        }
                    }
                }
            }
        }
    }

    /** The soft keyboard's word in progress, at the cursor, underlined: where it'll be typed. */
    private fun drawComposing(canvas: Canvas) {
        if (composingText.isEmpty() || cells.cursorX < 0 || cells.cursorY < 0) return
        val paint = paints[0]
        paint.color = colors.foreground
        val l = cellLeft(cells.cursorX)
        val t = cellTop(cells.cursorY)
        val w = paint.measureText(composingText)
        fill.color = colors.background
        canvas.drawRect(l, t, l + w, t + cellHeight, fill)
        canvas.drawText(composingText, l, t + baseline, paint)
        fill.color = colors.selectionBackground
        canvas.drawRect(l, t + cellHeight - max(2f, cellHeight / 12f), l + w, t + cellHeight, fill)
    }

    /** A thin bar at the right while the view is back in the scrollback, where it is. */
    private fun drawScrollbar(
        canvas: Canvas,
        vt: Vt?,
    ) {
        if (vt == null || vt.state.has(Vt.AT_BOTTOM)) return
        val (total, offset, len) = vt.scrollbar()?.toList() ?: return
        if (total <= len) return
        val h = height.toFloat()
        val top = h * offset / total
        val bottom = h * (offset + len) / total
        fill.color = (colors.selectionBackground and 0x00ffffff) or 0xb0000000.toInt()
        val w = max(3f, resources.displayMetrics.density * 3)
        canvas.drawRect(width - w, top, width.toFloat(), max(bottom, top + w * 4), fill)
    }

    // --- Selection ---

    private var selection: IntArray? = null
    private var actionMode: ActionMode? = null

    private val handleRadius get() = resources.displayMetrics.density * 11

    /** The handle at each end, centre x and y in the view: start's below its cell's left, end's below its right. */
    private fun handlePoints(): FloatArray? {
        val s = selection ?: return null
        return floatArrayOf(
            cellLeft(s[0]),
            cellTop(s[1]) + cellHeight + handleRadius,
            cellLeft(s[2] + 1),
            cellTop(s[3]) + cellHeight + handleRadius,
        )
    }

    private fun drawSelectionHandles(canvas: Canvas) {
        val s = selection ?: return
        val p = handlePoints() ?: return
        handlePaint.color = colors.selectionBackground
        val r = handleRadius
        if (s[1] >= 0) {
            canvas.drawCircle(p[0] - r, p[1], r, handlePaint)
            canvas.drawRect(p[0] - r, p[1] - r, p[0], p[1], handlePaint)
        }
        if (s[3] >= 0) {
            canvas.drawCircle(p[2] + r, p[3], r, handlePaint)
            canvas.drawRect(p[2], p[3] - r, p[2] + r, p[3], handlePaint)
        }
    }

    private fun refreshSelection() {
        selection = session?.vt?.selection()
        if (selection == null) {
            actionMode?.finish()
        } else if (actionMode == null) {
            actionMode = startActionMode(selectionActions, ActionMode.TYPE_FLOATING)
        } else {
            actionMode?.invalidateContentRect()
        }
        invalidate()
    }

    private fun clearSelection() {
        session?.vt?.clearSelection()
        selection = null
        actionMode?.finish()
        invalidate()
    }

    private val selectionActions =
        object : ActionMode.Callback2() {
            override fun onCreateActionMode(
                mode: ActionMode,
                menu: Menu,
            ): Boolean {
                menu.add(Menu.NONE, android.R.id.copy, 0, android.R.string.copy)
                menu.add(Menu.NONE, android.R.id.paste, 1, android.R.string.paste)
                menu.add(Menu.NONE, android.R.id.selectAll, 2, android.R.string.selectAll)
                return true
            }

            override fun onPrepareActionMode(
                mode: ActionMode,
                menu: Menu,
            ) = false

            override fun onActionItemClicked(
                mode: ActionMode,
                item: MenuItem,
            ): Boolean {
                when (item.itemId) {
                    android.R.id.copy -> {
                        copySelection()
                        clearSelection()
                    }
                    android.R.id.paste -> {
                        clearSelection()
                        pasteClipboard()
                    }
                    android.R.id.selectAll -> {
                        val vt = session?.vt ?: return true
                        vt.select(0, 0, cells.cols - 1, cells.rows - 1)
                        refreshSelection()
                    }
                    else -> return false
                }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                if (actionMode === mode) actionMode = null
            }

            override fun onGetContentRect(
                mode: ActionMode,
                view: View,
                outRect: Rect,
            ) {
                val s = selection
                if (s == null) {
                    outRect.set(0, 0, width, height)
                    return
                }
                val top = if (s[1] >= 0) cellTop(s[1]) else 0f
                val bottom = if (s[3] >= 0) cellTop(s[3]) + cellHeight else height.toFloat()
                val oneRow = s[1] == s[3] && s[1] >= 0
                val left = if (oneRow) cellLeft(s[0]) else 0f
                val right = if (oneRow) cellLeft(s[2] + 1) else width.toFloat()
                outRect.set(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
            }
        }

    private fun clipboard() = context.getSystemService(ClipboardManager::class.java)

    private fun copySelection() {
        val text = session?.vt?.selectedText()?.takeIf { it.isNotEmpty() } ?: return
        clipboard()?.setPrimaryClip(ClipData.newPlainText(null, text))
    }

    private fun pasteClipboard() {
        val clip = clipboard()?.primaryClip ?: return
        val text = (0 until clip.itemCount).joinToString("") { clip.getItemAt(it).coerceToText(context) }
        if (text.isNotEmpty()) paste(text)
    }

    // --- Keys ---

    override fun key(
        code: Int,
        pressed: Boolean,
    ) {
        val s = session ?: return
        when (val a = keys.key(s.vt, code, pressed)) {
            is TerminalKeyboard.Action.Send -> {
                if (selection != null) clearSelection()
                s.send(a.bytes)
                toBottom()
            }
            TerminalKeyboard.Action.Copy -> copySelection()
            TerminalKeyboard.Action.Paste -> pasteClipboard()
            null -> Unit
        }
    }

    override fun typeCodePoint(cp: Int) {
        val s = session ?: return
        s.type(String(Character.toChars(cp)))
        toBottom()
    }

    override fun paste(text: String) {
        val s = session ?: return
        s.paste(text)
        toBottom()
    }

    override fun composing(text: CharSequence) {
        val t = text.toString()
        if (t == composingText) return
        composingText = t
        invalidate()
    }

    /** Back to where the program writes, as a key typed in the scrollback does. */
    private fun toBottom() {
        val vt = session?.vt ?: return
        if (!vt.state.has(Vt.AT_BOTTOM)) {
            vt.scrollToBottom()
            invalidate()
        }
    }

    // --- What the program does besides drawing ---

    private fun event(e: VtEvent) {
        when (e) {
            VtEvent.Bell -> performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            is VtEvent.Title -> onTitle(e.title)
            is VtEvent.Copy -> clipboard()?.setPrimaryClip(ClipData.newPlainText(null, e.text))
            // Claude Code's "needs your attention": a notification only when this terminal isn't in front.
            is VtEvent.Notify -> if (!hasWindowFocus() && e.title.isNotBlank()) context.postLinuxNotification(e.title, e.body.takeIf { it.isNotBlank() })
        }
    }

    // --- Touch ---

    private fun colAt(x: Float) = ((x - padPx) / cellWidth).toInt().coerceIn(0, (cells.cols - 1).coerceAtLeast(0))

    private fun rowAt(y: Float) = ((y - padPx) / cellHeight).toInt().coerceIn(0, (cells.rows - 1).coerceAtLeast(0))

    private val mouseReports get() = session?.vt?.state?.has(Vt.MOUSE) == true

    private fun mouse(
        action: Int,
        button: Int,
        x: Float,
        y: Float,
    ) {
        val s = session ?: return
        s.vt
            .mouse(action, button, keys.mods, x, y, width, height, cellWidth, cellHeight, padPx, padPx, false)
            ?.let(s::send)
    }

    /** Scrolled rows not yet sent, as a fraction of a row. */
    private var scrollRest = 0f
    private val scroller = OverScroller(context)
    private var flingY = 0

    /** Which handle a finger drags: 0 none, 1 the start, 2 the end; 3 a long press still extending its selection. */
    private var dragging = 0
    private var anchor: IntArray? = null
    private var scaling = false

    private val gestures =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    scroller.forceFinished(true)
                    scrollRest = 0f
                    return true
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (selection != null) {
                        clearSelection()
                        return true
                    }
                    val col = colAt(e.x)
                    val row = rowAt(e.y)
                    if (mouseReports) {
                        mouse(Vt.PRESS, Vt.LEFT, e.x, e.y)
                        mouse(Vt.RELEASE, Vt.LEFT, e.x, e.y)
                        // On the cursor's row (Claude Code's prompt, which takes the mouse too) it also brings up the
                        // keyboard, after the program has the click: as a tap on a foot window's prompt does.
                        if (row == cells.cursorY) performClick()
                        return true
                    }
                    val link = session?.vt?.link(col, row) ?: urlAt(col, row)
                    if (link != null) {
                        open(link)
                        return true
                    }
                    performClick()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (scaling) return
                    val vt = session?.vt ?: return
                    val col = colAt(e.x)
                    val row = rowAt(e.y)
                    if (!vt.selectWord(col, row)) vt.select(col, row, col, row)
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    anchor = vt.selection()
                    dragging = 3
                    refreshSelection()
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (scaling || dragging != 0) return true
                    scrollBy(distanceY, e2.x, e2.y)
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    if (scaling || dragging != 0 || session?.vt?.state?.has(Vt.ALTERNATE or Vt.MOUSE) != false) return false
                    flingY = 0
                    scroller.fling(0, 0, 0, -velocityY.toInt(), 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
                    postInvalidateOnAnimation()
                    return true
                }
            },
        )

    private val scale =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    scaling = true
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val z = (zoom * detector.scaleFactor).coerceIn(MIN_EM_PX / emPx, MAX_EM_PX / emPx)
                    // In whole pixels of the em, so a pinch doesn't re-lay the grid for every small move.
                    if (((emPx * z).roundToInt()) == (emPx * zoom).roundToInt()) return false
                    zoom = (emPx * z).roundToInt() / emPx
                    measureCells()
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) = Unit
            },
        )

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        val y = scroller.currY
        scrollBy((y - flingY).toFloat(), width / 2f, height / 2f)
        flingY = y
        postInvalidateOnAnimation()
    }

    /**
     * A finger moved [dy] pixels up: the scrollback moves with it, or a program that takes the mouse gets the wheel,
     * or one on the alternate screen (less, man) gets arrow keys, as Termux does.
     */
    private fun scrollBy(
        dy: Float,
        x: Float,
        y: Float,
    ) {
        val s = session ?: return
        scrollRest += dy / cellHeight
        val rows = scrollRest.toInt()
        if (rows == 0) return
        scrollRest -= rows
        val state = s.vt.state
        when {
            state.has(Vt.MOUSE) -> repeat(abs(rows)) { mouse(Vt.PRESS, if (rows > 0) Vt.WHEEL_DOWN else Vt.WHEEL_UP, x, y) }
            state.has(Vt.ALTERNATE) -> repeat(abs(rows)) { s.press(if (rows > 0) VtKey.ARROW_DOWN else VtKey.ARROW_UP) }
            else -> {
                s.vt.scroll(rows)
                if (selection != null) selection = s.vt.selection()
                invalidate()
            }
        }
    }

    /** A tap on the terminal that nothing in it took: [onTap], which brings up the keyboard. */
    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }

    // The gesture detector tells a tap from the rest and calls performClick for it.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            scaling = false
            dragging = handleAt(event.x, event.y)
        }
        scale.onTouchEvent(event)
        val ends = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        if (dragging != 0) {
            if (action == MotionEvent.ACTION_MOVE && !scaling) dragSelection(event.x, event.y)
            // A long press that started the selection keeps its gesture, so the detector sees it end.
            if (dragging == 3) gestures.onTouchEvent(event)
            if (ends) {
                dragging = 0
                anchor = null
                actionMode?.invalidateContentRect()
            }
            return true
        }
        gestures.onTouchEvent(event)
        return true
    }

    private fun handleAt(
        x: Float,
        y: Float,
    ): Int {
        val s = selection ?: return 0
        val p = handlePoints() ?: return 0
        val reach = handleRadius * 2.2f
        val start = if (s[1] >= 0) hypot(x - (p[0] - handleRadius), y - p[1]) else Float.MAX_VALUE
        val end = if (s[3] >= 0) hypot(x - (p[2] + handleRadius), y - p[3]) else Float.MAX_VALUE
        return when {
            start <= reach && start <= end -> 1
            end <= reach -> 2
            else -> 0
        }
    }

    private fun dragSelection(
        x: Float,
        y: Float,
    ) {
        val vt = session?.vt ?: return
        // A handle hangs below its end, to the outside: the finger on it points at the cell it holds.
        val row = rowAt(if (dragging == 3) y else y - handleRadius - cellHeight / 2f)
        when (dragging) {
            1 -> vt.moveSelection(start = true, col = colAt(x + handleRadius), row = row)
            2 -> vt.moveSelection(start = false, col = colAt(x - handleRadius), row = row)
            3 -> {
                // From the long-pressed word, forwards or backwards.
                val a = anchor ?: return
                val col = colAt(x)
                val before = row < a[1] || row == a[1] && col < a[0]
                if (before) vt.select(a[2], a[3], col, row) else vt.select(a[0], a[1], col, row)
            }
        }
        refreshSelection()
    }

    /** A web address written out in row [row] around column [col], not marked as a link. */
    private fun urlAt(
        col: Int,
        row: Int,
    ): String? {
        if (row >= cells.rows) return null
        val text = StringBuilder()
        val colOf = ArrayList<Int>()
        for (x in 0 until cells.cols) {
            val i = cells.index(x, row)
            if (cells.flags[i].has(Cells.SPACER)) continue
            val t = cells.text(i).ifEmpty { " " }
            for (ch in t) {
                text.append(ch)
                colOf += x
            }
        }
        for (m in URL.findAll(text)) {
            val url = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '\'', '"')
            val first = colOf[m.range.first]
            val last = colOf[m.range.first + url.length - 1]
            if (col in first..last) return url
        }
        return null
    }

    private fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "can't open $url", it) }
    }

    // Last, once every field above has its value: measuring fills the atlas's paints.
    init {
        isFocusable = false
        measureCells()
    }

    override fun onDetachedFromWindow() {
        actionMode?.finish()
        for (n in rowNodes) n?.discardDisplayList()
        rowNodes = arrayOfNulls(0)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TAG = "native-terminal"
        const val HOLD_MAX_MS = 1000L
        const val MIN_EM_PX = 6f
        const val MAX_EM_PX = 200f

        val URL = Regex("""(?:https?|file)://[^\s<>"'`]+""")
    }
}
