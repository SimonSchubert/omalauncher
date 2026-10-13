package dev.omalauncher.ui.splitdrag

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** An app being dragged out of the launcher, for the overlay to draw: which one, and where the finger is over home. */
class SplitDragSession {
    /** The dragged app's name; null while nothing is dragged. */
    var app by mutableStateOf<String?>(null)
        private set

    /** The finger over the launcher's window, in its pixels; null before it moves and once the system's own zones show. */
    var finger by mutableStateOf<Pair<Float, Float>?>(null)
        private set

    fun begin(app: String) {
        this.app = app
        finger = null
    }

    fun move(
        x: Float,
        y: Float,
    ) {
        if (app != null) finger = x to y
    }

    /** The finger left the launcher's window: the system draws its own zones there. */
    fun leave() {
        finger = null
    }

    fun end() {
        app = null
        finger = null
    }
}
