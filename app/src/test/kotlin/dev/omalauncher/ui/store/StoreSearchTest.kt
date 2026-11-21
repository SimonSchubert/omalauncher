package dev.omalauncher.ui.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreSearchTest {
    @Test fun spaceFindsHyphenatedName() = assertTrue(searchMatches("now playing", "now-playing"))

    @Test fun hyphenFindsSpacedText() = assertTrue(searchMatches("now-playing", "What's now playing"))

    @Test fun underscoreReadsAsSpace() = assertTrue(searchMatches("sky forge", "sky_forge"))

    @Test fun ignoresCase() = assertTrue(searchMatches("now playing", "Now-Playing"))

    @Test fun stillNeedsTheWordsInOrder() = assertFalse(searchMatches("playing now", "now-playing"))
}
