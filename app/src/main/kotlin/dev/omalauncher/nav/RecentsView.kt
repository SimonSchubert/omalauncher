package dev.omalauncher.nav

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import androidx.compose.ui.graphics.toArgb
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.wallpaperCrop
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The launcher's recents, drawn over everything by [NavigationService]. A swipe up lifts the app on screen
 * into a card under the finger ([drag]), with the apps before it coming in from the left; holding
 * settles them into a row to browse ([browse]). Tap a card to switch to it, swipe one up to close it,
 * tap the background or swipe up from the bottom for home, swipe in from a side to go back to the app.
 *
 * The app under the finger never really moves: Android lets only the phone's own launcher do that.
 * Everything here is drawn from screenshots, over the real windows, and fades once the screen
 * underneath has caught up.
 */
internal class RecentsView(
    context: Context,
    entries: List<RecentEntry>,
    /** The first entry is the app on screen right now, under this view. */
    private var current: Boolean,
    /** The theme's background, as home shows it; the theme's background color without one. */
    private val backdrop: Bitmap?,
    /** The apps before the current one come in while dragging: the launcher's recents, not the phone's. */
    private val neighbors: Boolean,
    private val actions: Actions,
) : View(context) {
    interface Actions {
        /** Brings back an app that isn't on screen. */
        fun open(entry: RecentEntry)

        fun home()

        /** Closes an app for good: out of recents, and its process if Android allows. */
        fun dismiss(entry: RecentEntry)

        /** Browsing: the view starts taking touches. */
        fun interactive()

        /** Done: the view's window goes. */
        fun closed()
    }

    private enum class Mode { Drag, Morph, Browse }

    private inner class Card(
        val entry: RecentEntry,
    ) {
        val rect = RectF()
        var alpha = 1f

        /** How far it's been swiped up, to close it. */
        var lift = 0f
        val shot = entry.shot
        val shotRect = shot?.let { RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()) }
        val shader = shot?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }

    private val density = resources.displayMetrics.density
    private val cards = entries.map(::Card).toMutableList()
    private var mode = Mode.Drag

    /** A card has followed the finger: transitions start from where it is. */
    private var dragged = false

    /** Asked to browse before the window was laid out: the cards' places depend on its size. */
    private var whenSized: (() -> Unit)? = null
    private var animator: ValueAnimator? = null

    /** Browsing: how far the row is pulled right, toward the older apps. */
    private var scroll = 0f
    private var radius = 0f
    private var backdropAlpha = 1f

    /** Labels, icons and "clear all": only once browsing. */
    private var chromeAlpha = 0f

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val text =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        }
    private val matrix = Matrix()
    private val screen = Rect()
    private val iconRect = RectF()
    private val clearAll = RectF()

    private val gap get() = GAP_DP * density
    private val cardW get() = width * SCALE
    private val pitch get() = cardW + gap
    private val maxScroll get() = (cards.size - 1).coerceAtLeast(0) * pitch

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        screen.set(0, 0, w, h)
        whenSized?.invoke()
        whenSized = null
    }

    // --- Following the finger ---

    /** The current app as a card [up] px above where the finger landed and [dx] px to the side. */
    fun drag(
        up: Float,
        dx: Float,
    ) {
        if (mode != Mode.Drag || width == 0 || cards.isEmpty() || !current) return
        dragged = true
        val w = width.toFloat()
        val h = height.toFloat()
        val progress = (up / (h * 0.45f)).coerceIn(0f, 1f)
        val scale = 1f - (1f - SCALE) * progress
        // The card's bottom edge stays a little below the finger; it drifts sideways with it, by less.
        val bottom = (h - up * 0.9f).coerceAtLeast(h * scale + h * 0.06f)
        val cx = w / 2 + dx * 0.6f
        val cw = w * scale
        val ch = h * scale
        radius = CORNER_DP * density * (progress * 6).coerceAtMost(1f)
        for (i in cards.indices) {
            val card = cards[i]
            val x = cx - i * (cw + gap * scale / SCALE)
            card.rect.set(x - cw / 2, bottom - ch, x + cw / 2, bottom)
            card.alpha = if (i == 0) 1f else if (neighbors) ((progress - 0.1f) / 0.3f).coerceIn(0f, 1f) else 0f
        }
        invalidate()
    }

    // --- Where it goes from there ---

    /** Settles into the row of recent apps, then takes touches. Without a drag before, it rises into place. */
    fun browse() {
        if (mode == Mode.Browse) return
        if (width == 0) {
            whenSized = ::browse
            return
        }
        if (!dragged) {
            // Opened without a card under the finger (from home, or a screen that can't be captured).
            for (i in cards.indices) {
                browseRect(i, cards[i].rect)
                cards[i].rect.offset(0f, height * 0.12f)
                cards[i].alpha = 0f
            }
            backdropAlpha = 0f
            radius = CORNER_DP * density
        }
        scroll = 0f
        morph(
            duration = 340,
            ease = EMPHASIZED,
            radiusTo = CORNER_DP * density,
            backdropTo = 1f,
            chromeTo = 1f,
            target = { i, _, out ->
                browseRect(i, out)
                1f
            },
        ) {
            mode = Mode.Browse
            actions.interactive()
        }
    }

    /**
     * Off home: the cards shrink away over the background, which stays solid meanwhile, so neither the phone's
     * own closing animation nor home's first frames show through. Then it fades into home, which has the same
     * background.
     */
    fun toHome() {
        actions.home()
        val h = height.toFloat()
        morph(
            duration = 280,
            ease = EMPHASIZED,
            radiusTo = CORNER_DP * density,
            backdropTo = 1f,
            chromeTo = 0f,
            fadeLate = true,
            target = { _, card, out ->
                val r = card.rect
                val s = if (dragged && mode == Mode.Drag) 0.25f else 0.8f
                out.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2, r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2)
                out.offset(0f, h * 0.06f)
                0f
            },
        )
        // Home is up by the time the card is nearly gone: the fade overlaps the card's last stretch.
        fadeOut(delay = 170)
    }

    /** The whole view fades, revealing what's under it now, then goes. */
    private fun fadeOut(delay: Long) {
        animate()
            .alpha(0f)
            .setStartDelay(delay)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction(actions::closed)
            .start()
    }

    /** Back into the app on screen, which never left: the card grows back over it. */
    fun toApp() {
        if (!current || cards.isEmpty()) {
            toHome()
            return
        }
        expand(0, duration = 220, then = actions::closed)
    }

    /** For the phone's recents: the card steps back and fades while those come in. */
    fun toPhoneRecents() {
        val w = width.toFloat()
        val h = height.toFloat()
        morph(
            duration = 300,
            ease = EMPHASIZED,
            radiusTo = CORNER_DP * density,
            backdropTo = 0f,
            chromeTo = 0f,
            fadeLate = true,
            target = { i, _, out ->
                out.set(w / 2 - w * 0.3f, h * 0.46f - h * 0.3f, w / 2 + w * 0.3f, h * 0.46f + h * 0.3f)
                out.offset(-i * (w * 0.6f + gap), 0f)
                0f
            },
            then = actions::closed,
        )
    }

    /** Switches to card [i]: it grows to fill the screen while the app comes up underneath, then the view fades. */
    private fun open(i: Int) {
        if (i == 0 && current) {
            toApp()
            return
        }
        actions.open(cards[i].entry)
        // The app's own opening animation runs under the card; let it finish before showing it.
        expand(i, duration = 280) { fadeOut(delay = 160) }
    }

    private fun expand(
        i: Int,
        duration: Long,
        then: () -> Unit,
    ) {
        val w = width.toFloat()
        val h = height.toFloat()
        morph(
            duration = duration,
            ease = DecelerateInterpolator(1.5f),
            radiusTo = 0f,
            backdropTo = 1f,
            chromeTo = 0f,
            target = { j, card, out ->
                if (j == i) {
                    out.set(0f, 0f, w, h)
                    1f
                } else {
                    out.set(card.rect)
                    0f
                }
            },
            then = then,
        )
    }

    private fun dismiss(card: Card) {
        animator?.cancel()
        mode = Mode.Morph
        val from = card.lift
        animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val t = it.animatedValue as Float
                    card.lift = from + (height - from) * t
                    card.alpha = 1f - t
                    browseRect(cards.indexOf(card), card.rect)
                    invalidate()
                }
                onEnd {
                    val index = cards.indexOf(card)
                    cards.remove(card)
                    if (index == 0 && current) current = false
                    actions.dismiss(card.entry)
                    if (cards.isEmpty()) {
                        toHome()
                        return@onEnd
                    }
                    // The others close the gap.
                    scroll = scroll.coerceIn(0f, maxScroll)
                    morph(220, DecelerateInterpolator(1.5f), CORNER_DP * density, 1f, 1f, target = { i, _, out ->
                        browseRect(i, out)
                        1f
                    }) { mode = Mode.Browse }
                }
                start()
            }
    }

    private fun clearAll() {
        for (card in cards.toList()) actions.dismiss(card.entry)
        cards.clear()
        current = false
        toHome()
    }

    // --- Layout and animation ---

    /** Card [i] in the row: the newest in the middle, older ones to its left, [scroll] pulling them right. */
    private fun browseRect(
        i: Int,
        out: RectF,
    ) {
        val w = cardW
        val h = height * SCALE
        val cx = width / 2f - i * pitch + scroll
        val cy = height * 0.46f - (cards.getOrNull(i)?.lift ?: 0f)
        out.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    private fun layoutBrowse() {
        for (i in cards.indices) {
            val card = cards[i]
            browseRect(i, card.rect)
            card.alpha = (1f - card.lift / (height * 0.6f)).coerceIn(0f, 1f)
        }
        invalidate()
    }

    /** Animates every card from where it is to [target] (which also gives its alpha), and the rest along. */
    private fun morph(
        duration: Long,
        ease: TimeInterpolator,
        radiusTo: Float,
        backdropTo: Float,
        chromeTo: Float,
        /** Cards fade a beat after moving off, once the screen behind has switched. */
        fadeLate: Boolean = false,
        target: (Int, Card, RectF) -> Float,
        then: () -> Unit = {},
    ) {
        animator?.cancel()
        val fromRects = cards.map { RectF(it.rect) }
        val fromAlphas = cards.map { it.alpha }
        val toRects = cards.map { RectF() }
        val toAlphas = cards.mapIndexed { i, card -> target(i, card, toRects[i]) }
        val fromRadius = radius
        val fromBackdrop = backdropAlpha
        val fromChrome = chromeAlpha
        mode = Mode.Morph
        animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                interpolator = ease
                addUpdateListener {
                    val t = it.animatedValue as Float
                    val fade = if (fadeLate) ((t - 0.25f) / 0.75f).coerceIn(0f, 1f) else t
                    for (i in cards.indices) {
                        val a = fromRects[i]
                        val b = toRects[i]
                        cards[i].rect.set(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))
                        cards[i].alpha = lerp(fromAlphas[i], toAlphas[i], fade)
                    }
                    radius = lerp(fromRadius, radiusTo, t)
                    backdropAlpha = lerp(fromBackdrop, backdropTo, fade)
                    chromeAlpha = lerp(fromChrome, chromeTo, t)
                    invalidate()
                }
                onEnd(then)
                start()
            }
    }

    private fun animateScroll(to: Float) {
        animator?.cancel()
        animator =
            ValueAnimator.ofFloat(scroll, to).apply {
                duration = 260
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener {
                    scroll = it.animatedValue as Float
                    layoutBrowse()
                }
                start()
            }
    }

    private fun animateLift(card: Card) {
        animator?.cancel()
        animator =
            ValueAnimator.ofFloat(card.lift, 0f).apply {
                duration = 200
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener {
                    card.lift = it.animatedValue as Float
                    layoutBrowse()
                }
                start()
            }
    }

    private fun ValueAnimator.onEnd(action: () -> Unit) =
        addListener(
            object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) action()
                }
            },
        )

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    // --- Touch, while browsing ---

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var startScroll = 0f
    private var touched: Card? = null
    private var axis = 0 // 0 undecided, 1 sideways, 2 up/down
    private var fromSide = false
    private var fromBottom = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (mode != Mode.Browse) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                downX = e.x
                downY = e.y
                startScroll = scroll
                axis = 0
                touched = cards.firstOrNull { it.rect.contains(e.x, e.y) }
                val edge = EDGE_DP * density
                fromSide = e.x < edge || e.x > width - edge
                fromBottom = e.y > height - edge
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                if (axis == 0 && hypot(dx, dy) > slop) axis = if (abs(dx) > abs(dy)) 1 else 2
                when {
                    axis == 1 && !fromSide -> {
                        // A little give past either end.
                        val raw = startScroll + dx
                        scroll = if (raw < 0f) raw * 0.3f else if (raw > maxScroll) maxScroll + (raw - maxScroll) * 0.3f else raw
                        layoutBrowse()
                    }
                    axis == 2 && !fromBottom -> {
                        touched?.let {
                            it.lift = (-dy).coerceAtLeast(-24 * density)
                            layoutBrowse()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(e)
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val vy = velocity?.yVelocity ?: 0f
                val dx = e.x - downX
                val dy = e.y - downY
                when {
                    axis == 0 -> {
                        val card = touched
                        when {
                            card != null -> open(cards.indexOf(card))
                            cards.isNotEmpty() && clearAll.contains(e.x, e.y) -> clearAll()
                            else -> toHome()
                        }
                    }
                    // Back from the side: to the app on screen, or home.
                    axis == 1 && fromSide -> if (abs(dx) > BACK_DP * density) toApp() else Unit
                    axis == 1 -> {
                        val index = ((scroll + vx * 0.15f) / pitch).roundToInt().coerceIn(0, (cards.size - 1).coerceAtLeast(0))
                        animateScroll(index * pitch)
                    }
                    axis == 2 && fromBottom -> if (-dy > BACK_DP * density) toHome() else Unit
                    axis == 2 -> {
                        val card = touched ?: return true
                        if (card.lift > height * 0.18f || vy < -1500f) dismiss(card) else animateLift(card)
                    }
                }
                velocity?.recycle()
                velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                touched?.let(::animateLift)
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        if (backdropAlpha > 0f) {
            val a = (backdropAlpha * 255).roundToInt()
            if (backdrop != null) {
                bitmapPaint.alpha = a
                canvas.drawBitmap(backdrop, wallpaperCrop(backdrop.width, backdrop.height, width, height, 0.5f), screen, bitmapPaint)
            } else {
                val c = Palette.background.toArgb()
                canvas.drawColor(Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)))
            }
            // Dimmed, so the cards stand out from it.
            canvas.drawColor(Color.argb((a * 0.35f).roundToInt(), 0, 0, 0))
        }
        for (card in cards.asReversed()) drawCard(canvas, card)
        if (chromeAlpha > 0f) drawChrome(canvas)
    }

    private fun drawCard(
        canvas: Canvas,
        card: Card,
    ) {
        val r = card.rect
        if (card.alpha <= 0f || r.right < 0f || r.left > width) return
        val a = (card.alpha * 255).roundToInt()
        val shader = card.shader
        if (shader != null) {
            matrix.setRectToRect(card.shotRect, r, Matrix.ScaleToFit.FILL)
            shader.setLocalMatrix(matrix)
            cardPaint.shader = shader
            cardPaint.alpha = a
            canvas.drawRoundRect(r, radius, radius, cardPaint)
        } else {
            // No screenshot yet (or the app forbids them): the app's icon on the theme's surface.
            fill.color = Palette.surface.toArgb()
            fill.alpha = a
            canvas.drawRoundRect(r, radius, radius, fill)
            card.entry.icon?.let { icon ->
                val s = minOf(72 * density, r.width() * 0.3f)
                iconRect.set(r.centerX() - s / 2, r.centerY() - s / 2, r.centerX() + s / 2, r.centerY() + s / 2)
                bitmapPaint.alpha = a
                canvas.drawBitmap(icon, null, iconRect, bitmapPaint)
            }
        }
        if (chromeAlpha > 0f) {
            // Icon and name above the card.
            val ca = (chromeAlpha * card.alpha * 255).roundToInt()
            val s = 22 * density
            val y = r.top - 12 * density - s
            var x = r.left
            card.entry.icon?.let { icon ->
                iconRect.set(x, y, x + s, y + s)
                bitmapPaint.alpha = ca
                canvas.drawBitmap(icon, null, iconRect, bitmapPaint)
                x += s + 8 * density
            }
            text.color = Palette.foreground.toArgb()
            text.alpha = ca
            val room = r.right - x
            val label = card.entry.label
            val n = text.breakText(label, true, room, null)
            canvas.drawText(if (n < label.length) label.take((n - 1).coerceAtLeast(0)) + "…" else label, x, y + s / 2 - (text.ascent() + text.descent()) / 2, text)
        }
    }

    private fun drawChrome(canvas: Canvas) {
        val a = (chromeAlpha * 255).roundToInt()
        val label = if (cards.isEmpty()) "no recent apps" else "[ clear all ]"
        text.color = (if (cards.isEmpty()) Palette.muted else Palette.accent).toArgb()
        text.alpha = a
        val w = text.measureText(label)
        val y = height * 0.88f
        canvas.drawText(label, (width - w) / 2, y, text)
        val pad = 16 * density
        clearAll.set((width - w) / 2 - pad, y + text.ascent() - pad, (width + w) / 2 + pad, y + text.descent() + pad)
    }

    private companion object {
        /** Cards in the row are this much of the screen. */
        const val SCALE = 0.6f
        const val GAP_DP = 18
        const val CORNER_DP = 24
        const val EDGE_DP = 24
        const val BACK_DP = 44

        /** Material's emphasized easing: quick off the mark, gentle landing. */
        val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)

        fun lerp(
            a: Float,
            b: Float,
            t: Float,
        ) = a + (b - a) * t
    }
}
