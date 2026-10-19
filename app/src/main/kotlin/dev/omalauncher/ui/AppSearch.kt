package dev.omalauncher.ui

import java.text.Normalizer

/*
 * Ranking for the bar: what's typed is either an app to open or a prompt for an agent.
 * Tiers are 100 apart so a usage boost (under 100) reorders within a tier, never across.
 */

private const val EXACT = 600
private const val PREFIX = 500
private const val WORD_PREFIX = 400
private const val INITIALS = 300
private const val SUBSTRING = 200
private const val SUBSEQUENCE = 100

private val WORD_BREAK = Regex("[^\\p{L}\\p{N}]+")

/** Combining marks left behind by NFD, so "Café" and "cafe" compare equal. */
private val MARKS = Regex("\\p{M}+")

/** Lowercase with accents stripped. */
internal fun fold(s: String) = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()

private fun words(s: String) = fold(s).split(WORD_BREAK).filter { it.isNotEmpty() }

/** How well app [name] matches [query]: 0 for no match, higher is better. */
internal fun matchScore(
    name: String,
    query: String,
): Int {
    val q = fold(query.trim())
    if (q.isEmpty()) return 0
    val n = fold(name)
    val nameWords = words(n)
    val queryWords = words(q)
    return when {
        n == q -> EXACT
        n.startsWith(q) -> PREFIX
        queryWords.isNotEmpty() && queryWords.all { w -> nameWords.any { it.startsWith(w) } } -> WORD_PREFIX
        queryWords.size == 1 && q.length >= 2 && nameWords.size > 1 && nameWords.joinToString("") { it.take(1) }.startsWith(q) -> INITIALS
        q in n -> SUBSTRING
        queryWords.size == 1 && isSubsequence(q, n) -> SUBSEQUENCE
        else -> 0
    }
}

private fun isSubsequence(
    needle: String,
    hay: String,
): Boolean {
    var i = 0
    for (c in hay) if (i < needle.length && c == needle[i]) i++
    return i == needle.length
}

/**
 * Whether Enter should open the top app rather than ask the agent: a short query
 * (two words at most, one line) that names the app well. Sentences go to the agent.
 */
internal fun isStrongMatch(
    score: Int,
    query: String,
) = score >= INITIALS && '\n' !in query && words(query).size <= 2

/**
 * [items] that match [query], best first; [boost] (0–99, e.g. from recent use) breaks ties within a tier.
 * An [alias] matches too, scored on its own, so it adds matches without demoting what [name] matches.
 */
internal fun <T> rankMatches(
    items: List<T>,
    query: String,
    name: (T) -> String,
    boost: (T) -> Int = { 0 },
    alias: (T) -> String? = { null },
): List<Pair<T, Int>> =
    items
        .map { it to maxOf(matchScore(name(it), query), alias(it)?.let { a -> matchScore(a, query) } ?: 0) }
        .filter { it.second > 0 }
        .sortedWith(
            compareByDescending<Pair<T, Int>> { it.second + boost(it.first).coerceIn(0, 99) }
                .thenBy { name(it.first).length }
                .thenBy { fold(name(it.first)) },
        )
