package dev.omalauncher.taskbar

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The taskbar's settings: on or off (off by default), what it lists, and the apps pinned to it. Saved as they're set. */
class TaskbarSettings(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private var on by mutableStateOf(prefs.getBoolean(ENABLED, false))
    var enabled: Boolean
        get() = on
        set(value) {
            on = value
            prefs.edit().putBoolean(ENABLED, value).apply()
        }

    private var shows by mutableStateOf(TaskbarMode.of(prefs.getString(MODE, null)) ?: TaskbarMode.Recent)
    var mode: TaskbarMode
        get() = shows
        set(value) {
            shows = value
            prefs.edit().putString(MODE, value.id).apply()
        }

    /** Usage keys of the apps pinned (see [usageKey], [linuxKey]), in the order they were pinned; older pins read as Android ones. */
    var pinned by mutableStateOf(prefs.getString(PINNED, null)?.split('\n')?.filter { it.isNotEmpty() }?.map(::pinnedKey)?.distinct() ?: emptyList())
        private set

    /** Pins the app with usage [key], or unpins it; saves the keys prefixed from then on. */
    fun togglePin(key: String) {
        pinned = if (key in pinned) pinned - key else pinned + key
        prefs.edit().putString(PINNED, pinned.joinToString("\n")).apply()
    }

    private companion object {
        const val ENABLED = "taskbar_enabled"
        const val MODE = "taskbar_mode"
        const val PINNED = "taskbar_pinned"
    }
}

/** Whether the taskbar's accessibility service runs; the service sets it, settings show a warning while it doesn't. */
object TaskbarStatus {
    var serviceOn by mutableStateOf(false)
}
