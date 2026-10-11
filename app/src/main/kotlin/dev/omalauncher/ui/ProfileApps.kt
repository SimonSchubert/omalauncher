package dev.omalauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.omalauncher.AndroidAppCatalog

/** The badge on an app of [profile]: a briefcase in a work profile, a lock in the private space, none in the phone's own. */
internal fun AndroidAppCatalog.badge(profile: Long?): ImageVector? =
    when {
        profile == null -> null
        isPrivate(profile) -> Icons.Lock
        profile in workProfiles -> Icons.Work
        // Not loaded yet.
        else -> null
    }

/** [glyph] on another profile's app icon, bottom right like stock launchers' badge, in the theme's colors. */
@Composable
internal fun BoxScope.ProfileBadge(
    iconSize: Dp,
    glyph: ImageVector,
) {
    val size = iconSize * 0.4f
    val shape = LauncherStyle.shape(size / 2)
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .size(size)
            .clip(shape)
            .background(Palette.accent)
            // Rimmed in the background color, so it stands off an icon of the same color (silhouettes).
            .border(1.dp, Palette.background, shape),
        contentAlignment = Alignment.Center,
    ) {
        Image(glyph, glyph.name, Modifier.size(size * 0.6f), colorFilter = ColorFilter.tint(Palette.background))
    }
}

/** A paused work app's or locked private app's icon, whatever it is (bitmap, silhouette, initials): drained of color and faded, as stock launchers grey them. */
internal fun Modifier.paused(paused: Boolean): Modifier =
    if (!paused) {
        this
    } else {
        drawWithCache {
            val paint =
                Paint().apply {
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                    alpha = 0.45f
                }
            onDrawWithContent {
                drawIntoCanvas { it.saveLayer(size.toRect(), paint) }
                drawContent()
                drawIntoCanvas { it.restore() }
            }
        }
    }
