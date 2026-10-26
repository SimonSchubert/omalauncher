package dev.omadroid.linux

import android.content.Context
import dev.omadroid.upgrade.Managed
import dev.omadroid.upgrade.Policy
import java.io.File

/**
 * Icon name of a launcher-provided icon (assets/icons/<name>.png, rendered from
 * art/icons by scripts/make-icons.sh). Prefixed so a package's own icon of the
 * same name, which the drawer would find first, can't shadow it.
 */
fun bundledIcon(name: String) = "omalauncher-$name"

/** The launcher's icons in the guest's hicolor theme, where the drawer looks up Icon= names. */
fun bundledIcons(
    context: Context,
    home: File,
): List<Managed> {
    val dir = File(home, ".local/share/icons/hicolor/256x256/apps")
    return context.assets.list("icons").orEmpty().filter { it.endsWith(".png") }.map { asset ->
        Managed(File(dir, "${bundledIcon(asset.removeSuffix(".png"))}.png"), Policy.Owned) {
            context.assets.open("icons/$asset").use { it.readBytes() }
        }
    }
}
