package dev.omalauncher.widgets.render

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import dev.omalauncher.widgets.lang.Builtins
import dev.omalauncher.widgets.lang.Env
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.WNode
import dev.omalauncher.widgets.lang.num
import dev.omalauncher.widgets.runtime.WidgetInstance
import kotlinx.coroutines.flow.collectLatest

/** A node's properties bound to its environment, read in whichever phase needs them. */
internal class Props(
    val el: WNode.Element,
    val env: Env,
    val inst: WidgetInstance,
) {
    fun has(name: String) = name in el.props

    /** "Text.color" for error reports, built once per property rather than on every evaluation. */
    private val wheres = HashMap<String, String>()

    private fun where(name: String) = wheres.getOrPut(name) { "${el.kind}.$name" }

    /**
     * Readers of constant and missing floats by name and value, kept so the
     * modifiers that capture them stay equal across recompositions. By value
     * too: callers can give the same property different defaults.
     */
    private val constants = HashMap<Pair<String, Float>, () -> Float>()

    /** Reads now, from the caller's phase (composition, layout or draw). */
    fun now(name: String): Value? = el.props[name]?.let { inst.eval(it, env, where(name)) }

    fun color(v: Value?): Color? = v?.let { Builtins.argb(it, inst) }?.let(::Color)

    /**
     * Reads in composition, through a derived state: the node recomposes only
     * when the result changes, not whenever an input does.
     */
    @Composable
    fun observe(name: String): Value? {
        val ev = el.props[name] ?: return null
        ev.const?.let { return it }
        return remember(ev, env) {
            val where = where(name)
            derivedStateOf(structuralEqualityPolicy()) { inst.eval(ev, env, where) }
        }.value
    }

    /** A float for the draw or layout phase; animated when the node says `animate name: ...`. */
    @Composable
    fun float(
        name: String,
        default: Float,
    ): () -> Float {
        val ev = el.props[name]
        if (ev?.const != null || ev == null) {
            val f = ev?.const?.num()?.toFloat() ?: default
            return constants.getOrPut(name to f) { { f } }
        }
        val read =
            remember(ev, env) {
                val where = where(name)
                // Not NaN or infinite: an animation towards one throws.
                val read: () -> Float = { inst.eval(ev, env, where).num()?.toFloat()?.takeIf { it.isFinite() } ?: default }
                read
            }
        val spec = el.animations[name] ?: return read
        val anim = remember(ev, env) { androidx.compose.animation.core.Animatable(Snapshot.withoutReadObservation { read() }) }
        val animSpec = remember(spec) { floatSpec(inst.eval(spec, env, "animate $name")) }
        LaunchedEffect(anim) { snapshotFlow(read).collectLatest { anim.animateTo(it, animSpec) } }
        // The same reader each time, so the modifiers that capture it stay equal.
        return remember(anim) { { anim.value } }
    }

    /**
     * The node's `offset` in dp, for placement. Animated when it says `animate offset: ...`, except while [held], its own
     * drag: then it follows the finger, and only going back (or on) once let go animates.
     */
    @Composable
    fun offset(held: State<Boolean>): () -> Offset {
        val ev = el.props["offset"] ?: return { Offset.Zero }
        val read =
            remember(ev, env) {
                val where = where("offset")
                val read: () -> Offset = {
                    val o = inst.eval(ev, env, where) as? Lst
                    fun at(i: Int) = o?.items?.getOrNull(i)?.num()?.toFloat()?.takeIf { it.isFinite() } ?: 0f
                    Offset(at(0), at(1))
                }
                read
            }
        val spec = el.animations["offset"] ?: return read
        val anim = remember(ev, env) { androidx.compose.animation.core.Animatable(Snapshot.withoutReadObservation { read() }, Offset.VectorConverter) }
        val animSpec = remember(spec) { offsetSpec(inst.eval(spec, env, "animate offset")) }
        LaunchedEffect(anim) {
            snapshotFlow { read() to held.value }.collectLatest { (to, dragging) -> if (dragging) anim.snapTo(to) else anim.animateTo(to, animSpec) }
        }
        return remember(anim) { { anim.value } }
    }

    @Composable
    fun color(
        name: String,
        default: () -> Color,
    ): () -> Color {
        val ev = el.props[name] ?: return default
        val read =
            remember(ev, env) {
                val where = where(name)
                val read: () -> Color = { color(inst.eval(ev, env, where)) ?: default() }
                read
            }
        val spec = el.animations[name] ?: return read
        val anim = remember(ev, env) { Animatable(Snapshot.withoutReadObservation { read() }) }
        val animSpec = remember(spec) { colorSpec(inst.eval(spec, env, "animate $name")) }
        LaunchedEffect(anim) { snapshotFlow(read).collectLatest { anim.animateTo(it, animSpec) } }
        return remember(anim) { { anim.value } }
    }

    companion object {
        fun easing(name: String): Easing =
            when (name) {
                "linear" -> LinearEasing
                "in" -> CubicBezierEasing(0.4f, 0f, 1f, 1f)
                "out" -> CubicBezierEasing(0f, 0f, 0.2f, 1f)
                else -> FastOutSlowInEasing
            }

        private fun <T> spec(v: Value): AnimationSpec<T> {
            val o = v as? Obj
            val f = o?.fields.orEmpty()
            return if (f["type"]?.toString() == "tween") {
                tween(((f["duration"]?.num() ?: 0.3) * 1000).toInt(), easing = easing(f["easing"]?.toString() ?: "ease"))
            } else {
                // Compose throws on a negative damping or a stiffness that isn't positive.
                spring(dampingRatio = (f["damping"]?.num()?.takeIf { it >= 0 } ?: 0.6).toFloat(), stiffness = (f["stiffness"]?.num()?.takeIf { it > 0 } ?: 300.0).toFloat())
            }
        }

        fun floatSpec(v: Value): AnimationSpec<Float> = spec(v)

        fun colorSpec(v: Value): AnimationSpec<Color> = spec(v)

        fun offsetSpec(v: Value): AnimationSpec<Offset> = spec(v)
    }
}
