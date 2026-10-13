package dev.omalauncher.ui.splitdrag

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What dropping an app in a zone does. */
enum class ZoneKind { SplitLeft, SplitRight, SplitTop, SplitBottom, FullScreen, PopUp }

/** A rectangle in window pixels; [right] and [bottom] are exclusive. */
data class ZoneBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(
        x: Float,
        y: Float,
    ) = x >= left && x < right && y >= top && y < bottom
}

/**
 * Where an app dragged out of the launcher can go. Released over [trigger], the system opens it in [preview]'s place;
 * a zone without a trigger takes every point no other zone does.
 */
data class DropZone(
    val kind: ZoneKind,
    val label: String,
    val trigger: ZoneBox?,
    val preview: ZoneBox,
)

/** Which system places a dropped app: Samsung's One UI, or stock Android. */
sealed interface SplitPlatform {
    data object OneUi : SplitPlatform

    data object Stock : SplitPlatform
}

/**
 * Whether the launcher opens an app dropped over its own window. Stock Android takes a drop only at its top and bottom
 * strips; anywhere else the launcher opens the app full screen itself. One UI opens an unhandled drop where it lands
 * (pop-up or split), so there the launcher never takes it.
 */
fun SplitPlatform.launcherOpensOnDrop(): Boolean = this is SplitPlatform.Stock

/** The side the navigation buttons stand on when they stand beside the screen; One UI gives that edge no zone. */
enum class NavEdge { Left, Right }

/** One UI's edge strips are this share of the screen's shorter side. */
private const val EDGE_SHARE = 0.056f

/**
 * The zones for a [width]×[height] window. One UI splits at the left and right edges (on a narrow, folded screen,
 * into the top or bottom half by the height the app is let go at), opens full screen from the top strip and pops the app up anywhere else. Stock Android
 * opens it full screen wherever it's dropped.
 */
fun dropZones(
    width: Float,
    height: Float,
    platform: SplitPlatform,
    navEdge: NavEdge?,
): List<DropZone> {
    val whole = ZoneBox(0f, 0f, width, height)
    val shorter = min(width, height)
    val longer = max(width, height)
    val popW = if (width <= height) shorter * 0.6f else longer * 0.5f
    val popH = if (width <= height) longer * 0.5f else shorter * 0.6f
    val popUp = ZoneBox((width - popW) / 2, (height - popH) / 2, (width + popW) / 2, (height + popH) / 2)
    return when (platform) {
        SplitPlatform.Stock -> listOf(DropZone(ZoneKind.FullScreen, "Full screen", null, whole))
        SplitPlatform.OneUi -> {
            val t = (shorter * EDGE_SHARE).roundToInt().toFloat()
            val left = navEdge != NavEdge.Left
            val right = navEdge != NavEdge.Right
            // A folded phone's screen is too narrow to split sideways: either side splits it top or bottom, by the
            // height the app is let go at, so each side strip is two zones.
            val narrow = width / height < 0.6f
            buildList {
                if (narrow) {
                    val top = DropZone(ZoneKind.SplitTop, "Split · top", null, ZoneBox(0f, 0f, width, height / 2))
                    val bottom = DropZone(ZoneKind.SplitBottom, "Split · bottom", null, ZoneBox(0f, height / 2, width, height))
                    if (left) {
                        add(top.copy(trigger = ZoneBox(0f, 0f, t, height / 2)))
                        add(bottom.copy(trigger = ZoneBox(0f, height / 2, t, height)))
                    }
                    if (right) {
                        add(top.copy(trigger = ZoneBox(width - t, 0f, width, height / 2)))
                        add(bottom.copy(trigger = ZoneBox(width - t, height / 2, width, height)))
                    }
                } else {
                    if (left) add(DropZone(ZoneKind.SplitLeft, "Split · left", ZoneBox(0f, 0f, t, height), ZoneBox(0f, 0f, width / 2, height)))
                    if (right) add(DropZone(ZoneKind.SplitRight, "Split · right", ZoneBox(width - t, 0f, width, height), ZoneBox(width / 2, 0f, width, height)))
                }
                add(DropZone(ZoneKind.FullScreen, "Full screen", ZoneBox(t, 0f, width - t, t), whole))
                add(DropZone(ZoneKind.PopUp, "Pop-up", null, popUp))
            }
        }
    }
}

/** The zone a drop at ([x], [y]) lands in: the first whose trigger holds it, else the one without a trigger. */
fun List<DropZone>.zoneAt(
    x: Float,
    y: Float,
): DropZone? = firstOrNull { it.trigger?.contains(x, y) == true } ?: firstOrNull { it.trigger == null }
