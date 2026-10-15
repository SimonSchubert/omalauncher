package dev.omalauncher.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How big app icons draw, as a multiplier of the drawer's 52dp slot. Home tiles fill their cells,
 * so this sizes the places an icon has a slot of its own: the drawer, the recent strip, the
 * shortcut menu and the pin dialog. The picker offers [sizes]; choosing one redraws every icon.
 */
object IconSize {
    /** The sizes the picker offers, as multipliers of the drawer's icon slot. */
    val sizes = listOf(0.8f to "small", 1f to "normal", 1.25f to "large")

    /** The chosen size; 1f keeps the drawer's own 52dp icons. */
    var scale by mutableStateOf(1f)
        private set

    /** The drawer's icon slot at the chosen size. */
    val dp: Dp get() = 52.dp * scale

    /** The chosen size's label, for the settings row. */
    val label: String get() = sizes.firstOrNull { it.first == scale }?.second ?: "normal"

    /** Reads the saved size; called once when the launcher starts. */
    fun load(context: Context) {
        scale = context.getSharedPreferences("launcher", Context.MODE_PRIVATE).getFloat(KEY, 1f)
    }

    /** Picks [scale] and saves it; the caller redraws the icons. */
    fun choose(
        context: Context,
        scale: Float,
    ) {
        this.scale = scale
        context.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putFloat(KEY, scale).apply()
    }

    private const val KEY = "icon_size"
}
