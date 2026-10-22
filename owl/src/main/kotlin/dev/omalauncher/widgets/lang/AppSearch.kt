package dev.omalauncher.widgets.lang

import java.text.Normalizer

/*
 * Ranking for the bar, and for owl's rank() and strongMatch(): what's typed is either an app to open or a prompt for an agent.
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

/** Lowercase with accents stripped; lowercased first, so a mark lowercasing adds (İ's dot) goes too, and folding twice changes nothing. */
fun foldAccents(s: String): String {
    // Most names are ASCII, which NFD leaves as is: skip the normalizing, which ran for every app on every keystroke.
    if (s.all { it < '\u0080' }) return s.lowercase()
    return MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")
}

/** The words of a string that's folded already. */
private fun split(folded: String) = folded.split(WORD_BREAK).filter { it.isNotEmpty() }

private fun words(s: String) = split(foldAccents(s))

/** A query folded once, to score many names with. */
private class Query(
    raw: String,
) {
    val text = foldAccents(raw.trim())
    val words = split(text)
}

/** How well app [name] matches [query]: 0 for no match, higher is better. */
fun matchScore(
    name: String,
    query: String,
): Int = score(foldAccents(name), Query(query))

/** [matchScore] for a name [n] that's folded already. */
private fun score(
    n: String,
    query: Query,
): Int {
    val q = query.text
    if (q.isEmpty()) return 0
    if (n == q) return EXACT
    if (n.startsWith(q)) return PREFIX
    val queryWords = query.words
    val nameWords = split(n)
    return when {
        queryWords.isNotEmpty() && queryWords.all { w -> nameWords.any { it.startsWith(w) } } -> WORD_PREFIX
        queryWords.size == 1 && q.length >= 2 && nameWords.size > 1 && initialsStart(nameWords, q) -> INITIALS
        q in n -> SUBSTRING
        queryWords.size == 1 && isSubsequence(q, n) -> SUBSEQUENCE
        else -> 0
    }
}

/** Whether the first letters of [nameWords] start with [q]. */
private fun initialsStart(
    nameWords: List<String>,
    q: String,
): Boolean {
    if (q.length > nameWords.size) return false
    for (i in q.indices) if (nameWords[i][0] != q[i]) return false
    return true
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
fun isStrongMatch(
    score: Int,
    query: String,
) = score >= INITIALS && '\n' !in query && words(query).size <= 2

/**
 * [items] that match [query], best first; [boost] (0–99, e.g. from recent use) breaks ties within a tier.
 * An [alias] matches too, scored on its own, so it adds matches without demoting what [name] matches.
 */
fun <T> rankMatches(
    items: List<T>,
    query: String,
    name: (T) -> String,
    boost: (T) -> Int = { 0 },
    alias: (T) -> String? = { null },
): List<Pair<T, Int>> {
    val q = Query(query)
    if (q.text.isEmpty()) return emptyList()
    // Each item's name is read and folded once, not again for every comparison the sort makes.
    val hits = ArrayList<Ranked<T>>()
    for (item in items) {
        val n = name(item)
        val folded = foldAccents(n)
        val s = maxOf(score(folded, q), alias(item)?.let { score(foldAccents(it), q) } ?: 0)
        if (s > 0) hits += Ranked(item, s, s + boost(item).coerceIn(0, 99), n.length, folded)
    }
    hits.sortWith(compareByDescending<Ranked<T>> { it.order }.thenBy { it.length }.thenBy { it.folded })
    return hits.map { it.item to it.score }
}

private class Ranked<T>(
    val item: T,
    val score: Int,
    /** The score with the boost: what orders them. */
    val order: Int,
    val length: Int,
    val folded: String,
)
