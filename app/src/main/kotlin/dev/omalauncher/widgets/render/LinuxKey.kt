package dev.omalauncher.widgets.render

import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.ControlPointers
import dev.omalauncher.ui.ControlPress
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.Palette
import dev.omalauncher.widgets.lang.LinuxKeys
import dev.omalauncher.widgets.lang.text

/** Each control receives all of its fingers, independently of presses on sibling controls. */
@Composable
internal fun LinuxKeyNode(p: Props, modifier: Modifier) {
    val target = p.observe("target")?.takeIf { LinuxKeys.validTarget(it) }?.toString()
    val term = target?.let { p.inst.linuxInputs.target(it) }
    val keys = p.observe("keys")?.let(LinuxKeys::codes)
    val window = term?.window
    val covered = LocalHomeCovered.current()
    val enabled = term != null && window != null && term.running && !term.stopping && keys != null && !covered
    val view = LocalView.current
    val claim = LocalLongPressClaim.current
    var held by remember { mutableIntStateOf(0) }
    val pointers = remember(term, window, keys, enabled, view, claim) {
        ControlPointers { id ->
            val lease = if (enabled && view.hasWindowFocus()) term.holdKeys(keys.orEmpty()) else null
            if (lease == null) null else {
                held++
                claim?.hold(id)
                ControlPress(
                    release = { lease.release(); claim?.release(id); held = (held - 1).coerceAtLeast(0) },
                    cancel = { lease.close(); claim?.release(id); held = (held - 1).coerceAtLeast(0) },
                )
            }
        }
    }
    // Focus may change without cancelling Compose's pointer coroutine.
    val focused = term?.tiles?.windowManager?.focused === window && window != null
    LaunchedEffect(pointers, focused) { if (!focused) pointers.cancel() }
    DisposableEffect(pointers, view) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (!focused) pointers.cancel() }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose {
            pointers.cancel()
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
        }
    }
    // A touch on a controller must not be treated as an outside tap on its LinuxApp.
    val boundsToken = remember { Any() }
    DisposableEffect(term, boundsToken) { onDispose { term?.tiles?.controls?.remove(boundsToken) } }
    val color = p.color("color") { Palette.accent }
    val radius = p.float("radius", 8f * LauncherStyle.cornerScale)
    val label = p.observe("text")?.text() ?: p.observe("keys")?.text().orEmpty()
    Box(
        modifier
            .onGloballyPositioned { coordinates ->
                if (term != null) term.tiles.controls[boundsToken] = term to coordinates.boundsInWindow().translate(0f, term.tiles.lift)
            }
            .semantics {
                contentDescription = label
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    val press = if (enabled && view.hasWindowFocus()) term.holdKeys(keys.orEmpty()) else null
                    press?.release()
                    press != null
                }
            }
            .pointerInput(pointers) {
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                val id = change.id.value
                                val inside = change.position.x >= 0 && change.position.y >= 0 &&
                                    change.position.x < size.width && change.position.y < size.height
                                when {
                                    pointers.contains(id) -> {
                                        val cancelled = change.isConsumed || !inside
                                        change.consume()
                                        if (cancelled) pointers.cancel(id) else if (!change.pressed) pointers.up(id)
                                    }
                                    !change.previousPressed && change.pressed && inside && !change.isConsumed -> {
                                        if (pointers.down(id)) change.consume()
                                    }
                                }
                            }
                        }
                    }
                } finally { pointers.cancel() }
            }
            .drawBehind {
                val tint = if (enabled) color() else Palette.muted
                val r = CornerRadius(radius().dp.toPx())
                if (held > 0) drawRoundRect(tint.copy(alpha = 0.25f), cornerRadius = r)
                val w = 1.dp.toPx()
                drawRoundRect(tint, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), r, style = Stroke(w))
            }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = WidgetMono.copy(color = if (enabled) color() else Palette.muted), maxLines = 1)
    }
}
