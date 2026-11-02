package dev.omalauncher.ui

import dev.omalauncher.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * What home shows while a widget holds the screen still: the launcher's accent around the screen's edge, with its
 * corners, and at the top a lock, the words, and the way out.
 *
 * Drawn here, over the pages, so a widget that covers its tile can't cover the way out. Only the button takes a tap;
 * the border and the words take none, so a finger passing over the chip doesn't unlock what it was using.
 */
@Composable
fun LockCue(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    /** A face fills the whole page: the border lies over its art at [OnFaceBorder], rather than framing it. */
    face: Boolean = false,
) {
    Box(modifier.fillMaxSize()) {
        // Nothing to tap: the widget under it keeps every touch that isn't the button's.
        Box(
            Modifier
                .fillMaxSize()
                .frame(CueBorder, Palette.accent.copy(alpha = if (face) OnFaceBorder else 1f), radius = 20.dp),
        )
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .padding(top = EdgeGap)
                .tile()
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LockIcon(Palette.accent)
            BasicText(stringResource(R.string.home_lock_locked), style = Mono.copy(fontSize = TextSize.Small))
            BracketAction(stringResource(R.string.home_lock_unlock), Palette.accent, onClick = onUnlock)
        }
    }
}

/** The cue's outline and its lock: 2 dp, like the focused tile's border. */
private val CueBorder = 2.dp

/** How much of the accent the border keeps over a face's art. */
private const val OnFaceBorder = 0.6f

/**
 * The prompt bar and the recent strip while a widget holds the screen still: drawn, and opening nothing. Only their
 * press is taken, so a finger can still scroll the strip, and it's taken before it reaches them: the bar's own tap
 * targets never see it.
 */
internal fun Modifier.lockedTaps(locked: Boolean): Modifier =
    if (!locked) {
        this
    } else {
        pointerInput(Unit) {
            awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume() }
        }
    }

/** A lock drawn on a 24-unit grid, like the launcher's other icons: the symbols font has none. */
@Composable
private fun LockIcon(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(2f * u, cap = StrokeCap.Round)
        // The shackle: the top half of a circle standing on the body.
        drawArc(
            color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(8f * u, 4f * u),
            size = Size(8f * u, 10f * u),
            style = stroke,
        )
        drawRoundRect(
            color,
            topLeft = Offset(4.5f * u, 9f * u),
            size = Size(15f * u, 11f * u),
            cornerRadius = CornerRadius(2f * u),
        )
    }
}
