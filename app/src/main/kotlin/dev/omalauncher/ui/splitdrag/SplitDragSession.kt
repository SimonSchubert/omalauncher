package dev.omalauncher.ui.splitdrag

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap

/** An app the launcher is dragging out of the recent strip: what to draw, and what to open where it's let go. */
class SplitDragSession {
    /** The dragged app's name; null while nothing is dragged. */
    var label by mutableStateOf<String?>(null)
        private set

    var icon by mutableStateOf<ImageBitmap?>(null)
        private set

    /** The finger, in window pixels. */
    var finger by mutableStateOf<Offset?>(null)
        private set

    /** Whether this drag can split the screen (a partner app, and the launcher is home) and pop up. */
    var canSplit by mutableStateOf(false)
        private set
    var canPopUp by mutableStateOf(false)
        private set

    /** Whether this is One UI, which decides how the halves lie; see [splitsSideBySide]. */
    var oneUi by mutableStateOf(false)
        private set

    /** Lays the zones out instead of home's [dropZones], for drags elsewhere (the taskbar); null for home's. Set only with begin(), which also sets the state the overlay reads. */
    private var layout: ((Float, Float) -> List<DropZone>)? = null

    /** The window's size, which the overlay keeps up to date; the zones are laid out in it. */
    var area: Pair<Float, Float>? = null

    private var onDrop: ((DropZone) -> Unit)? = null

    fun zones(): List<DropZone> {
        val (w, h) = area ?: return emptyList()
        return layout?.invoke(w, h) ?: dropZones(w, h, canSplit, canPopUp, splitsSideBySide(w, h, oneUi))
    }

    fun begin(
        label: String,
        icon: ImageBitmap?,
        at: Offset,
        canSplit: Boolean,
        canPopUp: Boolean,
        oneUi: Boolean,
        layout: ((Float, Float) -> List<DropZone>)? = null,
        onDrop: (DropZone) -> Unit,
    ) {
        this.canSplit = canSplit
        this.canPopUp = canPopUp
        this.oneUi = oneUi
        this.layout = layout
        this.onDrop = onDrop
        this.icon = icon
        this.label = label
        finger = at
    }

    fun move(at: Offset) {
        if (label != null) finger = at
    }

    /** Let go at [at]: whatever zone holds it opens; null when the drag was cancelled. */
    fun end(at: Offset?) {
        val drop = onDrop
        val zone = at?.let { zones().zoneAt(it.x, it.y) }
        label = null
        icon = null
        finger = null
        onDrop = null
        layout = null
        if (zone != null) drop?.invoke(zone)
    }
}
