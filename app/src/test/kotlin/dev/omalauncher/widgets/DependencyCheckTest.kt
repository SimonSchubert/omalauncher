package dev.omalauncher.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyCheckTest {
    @Test fun quotesNamesAndReadsBackWhatsMissing() {
        val script = DependencyCheck.script(listOf("jq", "it's"), listOf("curl"))
        assertTrue(script.startsWith("pacman -T 'jq' 'it'\\''s' | sed 's/^/pkg /'; "))
        assertTrue(script.contains("for c in 'curl'; do"))
        assertEquals("true", DependencyCheck.script(emptyList(), emptyList()))
        val missing = DependencyCheck.parse("pkg jq\n  bin curl \nsomething else\n")
        assertEquals(setOf("jq"), missing.packages)
        assertEquals(setOf("curl"), missing.programs)
    }
}
