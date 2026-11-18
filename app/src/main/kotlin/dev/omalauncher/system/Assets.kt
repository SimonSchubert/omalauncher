package dev.omalauncher.system

import android.content.Context

/** The text of the bundled file [name] in the app's assets. Blocking; throws when there is none. */
fun Context.assetText(name: String): String = assets.open(name).bufferedReader().use { it.readText() }
