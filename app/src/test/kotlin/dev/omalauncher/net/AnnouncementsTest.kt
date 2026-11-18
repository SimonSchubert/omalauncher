package dev.omalauncher.net

import dev.omalauncher.net.Announcements.Companion.parse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

class AnnouncementsTest {
    private val now = Instant.parse("2026-12-01T12:00:00Z")

    private fun one(
        message: String,
        versionCode: Int = 38,
        flavor: String = "play",
        locale: Locale = Locale.ENGLISH,
    ) = parse("""{"version":1,"messages":[$message]}""", versionCode, flavor, now, locale)

    @Test fun contest() {
        val m =
            one(
                """{
                  "id": "contest", "end": "2026-12-24T23:59:59Z",
                  "title": {"en": "full-screen widget contest", "de": "wettbewerb"},
                  "body": {"en": "Build one."},
                  "countdown": {"label": {"en": "entries close in"}},
                  "buttons": [
                    {"label": {"en": "reply on X"}, "url": "https://x.com/intent/post?in_reply_to=1"},
                    {"label": {"en": "rules"}, "url": "https://github.com/x"}
                  ]
                }""",
            ).single()
        assertEquals("contest", m.id)
        assertEquals("full-screen widget contest", m.title)
        assertEquals("Build one.", m.body)
        assertEquals(listOf("reply on X", "rules"), m.buttons.map { it.label })
        assertEquals(Instant.parse("2026-12-24T23:59:59Z"), m.countdown!!.to)
        assertEquals("entries close in", m.countdown!!.label)
    }

    @Test fun languages() {
        val texts = """"title": {"en": "contest", "de": "wettbewerb", "id": "kontes"}"""
        assertEquals("wettbewerb", one("""{"id":"a",$texts}""", locale = Locale.GERMANY).single().title)
        // Android gives Indonesian as "in" from Locale.language; the file says "id".
        assertEquals("kontes", one("""{"id":"a",$texts}""", locale = Locale.forLanguageTag("id-ID")).single().title)
        assertEquals("kontes", one("""{"id":"a",$texts}""", locale = Locale("in", "ID")).single().title)
        // No Spanish: English.
        assertEquals("contest", one("""{"id":"a",$texts}""", locale = Locale.forLanguageTag("es-ES")).single().title)
    }

    @Test fun whoAndWhen() {
        assertTrue(one("""{"id":"a","title":{"en":"t"},"active":false}""").isEmpty())
        assertTrue(one("""{"id":"a","title":{"en":"t"},"start":"2026-12-02T00:00:00Z"}""").isEmpty())
        assertTrue(one("""{"id":"a","title":{"en":"t"},"end":"2026-11-30T00:00:00Z"}""").isEmpty())
        assertTrue(one("""{"id":"a","title":{"en":"t"},"minVersionCode":39}""").isEmpty())
        assertTrue(one("""{"id":"a","title":{"en":"t"},"maxVersionCode":37}""").isEmpty())
        assertTrue(one("""{"id":"a","title":{"en":"t"},"flavors":["foss"]}""").isEmpty())
        assertEquals(1, one("""{"id":"a","title":{"en":"t"},"flavors":["foss","play"],"minVersionCode":38,"maxVersionCode":38}""").size)
    }

    @Test fun skipsWhatItCantRead() {
        // No id, no title, a title in another language only.
        assertTrue(one("""{"title":{"en":"t"}}""").isEmpty())
        assertTrue(one("""{"id":"a"}""").isEmpty())
        assertTrue(one("""{"id":"a","title":{"de":"t"}}""").isEmpty())
        // Buttons: not https, an action from a newer launcher, no label; at most three.
        val buttons =
            one(
                """{"id":"a","title":{"en":"t"},"buttons":[
                  {"label":{"en":"plain"},"url":"http://x.com"},
                  {"label":{"en":"newer"},"url":"https://x.com","action":"share"},
                  {"url":"https://x.com"},
                  {"label":{"en":"1"},"url":"https://x.com/1"},
                  {"label":{"en":"2"},"url":"https://x.com/2"},
                  {"label":{"en":"3"},"url":"https://x.com/3"},
                  {"label":{"en":"4"},"url":"https://x.com/4"}
                ]}""",
            ).single().buttons
        assertEquals(listOf("1", "2", "3"), buttons.map { it.label })
        // A countdown with nothing to count to.
        assertNull(one("""{"id":"a","title":{"en":"t"},"countdown":{}}""").single().countdown)
    }

    @Test fun oneBadMessageLeavesTheOthers() {
        // A date without its Z can't be read: that message goes, the next one stays.
        val list = parse("""{"messages":[{"id":"a","title":{"en":"t"},"end":"2026-12-24"},{"id":"b","title":{"en":"t"}},"junk",{"id":"c","title":{"en":"t"},"countdown":{"to":"soon"}}]}""", 38, "play", now, Locale.ENGLISH)
        assertEquals(listOf("b"), list.map { it.id })
    }

    @Test fun notTheFileItShouldBe() {
        for (json in listOf("", "<html>404</html>", "[]", "{}", """{"messages":"none"}""", """{"messages":[1,2]}""")) {
            assertTrue(json, parse(json, 38, "play", now, Locale.ENGLISH).isEmpty())
        }
    }

    @Test fun futureFields() {
        val m = one("""{"id":"a","title":{"en":"t"},"image":"https://x/i.webp","priority":3,"countdown":{"to":"2026-12-24T23:59:59Z","style":"big"}}""").single()
        assertEquals("t", m.title)
    }
}
