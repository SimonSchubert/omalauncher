package dev.omalauncher.linux

import dev.omalauncher.widgets.lang.shQuote

/** A foreground Wayland client; the command is shell code, the app ID is one quoted value. */
data class EmbeddedAppCommand(
    val command: String,
    val appId: String? = null,
) {
    fun error(): String? = when {
        command.isBlank() -> "LinuxApp command must not be blank"
        appId != null && !appId.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,254}")) -> "Invalid Wayland app ID"
        else -> null
    }

    /** SDL2/SDL3 use these hints; other clients can read OMALAUNCHER_APP_ID or use appId. */
    fun launch(id: String): String =
        "env OMALAUNCHER_APP_ID=${shQuote(id)} " +
            "SDL_VIDEO_WAYLAND_WMCLASS=${shQuote(id)} SDL_APP_ID=${shQuote(id)} " +
            "bash -c ${shQuote(command)}"
}
