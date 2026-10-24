package dev.omalauncher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.upgrade.Versioned
import org.json.JSONArray

/**
 * The apps pinned to the strip above the bar, for when it shows pinned apps instead of recent
 * ones: [AppUsage] keys, in the order they were added. Apps that aren't installed keep their pin,
 * so a Linux app or a reinstalled one comes back in its place.
 */
class StripPins(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("strip_pins", Context.MODE_PRIVATE)

    var keys by mutableStateOf(decode(prefs.getString(PINS, null)))
        private set

    /** Pins [key] at the strip's end. */
    fun add(key: String) = save(keys - key + key)

    fun remove(key: String) = save(keys - key)

    private fun save(pins: List<String>) {
        keys = pins
        prefs.edit().putString(PINS, encode(pins)).apply()
    }

    companion object {
        private const val PINS = "pins"
        private const val VERSION = 1

        fun encode(keys: List<String>): String = Versioned.write(VERSION, JSONArray(keys))

        fun decode(text: String?): List<String> {
            val array = Versioned.read(text, VERSION) as? JSONArray ?: return emptyList()
            return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }.distinct()
        }
    }
}

/**
 * Side in dp of each cell when [count] pinned apps share a strip [length] dp long: as big as
 * fits, between [min] and [max]. Past [min] they stop shrinking and the strip scrolls.
 */
fun stripCellSize(
    length: Float,
    count: Int,
    min: Float = 32f,
    max: Float = 56f,
): Float = if (count <= 0) max else (length / count).coerceIn(min, max)
