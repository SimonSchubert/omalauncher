package dev.omalauncher.widgets.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import dev.omalauncher.widgets.lang.OwlKind
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class SubmitTest {
    @Test fun theSlugIsAStoreFolder() {
        assertEquals("disk-free-n-code", Submit.slug("Disk Free — ünïcode!"))
        assertEquals("widget", Submit.slug("!!!"))
        assertTrue(Submit.slug("x".repeat(30) + " " + "y".repeat(30)).length <= 39)
    }

    @Test fun theIssueLinksToThePaste() {
        val url = Submit.issueUrl("o/r", "a + b", "100% & more?", "AB12CD")
        assertTrue(url.startsWith("https://github.com/o/r/issues/new?title="))
        val query = URI(url).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("Add widget: a + b", query["title"])
        val body = query["body"]!!
        assertTrue(body.contains("> 100% & more?"))
        assertTrue(body.contains("https://${Paste.HOST}/AB12CD.txt"))
        assertEquals("AB12CD", Share.pasteId(body.lines().first { it.startsWith("- open") }))
        assertFalse(url.contains(' '))
        assertFalse(url.contains('+'))
    }

    @Test fun anAppAsksToAddAnApp() {
        val url = Submit.issueUrl("o/r", "notes", "", "AB12CD", OwlKind.App)
        val query = URI(url).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("Add app: notes", query["title"])
        assertTrue(query["body"]!!.startsWith("Please add my app **notes**"))
    }
}
