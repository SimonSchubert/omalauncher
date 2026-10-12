package dev.omalauncher

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** GETs [url] whole, bypassing any cache. Anything but a 200 is an [IOException]. Blocking. */
fun httpGet(
    url: String,
    connectTimeout: Int = 10_000,
    readTimeout: Int = 20_000,
): ByteArray {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = connectTimeout
    c.readTimeout = readTimeout
    c.useCaches = false
    try {
        if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for $url")
        return c.inputStream.use { it.readBytes() }
    } finally {
        c.disconnect()
    }
}
