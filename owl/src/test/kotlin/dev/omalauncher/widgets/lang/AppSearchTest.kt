package dev.omalauncher.widgets.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSearchTest {
    private val apps = listOf("Firefox", "Visual Studio Code", "Files", "Claude Code", "Settings", "btop", "Calculator", "Calendar")

    private fun top(query: String) = rankMatches(apps, query, { it }).firstOrNull()

    private fun opensApp(query: String) = top(query)?.let { isStrongMatch(it.second, query) } == true

    @Test fun prefixOpensApp() {
        assertEquals("Firefox", top("fire")?.first)
        assertTrue(opensApp("fire"))
    }

    @Test fun initialsOpenApp() {
        assertEquals("Visual Studio Code", top("vsc")?.first)
        assertTrue(opensApp("vsc"))
    }

    @Test fun wordPrefixOpensApp() {
        assertEquals("Visual Studio Code", top("studio")?.first)
        assertTrue(opensApp("claude code"))
    }

    @Test fun sentencesGoToTheAgent() {
        assertFalse(opensApp("fix my wifi"))
        assertFalse(opensApp("why is the build failing"))
    }

    @Test fun weakMatchesShowButDontOpen() {
        // A subsequence still lists Firefox, but Enter asks the agent.
        assertEquals("Firefox", top("ffx")?.first)
        assertFalse(opensApp("ffx"))
    }

    @Test fun exactBeatsPrefixAndShorterWins() {
        assertEquals("Files", top("fi")?.first)
        assertEquals("Files", top("files")?.first)
        assertEquals("Calendar", rankMatches(apps + "Cal", "calendar", { it }).first().first)
    }

    @Test fun boostReordersWithinATier() {
        val ranked = rankMatches(apps, "cal", { it }, boost = { if (it == "Calendar") 50 else 0 })
        assertEquals("Calendar", ranked.first().first)
    }

    @Test fun boostNeverCrossesTiers() {
        // "co" starts a word of both Code apps but is only a subsequence of Calculator.
        val ranked = rankMatches(apps, "co", { it }, boost = { if (it == "Calculator") 99 else 0 })
        assertEquals("Calculator", ranked.last().first)
    }

    @Test fun aliasFindsWorkCopy() {
        // The same app in the personal and the work profile; only the work one has the alias.
        val copies = listOf("WhatsApp" to null, "WhatsApp" to "WhatsApp work")
        val ranked = { q: String, boost: (Pair<String, String?>) -> Int -> rankMatches(copies, q, { it.first }, boost, alias = { it.second }) }
        assertEquals(2, ranked("whatsapp") { 0 }.size)
        // Both match the plain name equally, so recent use decides which Enter opens.
        assertEquals("WhatsApp work", ranked("whatsapp") { if (it.second != null) 50 else 0 }.first().first.second)
        assertEquals(listOf("WhatsApp work"), ranked("work") { 0 }.map { it.first.second })
        assertEquals(listOf("WhatsApp work"), ranked("whatsapp work") { 0 }.map { it.first.second })
    }

    @Test fun noMatchIsEmpty() {
        assertTrue(rankMatches(apps, "zzz", { it }).isEmpty())
        assertTrue(rankMatches(apps, "  ", { it }).isEmpty())
    }

    @Test fun accentsMatchWithoutThem() {
        assertEquals("cafe", foldAccents("Café"))
        val named = listOf("Café", "Calendar")
        assertEquals("Café", rankMatches(named, "cafe", { it }).first().first)
        assertEquals("Café", rankMatches(named, "Café", { it }).first().first)
        assertEquals("Café Deluxe", rankMatches(listOf("Café Deluxe"), "cafe", { it }).first().first)
        // "cal" is still Calendar: folding does not invent letters.
        assertEquals("Calendar", rankMatches(named, "cal", { it }).first().first)
    }

    @Test fun foldingTwiceChangesNothing() {
        // Lowercasing İ adds a dot above, which folding strips too.
        assertEquals("istanbul", foldAccents("İstanbul"))
        assertEquals(foldAccents("Café Deluxe"), foldAccents(foldAccents("Café Deluxe")))
        assertEquals("İstanbul", rankMatches(listOf("Istria", "İstanbul"), "istanbul", { it }).first().first)
    }

    @Test fun wordsBreakAtAnythingButLettersAndNumbers() {
        // Punctuation, spaces and symbols between words, however many; numbers and other scripts are words too.
        val named = listOf("pocket-blocks 2", "Mail (Work)", "東京 地図", "Ⅻ Clock", "Notes²")
        assertEquals("pocket-blocks 2", rankMatches(named, "blocks 2", { it }).first().first)
        assertEquals("Mail (Work)", rankMatches(named, "work", { it }).first().first)
        assertEquals("東京 地図", rankMatches(named, "地図", { it }).first().first)
        assertEquals("Ⅻ Clock", rankMatches(named, "clock", { it }).first().first)
        assertEquals(400, matchScore("Mail (Work)", "work"))
        assertEquals(400, matchScore("Notes² beta", "beta"))
        // A letter outside the BMP is a letter, not a break.
        assertEquals(400, matchScore("𝒜pp Store", "store"))
    }
}
