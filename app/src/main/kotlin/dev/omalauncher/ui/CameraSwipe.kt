package dev.omalauncher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Pulling past the first page to the right reveals and opens the camera, with rubber-band
 * resistance and haptic feedback like iOS. Pages past the first scroll normally.
 */
@Composable
internal fun CameraSwipeContainer(
    pager: PagerState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val thresholdPx = with(density) { 96.dp.toPx() }
    val maxDragPx = with(density) { 150.dp.toPx() }

    val offsetX = remember { Animatable(0f) }
    var triggeredHaptic by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val nestedScroll =
        remember(pager, enabled) {
            object : NestedScrollConnection {
                // Dragging back left while revealed consumes the drag to fold it away.
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (!enabled || offsetX.value <= 0f) return Offset.Zero
                    if (available.x < 0f) {
                        val consumed = available.x.coerceAtLeast(-offsetX.value)
                        scope.launch {
                            val next = offsetX.value + consumed
                            offsetX.snapTo(next)
                            if (next < thresholdPx && triggeredHaptic) triggeredHaptic = false
                        }
                        return Offset(consumed, 0f)
                    }
                    return Offset.Zero
                }

                // Overscroll to the right on panel 1 drives the camera pull.
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (!enabled || pager.currentPage != 0) return Offset.Zero
                    if (available.x > 0f) {
                        val delta = available.x * 0.55f
                        scope.launch {
                            val next = (offsetX.value + delta).coerceAtMost(maxDragPx)
                            offsetX.snapTo(next)
                            if (next >= thresholdPx && !triggeredHaptic) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                triggeredHaptic = true
                            }
                        }
                        return Offset(available.x, 0f)
                    }
                    return Offset.Zero
                }

                // Releasing triggers the camera when past the threshold, or springs back.
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (offsetX.value > 0f) {
                        if (offsetX.value >= thresholdPx || available.x > 800f) {
                            launchCamera(context)
                            offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessLow))
                        } else {
                            offsetX.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow))
                        }
                        triggeredHaptic = false
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }

    Box(modifier.nestedScroll(nestedScroll)) {
        // Camera action revealed under the shifted pages on the left edge.
        if (offsetX.value > 0f) {
            val progress = (offsetX.value / thresholdPx).coerceIn(0f, 1f)
            val pastThreshold = offsetX.value >= thresholdPx
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(with(density) { maxDragPx.toDp() })
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .size(54.dp)
                        .graphicsLayer {
                            scaleX = 0.75f + (progress * 0.35f)
                            scaleY = scaleX
                            alpha = progress
                        }.clip(LauncherStyle.shape(27.dp))
                        .background(if (pastThreshold) Palette.accent else Palette.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        imageVector = Icons.Camera,
                        contentDescription = "camera",
                        modifier = Modifier.size(26.dp),
                        colorFilter = ColorFilter.tint(if (pastThreshold) Palette.background else Palette.accent),
                    )
                }
            }
        }

        // Home grid shifting smoothly with finger drag.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX.value },
        ) {
            content()
        }
    }
}
