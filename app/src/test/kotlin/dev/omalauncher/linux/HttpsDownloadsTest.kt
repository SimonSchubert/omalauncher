package dev.omalauncher.linux

import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.net.HttpURLConnection
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class HttpsDownloadsTest {
    @Test fun rejectsPlaintextBeforeConnecting() {
        assertThrows(Exception::class.java) { openHttps(URL("http://127.0.0.1:9/rootfs")) }
    }
    @Test fun rejectsEmbeddedCredentialsBeforeConnecting() {
        assertThrows(Exception::class.java) { openHttps(URL("https://user:password@127.0.0.1:9/rootfs")) }
    }
    @Test fun rejectsRedirectFromHttpsToHttpAndClosesConnection() {
        var closed = false
        val handler = object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = object : HttpURLConnection(url) {
                override fun getResponseCode() = 302
                override fun getHeaderField(name: String?) = if (name == "Location") "http://attacker.example/image" else null
                override fun connect() = Unit
                override fun disconnect() { closed = true }
                override fun usingProxy() = false
            }
        }
        assertThrows(Exception::class.java) { openHttps(URL(null, "https://trusted.example/image", handler)) }
        assertTrue(closed)
    }
    @Test fun stripsCredentialsWhenFollowingAnotherHttpsHost() {
        val requests = mutableListOf<Map<String, List<String>>>()
        val handler = object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = object : HttpURLConnection(url) {
                override fun getResponseCode(): Int {
                    requests += requestProperties
                    connected = true
                    return if (url.host == "trusted.example") 302 else 200
                }
                override fun getHeaderField(name: String?) = if (name == "Location") "https://cdn.example/image" else null
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
            }
        }
        openHttps(URL(null, "https://trusted.example/image", handler), mapOf("Authorization" to "secret", "Cookie" to "session", "X-API-Key" to "api-secret", "Accept" to "application/octet-stream")).disconnect()
        assertEquals(listOf("secret"), requests[0]["Authorization"])
        assertNull(requests[1]["Authorization"])
        assertNull(requests[1]["Cookie"])
        assertNull(requests[1]["X-API-Key"])
        assertEquals(listOf("application/octet-stream"), requests[1]["Accept"])
    }
    @Test fun resumeHeadersAreSetBeforeConnectingAndSurviveHttpsRedirects() {
        val requests = mutableListOf<Map<String, List<String>>>()
        val handler = object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = object : HttpURLConnection(url) {
                override fun getResponseCode(): Int {
                    requests += requestProperties
                    connected = true // setting headers after this must fail
                    return if (url.path == "/image") 302 else HTTP_PARTIAL
                }
                override fun getHeaderField(name: String?) = if (name == "Location") "/archive" else null
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
            }
        }
        val connection = openHttps(URL(null, "https://trusted.example/image", handler), mapOf("Range" to "bytes=123-", "If-Range" to "version-one"))
        connection.disconnect()
        assertEquals(2, requests.size)
        requests.forEach {
            assertEquals(listOf("bytes=123-"), it["Range"])
            assertEquals(listOf("version-one"), it["If-Range"])
        }
    }

}
