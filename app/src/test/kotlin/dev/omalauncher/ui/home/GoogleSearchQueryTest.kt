package dev.omalauncher.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleSearchQueryTest {
    @Test fun prefixSendsOnlyQuery() {
        assertEquals("cats and dogs", googleSearchQuery("g cats and dogs"))
        assertEquals("cats", googleSearchQuery("G   cats  "))
        assertEquals("café ☕", googleSearchQuery("g café ☕"))
    }

    @Test fun appNamesAndEmptySearchesAreNotIntercepted() {
        listOf("g", "g ", "g    ", "google", "gmail", "go cats", " g cats", "g\tcats").forEach {
            assertNull(it, googleSearchQuery(it))
        }
    }
}
