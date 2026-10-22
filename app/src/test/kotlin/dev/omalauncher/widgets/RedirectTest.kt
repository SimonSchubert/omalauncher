package dev.omalauncher.widgets

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URL

class RedirectTest {
    private val api = URL("https://api.test/v1/items")
    private val body = "{\"password\":\"hunter2\"}".toByteArray()

    @Test fun aPostAskedToLookElsewhereBecomesAGet() {
        for (code in listOf(301, 302, 303)) {
            val next = redirect(code, api, URL("https://other.test/done"), "POST", body)
            assertEquals("GET", next.method)
            assertNull(next.body)
        }
        assertEquals("GET", redirect(303, api, URL("https://other.test/"), "PUT", body).method)
    }

    @Test fun otherMethodsAndTheSameServerSendTheSameAgain() {
        // As browsers do: only a POST turns into a GET on a 301 or 302.
        assertEquals("PATCH", redirect(301, api, URL("https://api.test/v2/items"), "PATCH", body).method)
        for (code in listOf(307, 308)) {
            val next = redirect(code, api, URL("https://api.test/v2/items"), "POST", body)
            assertEquals("POST", next.method)
            assertArrayEquals(body, next.body)
        }
        assertEquals("DELETE", redirect(307, api, URL("https://cdn.test/x"), "DELETE", ByteArray(0)).method)
        assertEquals("GET", redirect(302, api, URL("https://cdn.test/x"), "GET", null).method)
    }

    @Test fun aBodyNeverGoesAgainToAnotherServer() {
        for (to in listOf("https://evil.test/v1/items", "https://api.test:8443/v1/items", "http://api.test/v1")) {
            assertThrows(to, IOException::class.java) { redirect(307, api, URL(to), "POST", body) }
        }
        val e = assertThrows(IOException::class.java) { redirect(308, api, URL("https://evil.test/"), "PUT", body) }
        assertTrue(e.message, e.message!!.contains("evil.test"))
    }

    @Test fun neverFromHttpsDownToHttp() {
        assertThrows(IOException::class.java) { redirect(302, api, URL("http://api.test/v1/items"), "GET", null) }
        assertEquals("http", redirect(302, URL("http://a.test/"), URL("http://b.test/"), "GET", null).url.protocol)
        assertEquals("https", redirect(302, URL("http://a.test/"), URL("https://a.test/"), "GET", null).url.protocol)
    }
}
