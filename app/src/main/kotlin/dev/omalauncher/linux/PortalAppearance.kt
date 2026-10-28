package dev.omalauncher.linux

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale

/** The Settings portal's snapshot. The containing runtime directory is an Android-owned bind.
 * Replace the final entry, never follow a guest-supplied destination symlink. */
internal object PortalAppearance {
    const val FILE_NAME = "omalauncher-color-scheme"

    data class Preferences(val highContrast: Boolean = false, val reducedMotion: Boolean = false)

    /** Android's normal contrast is 0, higher is positive (API 34+); its public
     * animation preference disables animations at duration scale 0. Unavailable
     * or invalid values mean no preference, not a guessed accessibility setting. */
    fun preferences(contrast: Float?, animationScale: Float?): Preferences = Preferences(
        highContrast = contrast != null && contrast.isFinite() && contrast > 0f && contrast <= 1f,
        reducedMotion = animationScale == 0f,
    )

    fun text(light: Boolean, accent: Int, preferences: Preferences = Preferences()): String =
        (if (light) "light" else "dark") + "\n" + String.format(Locale.ROOT, "#%06x", accent and 0xffffff) + "\n" +
            (if (preferences.highContrast) "1" else "0") + "\n" + (if (preferences.reducedMotion) "1" else "0") + "\n"

    fun write(runtimeDir: File, light: Boolean, accent: Int, preferences: Preferences = Preferences()) {
        val temporary = Files.createTempFile(runtimeDir.toPath(), ".portal-appearance-", ".tmp")
        try {
            Files.write(temporary, text(light, accent, preferences).toByteArray(Charsets.UTF_8))
            Files.move(temporary, File(runtimeDir, FILE_NAME).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
