package dev.omalauncher.theme

import dev.omalauncher.upgrade.Versioned
import org.json.JSONObject

/** Remember each image's framing; style.toml remains the record of the currently displayed image. */
class WallpaperProfiles private constructor(private val entries: MutableMap<String, WallpaperFraming>) {
    constructor() : this(linkedMapOf())

    fun remember(key: String, framing: WallpaperFraming) { entries[key] = framing }

    fun choose(from: String?, to: String, previous: WallpaperFraming, proposed: WallpaperFraming): WallpaperFraming {
        if (from != null) remember(from, previous)
        val selected = if (from != to && proposed == previous) entries[to] ?: WallpaperFraming() else proposed
        remember(to, selected)
        return selected
    }

    fun data(): JSONObject = JSONObject().also { root ->
        for ((key, f) in entries) root.put(key, JSONObject().put("scroll", f.scroll).put("x", f.x.toDouble()).put("y", f.y.toDouble()).put("zoom", f.zoom.toDouble()).put("fit", f.fit))
    }

    companion object {
        const val VERSION = 1

        /** Skip a damaged entry without losing the other images. */
        fun read(text: String?): WallpaperProfiles {
            val entries = linkedMapOf<String, WallpaperFraming>()
            val root = Versioned.read(text, VERSION) as? JSONObject ?: return WallpaperProfiles(entries)
            for (key in root.keys()) runCatching {
                val v = root.getJSONObject(key)
                val x = v.getDouble("x")
                val y = v.getDouble("y")
                val zoom = v.getDouble("zoom")
                require(x in 0.0..1.0 && y in 0.0..1.0 && zoom in 1.0..3.0)
                entries[key] = WallpaperFraming(v.getBoolean("scroll"), x.toFloat(), y.toFloat(), zoom.toFloat(), v.optBoolean("fit", false))
            }
            return WallpaperProfiles(entries)
        }
    }
}
