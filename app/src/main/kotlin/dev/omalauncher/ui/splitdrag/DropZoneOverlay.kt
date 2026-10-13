package dev.omalauncher.ui.splitdrag

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.TextSize
import dev.omalauncher.ui.frame
import kotlin.math.roundToInt

/**
 * While the launcher drags an app out of the recent strip: every zone faintly with what it does, the one under the
 * finger where the app will open, and the app's icon under the finger. Over everything; takes no touches.
 */
@Composable
fun DropZoneOverlay(session: SplitDragSession) {
    val shown by animateFloatAsState(if (session.label != null) 1f else 0f, tween(150), label = "drop zones")
    // What was drawn last, so it fades out instead of vanishing when the drag ends.
    val last = remember { LastDrawn() }
    session.label?.let { last.label = it }
    session.finger?.let { last.finger = it }
    if (session.label != null) last.icon = session.icon
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        session.area = w to h
        val label = session.label ?: last.label
        if (shown == 0f || label == null) return@BoxWithConstraints
        val finger = session.finger ?: last.finger
        val zones = dropZones(w, h, session.canSplit, session.canPopUp)
        val active = finger?.let { zones.zoneAt(it.x, it.y) }
        val density = LocalDensity.current
        Box(Modifier.fillMaxSize().alpha(shown)) {
            for (z in zones) {
                if (z == active) continue
                Area(z.trigger, fill = 0.06f, line = 0.30f, width = LauncherStyle.borderWidth)
                Label(z.trigger, z.label, strong = false)
            }
            if (active != null) {
                Area(active.preview, fill = 0.25f, line = 1f, width = LauncherStyle.focusWidth)
                Label(active.preview, active.label, strong = true)
            }
            BasicText(
                "Release over a zone to open $label",
                style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Body),
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        // Below the full-screen band, so its label stays readable.
                        .padding(top = 12.dp + with(density) { (zones.first().trigger.bottom).toDp() })
                        .frame(LauncherStyle.borderWidth, LauncherStyle.tileBorder, Palette.glass, radius = 12.dp)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            val icon = last.icon
            if (finger != null && icon != null) {
                val s = with(density) { 56.dp.toPx() }
                Image(icon, null, Modifier.offset { IntOffset((finger.x - s / 2).roundToInt(), (finger.y - s / 2).roundToInt()) }.size(56.dp))
            }
        }
    }
}

/** The label, finger and icon last drawn, to draw while the overlay fades out; not state, nothing waits on it. */
private class LastDrawn {
    var label: String? = null
    var finger: Offset? = null
    var icon: ImageBitmap? = null
}

/** [box] filled and outlined in the accent; [fill] and [line] are the accent's opacity. */
@Composable
private fun Area(
    box: ZoneBox,
    fill: Float,
    line: Float,
    width: Dp,
) {
    with(LocalDensity.current) {
        Box(
            Modifier
                .offset { IntOffset(box.left.toInt(), box.top.toInt()) }
                .size((box.right - box.left).toDp(), (box.bottom - box.top).toDp())
                .padding(6.dp)
                .frame(width, Palette.accent.copy(alpha = line), Palette.accent.copy(alpha = fill)),
        )
    }
}

/** What dropping in [box] does, in its middle: plain while faint, framed for the zone under the finger. */
@Composable
private fun Label(
    box: ZoneBox,
    label: String,
    strong: Boolean,
) {
    with(LocalDensity.current) {
        Box(
            Modifier
                .offset { IntOffset(box.left.toInt(), box.top.toInt()) }
                .size((box.right - box.left).toDp(), (box.bottom - box.top).toDp()),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                label,
                style = Mono.copy(color = Palette.accent.copy(alpha = if (strong) 1f else 0.75f), fontSize = if (strong) TextSize.Title else TextSize.Body),
                modifier = if (strong) Modifier.frame(LauncherStyle.borderWidth, LauncherStyle.tileBorder, Palette.glass, radius = 12.dp).padding(horizontal = 14.dp, vertical = 8.dp) else Modifier,
            )
        }
    }
}
