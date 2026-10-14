package dev.omalauncher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/** Home tile visibility is shared by native terminals and ordinary widget editors. */
internal class HomeTileIme {
    var inputOwner by mutableStateOf<Any?>(null)
        private set
    private var inputToken: Any? = null

    /** The whole tile before cut, in window coordinates before lift. */
    var tile by mutableStateOf<Rect?>(null)
    var lift = 0f
        private set
    var cut by mutableFloatStateOf(0f)
        private set

    fun focusInput(owner: Any, token: Any) {
        if (inputOwner !== owner) tile = null
        inputOwner = owner
        inputToken = token
    }

    fun releaseInput(token: Any) {
        if (inputToken === token) clearInput()
    }

    fun clearInput() {
        if (inputOwner != null) tile = null
        inputOwner = null
        inputToken = null
    }

    fun liftFor(
        terminalBounds: Rect?,
        imeHeight: Int,
        imeTarget: Int,
        windowHeight: Int,
        margin: Float,
        top: Float,
        minHeight: Float,
    ): Float {
        val bounds = if (inputOwner != null) tile else terminalBounds?.let { tile ?: it }
        if (bounds == null) {
            lift = 0f
            cut = 0f
            return lift
        }
        fun overlap(ime: Int) = if (ime == 0) 0f else (bounds.bottom + margin - (windowHeight - ime)).coerceAtLeast(0f)
        val room = (bounds.top - top).coerceAtLeast(0f)
        lift = minOf(overlap(imeHeight), room)
        cut = (overlap(imeTarget) - room).coerceIn(0f, (bounds.height - minHeight).coerceAtLeast(0f))
        return lift
    }
}
