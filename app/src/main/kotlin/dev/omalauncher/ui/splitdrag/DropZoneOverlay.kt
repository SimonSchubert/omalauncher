package dev.omalauncher.ui.splitdrag

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.TextSize
import dev.omalauncher.ui.frame

/**
 * While an app is dragged out of home: the zones it can be dropped in, in the theme's accent. Each zone's trigger
 * shows faintly; the zone under the finger shows where the app will open, framed, with what it does. Once the finger
 * reaches an edge the system draws its own zones and this fades. On One UI it never takes the drop: the system opens
 * the app. On stock Android a drop over the launcher's window is nobody else's, so the launcher opens the app itself.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DropZoneOverlay(session: SplitDragSession) {
    val platform = remember { SplitDrag.platform() }
    val currentPlatform by rememberUpdatedState(platform)
    val target =
        remember(session) {
            object : DragAndDropTarget {
                override fun onMoved(event: DragAndDropEvent) {
                    val e = event.toAndroidDragEvent()
                    session.move(e.x, e.y)
                }

                override fun onExited(event: DragAndDropEvent) = session.leave()

                override fun onEnded(event: DragAndDropEvent) = session.end()

                // One UI opens an unhandled drop where it lands, so it's never ours there. Stock Android leaves a drop
                // over our window unhandled and opens nothing: the launcher opens the app full screen. Runs before
                // onEnded, while the session still holds the app.
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    if (!currentPlatform.launcherOpensOnDrop()) return false
                    val open = session.open ?: return false
                    open()
                    return true
                }
            }
        }
    val app = session.app
    val finger = session.finger
    // Shown for the whole drag, until the app is let go: when One UI's own zones take over at an edge, the strips stay,
    // so moving on to another zone still shows where each one is.
    val shown by animateFloatAsState(if (app != null) 1f else 0f, tween(150), label = "drop zones")
    // What was drawn last, so it fades out with the zones once the drag ends or the finger leaves.
    val last = remember { LastDrawn() }
    if (app != null) last.app = app
    if (finger != null) last.finger = finger
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // Only our own drag: text or files dragged over home from another app aren't ours to draw for.
            .dragAndDropTarget(shouldStartDragAndDrop = { session.app != null }, target = target),
    ) {
        // The session's live values while dragging over home; the last ones while the overlay fades out.
        val drawnApp = session.app ?: last.app
        // The finger over home; none while One UI draws its own zone, which shows where the app opens instead of ours.
        val drawnFinger = if (session.app != null) session.finger else last.finger
        if (shown == 0f || drawnApp == null) return@BoxWithConstraints
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val nav = WindowInsets.navigationBars
        val navEdge =
            when {
                nav.getLeft(density, direction) > 0 -> NavEdge.Left
                nav.getRight(density, direction) > 0 -> NavEdge.Right
                else -> null
            }
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val zones = remember(w, h, platform, navEdge) { dropZones(w, h, platform, navEdge) }
        val active = drawnFinger?.let { (x, y) -> zones.zoneAt(x, y) }
        Box(Modifier.fillMaxSize().alpha(shown)) {
            // Where to go: each zone's trigger, faintly, with what it does, so the edges read as zones too and not
            // only the pop-up in the middle.
            for (z in zones) {
                val t = z.trigger ?: continue
                if (z == active) continue
                ZoneFrame(t, fill = 0.10f, line = 0.35f, width = LauncherStyle.borderWidth)
                StripLabel(t, z.label)
            }
            // Where it'll open: the zone under the finger, with what it does.
            if (active != null) {
                ZoneFrame(active.preview, fill = 0.22f, line = 1f, width = LauncherStyle.focusWidth)
                ZoneLabel(active.preview, active.label)
            }
            BasicText(
                "Release over a zone to open $drawnApp",
                style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Body),
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        // Below the top strip, so its label stays readable.
                        .padding(top = 12.dp + with(density) { (zones.firstOrNull { it.kind == ZoneKind.FullScreen }?.trigger?.bottom ?: 0f).toDp() })
                        .frame(LauncherStyle.borderWidth, LauncherStyle.tileBorder, Palette.glass, radius = 12.dp)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** The dragged app and the finger last seen, to draw while the overlay fades out; not state, nothing waits on it. */
private class LastDrawn {
    var app: String? = null
    var finger: Pair<Float, Float>? = null
}

/** [box] filled and outlined in the accent; [fill] and [line] are the accent's opacity. */
@Composable
private fun ZoneFrame(
    box: ZoneBox,
    fill: Float,
    line: Float,
    width: androidx.compose.ui.unit.Dp,
) {
    val density = LocalDensity.current
    with(density) {
        Box(
            Modifier
                .offset { IntOffset(box.left.toInt(), box.top.toInt()) }
                .size((box.right - box.left).toDp(), (box.bottom - box.top).toDp())
                .padding(6.dp)
                .frame(width, Palette.accent.copy(alpha = line), Palette.accent.copy(alpha = fill)),
        )
    }
}

/**
 * What dropping in the strip [box] does, small, along the strip: turned to run down a side strip (reading up on the
 * left edge, down on the right), level on the top one.
 */
@Composable
private fun StripLabel(
    box: ZoneBox,
    label: String,
) {
    val upright = box.bottom - box.top > box.right - box.left
    val turn = if (!upright) 0f else if (box.left == 0f) -90f else 90f
    val density = LocalDensity.current
    with(density) {
        Box(
            Modifier
                .offset { IntOffset(box.left.toInt(), box.top.toInt()) }
                .size((box.right - box.left).toDp(), (box.bottom - box.top).toDp()),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                label,
                style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small),
                softWrap = false,
                // Measured at its own length, then turned: a side strip is narrower than the label.
                modifier = Modifier.wrapContentSize(unbounded = true).graphicsLayer { rotationZ = turn },
            )
        }
    }
}

/** What dropping in [box] does, in its middle. */
@Composable
private fun ZoneLabel(
    box: ZoneBox,
    label: String,
) {
    val density = LocalDensity.current
    with(density) {
        Box(
            Modifier
                .offset { IntOffset(box.left.toInt(), box.top.toInt()) }
                .size((box.right - box.left).toDp(), (box.bottom - box.top).toDp()),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                label,
                style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title),
                modifier = Modifier.frame(LauncherStyle.borderWidth, LauncherStyle.tileBorder, Palette.glass, radius = 12.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
