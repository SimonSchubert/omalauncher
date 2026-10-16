package dev.omalauncher.linux

import java.net.HttpURLConnection
import java.net.URL

/** Explicit redirects: HTTPS connections must never silently downgrade to HTTP. */
internal fun openHttps(url: URL, headers: Map<String, String> = emptyMap(), connectTimeoutMillis: Int = 15_000, readTimeoutMillis: Int = 30_000): HttpURLConnection {
    var next = url
    var requestHeaders = headers
    repeat(6) {
        check(next.protocol == "https" && next.userInfo == null) { "An HTTPS download is required" }
        val c = next.openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = connectTimeoutMillis
        c.readTimeout = readTimeoutMillis
        requestHeaders.forEach { (name, value) -> c.setRequestProperty(name, value) }
        try {
            if (c.responseCode !in setOf(301, 302, 303, 307, 308)) return c
            val destination = URL(next, c.getHeaderField("Location") ?: error("Download redirect has no destination"))
            if (destination.host != next.host || destination.port != next.port) {
                // Custom authentication headers (e.g. X-API-Key) are secrets too.
                requestHeaders = requestHeaders.filterKeys { it.lowercase() in setOf("accept", "accept-language", "user-agent", "range", "if-range") }
            }
            next = destination
        } catch (e: Exception) {
            c.disconnect()
            throw e
        }
        c.disconnect()
    }
    error("Too many download redirects")
}

internal fun readHttps(url: URL, maxBytes: Int): ByteArray {
    val c = openHttps(url)
    try {
        check(c.responseCode == 200) { "Download failed: HTTP ${c.responseCode}" }
        return c.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
                check(out.size() <= maxBytes) { "Download is too large" }
            }
            out.toByteArray()
        }
    } finally {
        c.disconnect()
    }
}
