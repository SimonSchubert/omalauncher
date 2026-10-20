package dev.omalauncher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.pow

/**
 * How often and how lately apps were opened, for the recent apps strip and to rank
 * search results. Launches count less as they age: half every two weeks.
 */
class AppUsage(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("app_usage", Context.MODE_PRIVATE)

    /** Key -> (decayed score, millis it was last updated). */
    private val scores = mutableStateMapOf<String, Pair<Double, Long>>()

    init {
        for ((key, value) in prefs.all) {
            val (score, at) = (value as? String)?.split(',') ?: continue
            scores[key] = (score.toDoubleOrNull() ?: continue) to (at.toLongOrNull() ?: continue)
        }
    }

    /**
     * Keys of the apps opened, last opened first. Only updated by [settle], so the strip
     * doesn't reorder under the finger that just tapped it.
     */
    var recent by mutableStateOf(order())
        private set

    private fun order() = scores.entries.sortedByDescending { it.value.second }.map { it.key }

    /** Counts [settle] calls, so the strip can scroll back to its start each time home comes back. */
    var settled by mutableIntStateOf(0)
        private set

    /** Moves the latest launches to the front of [recent]; called once the home screen is out of sight. */
    fun settle() {
        recent = order()
        settled++
    }

    fun record(key: String) {
        val now = System.currentTimeMillis()
        val score = score(key, now) + 1
        scores[key] = score to now
        prefs.edit().putString(key, "$score,$now").apply()
    }

    fun score(
        key: String,
        now: Long = System.currentTimeMillis(),
    ): Double {
        val (score, at) = scores[key] ?: return 0.0
        return score * 0.5.pow((now - at).coerceAtLeast(0) / HALF_LIFE)
    }

    /** When [key] was last opened, in millis; null if it never was. */
    fun last(key: String): Long? = scores[key]?.second

    private companion object {
        const val HALF_LIFE = 14 * 24 * 60 * 60 * 1000.0
    }
}
