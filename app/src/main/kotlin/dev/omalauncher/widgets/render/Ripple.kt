package dev.omalauncher.widgets.render

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max

/**
 * A ripple from where a node that takes taps is pressed, in the foreground color, kept inside the node's
 * rounded corners. Only the ripple is clipped to them, not what the node holds. [radius] is in dp.
 */
internal fun Modifier.ripple(
    source: InteractionSource,
    radius: () -> Float,
): Modifier = this then RippleElement(source, radius)

private class RippleElement(
    val source: InteractionSource,
    val radius: () -> Float,
) : ModifierNodeElement<RippleNode>() {
    override fun create() = RippleNode(source, radius)

    override fun update(node: RippleNode) {
        node.radius = radius
    }

    // The source is remembered with the node, so it decides whether the node is still the same.
    override fun equals(other: Any?) = other is RippleElement && other.source === source

    override fun hashCode() = System.identityHashCode(source)
}

private class RippleNode(
    private val source: InteractionSource,
    var radius: () -> Float,
) : Modifier.Node(),
    DrawModifierNode {
    private class Wave(
        val at: Offset,
    ) {
        val spread = Animatable(0f)
        val alpha = Animatable(1f)
        var growing: Job? = null
    }

    private val waves = ArrayList<Wave>()
    private val pressed = HashMap<PressInteraction.Press, Wave>()
    private val path = Path()

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> {
                        val w = Wave(i.pressPosition)
                        waves += w
                        pressed[i] = w
                        w.growing = coroutineScope.launch { w.spread.animateTo(1f, tween(GROW_MS, easing = FastOutSlowInEasing)) }
                        // The waves aren't state: drawing only follows the ones it has already read.
                        invalidateDraw()
                    }
                    is PressInteraction.Release -> pressed.remove(i.press)?.let(::fade)
                    is PressInteraction.Cancel -> pressed.remove(i.press)?.let(::fade)
                }
            }
        }
    }

    override fun onDetach() {
        waves.clear()
        pressed.clear()
    }

    /** A quick tap still shows the whole ripple: it finishes spreading as it fades. */
    private fun fade(w: Wave) {
        coroutineScope.launch {
            w.growing?.cancel()
            launch { w.spread.animateTo(1f, tween(FADE_MS / 2, easing = FastOutSlowInEasing)) }
            w.alpha.animateTo(0f, tween(FADE_MS, easing = LinearEasing))
            waves.remove(w)
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (waves.isEmpty()) return
        val r = radius().dp.toPx()
        path.reset()
        path.addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r)))
        val color = Palette.foreground
        clipPath(path) {
            for (w in waves) {
                // Far enough to reach the corner furthest from the finger.
                val reach = hypot(max(w.at.x, size.width - w.at.x), max(w.at.y, size.height - w.at.y))
                drawCircle(color.copy(alpha = ALPHA * w.alpha.value), reach * (START + (1 - START) * w.spread.value), w.at)
            }
        }
    }

    private companion object {
        const val GROW_MS = 300
        const val FADE_MS = 250
        const val ALPHA = 0.16f

        /** How big it starts, a part of how far it spreads. */
        const val START = 0.2f
    }
}
