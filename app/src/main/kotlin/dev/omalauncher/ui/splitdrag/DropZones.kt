package dev.omalauncher.ui.splitdrag

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

/** Where an app dragged out of the launcher can go: let go over [trigger], it opens in [preview]'s place. */
data class DropZone(
    val kind: ZoneKind,
    val label: String,
    val trigger: ZoneBox,
    val preview: ZoneBox,
)

/** The top band, as a share of the height, that opens an app full screen. */
private const val BAND = 0.07f

/**
 * Whether the system splits a [width]×[height] window left and right (true) or top and bottom. One UI goes by shape:
 * side by side unless the window is narrow (a folded screen, under 0.6 wide for its height). Stock Android goes by
 * orientation: side by side only in landscape, top and bottom otherwise, an unfolded square-ish screen included.
 */
fun splitsSideBySide(
    width: Float,
    height: Float,
    oneUi: Boolean,
): Boolean = if (oneUi) width / height >= 0.6f else width > height

/**
 * The zones for a [width]×[height] window, whole areas as One UI's own: a band along the top opens the app full screen,
 * the middle pops it up ([canPopUp]), and the halves split the screen with it ([canSplit]): left and right when
 * [sideBySide], top and bottom otherwise. Without split, the rest opens full screen too. The first zone holding a point
 * is the one it lands in.
 */
fun dropZones(
    width: Float,
    height: Float,
    canSplit: Boolean,
    canPopUp: Boolean,
    sideBySide: Boolean,
): List<DropZone> {
    val whole = ZoneBox(0f, 0f, width, height)
    val band = height * BAND
    val upright = width <= height
    val popW = width * if (upright) 0.6f else 0.4f
    val popH = height * if (upright) 0.4f else 0.6f
    val popUp = ZoneBox((width - popW) / 2, (height - popH) / 2, (width + popW) / 2, (height + popH) / 2)
    return buildList {
        add(DropZone(ZoneKind.FullScreen, "Full screen", ZoneBox(0f, 0f, width, band), whole))
        if (canPopUp) add(DropZone(ZoneKind.PopUp, "Pop-up", popUp, popUp))
        when {
            !canSplit -> add(DropZone(ZoneKind.FullScreen, "Full screen", ZoneBox(0f, band, width, height), whole))
            !sideBySide -> {
                add(DropZone(ZoneKind.SplitTop, "Split · top", ZoneBox(0f, band, width, height / 2), ZoneBox(0f, 0f, width, height / 2)))
                add(DropZone(ZoneKind.SplitBottom, "Split · bottom", ZoneBox(0f, height / 2, width, height), ZoneBox(0f, height / 2, width, height)))
            }
            else -> {
                add(DropZone(ZoneKind.SplitLeft, "Split · left", ZoneBox(0f, band, width / 2, height), ZoneBox(0f, 0f, width / 2, height)))
                add(DropZone(ZoneKind.SplitRight, "Split · right", ZoneBox(width / 2, band, width, height), ZoneBox(width / 2, 0f, width, height)))
            }
        }
    }
}

/** The zone a drop at ([x], [y]) lands in: the first holding it; none outside the window. */
fun List<DropZone>.zoneAt(
    x: Float,
    y: Float,
): DropZone? = firstOrNull { it.trigger.contains(x, y) }
