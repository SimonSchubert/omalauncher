package dev.omalauncher

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A connection to [url] with timeouts, not yet connected. The caller disconnects it. */
fun openHttp(
    url: String,
    connectTimeout: Int = 15_000,
    readTimeout: Int = 30_000,
): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).also {
        it.connectTimeout = connectTimeout
        it.readTimeout = readTimeout
    }

/** GETs [url] whole, bypassing any cache. Anything but a 200 is an [IOException]. Blocking. */
fun httpGet(
    url: String,
    connectTimeout: Int = 10_000,
    readTimeout: Int = 20_000,
): ByteArray {
    val c = openHttp(url, connectTimeout, readTimeout)
    c.useCaches = false
    try {
        if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for $url")
        return c.inputStream.use { it.readBytes() }
    } finally {
        c.disconnect()
    }
}
