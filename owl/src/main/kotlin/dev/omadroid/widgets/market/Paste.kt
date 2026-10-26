package dev.omadroid.widgets.market

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/**
 * A widget too big for one QR code, as plain text on dpaste.com (around since 2006, no account needed),
 * so the code only has to hold `…/w#dpaste.<id>`. Anyone with the link can read it, and it's gone after
 * [DAYS] days. dpaste asks clients to name themselves in the User-Agent and stay under a request a second.
 */
object Paste {
    const val HOST = "dpaste.com"
    const val DAYS = 7

    /** For a store submission ([Submit]), which waits for a review; the most dpaste allows. */
    const val STORE_DAYS = 365

    /** Uploads [text] and returns its id. Throws [ShareError]. Blocking. */
    fun upload(
        text: String,
        userAgent: String,
        days: Int = DAYS,
    ): String {
        val body = "content=${URLEncoder.encode(text, "UTF-8")}&expiry_days=$days".toByteArray()
        val c = open("https://$HOST/api/v2/", userAgent)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body) }
            if (c.responseCode != HttpURLConnection.HTTP_CREATED) throw ShareError("$HOST said ${c.responseCode}")
            // The body is the new paste's URL: https://dpaste.com/<id>
            val url = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8).trim()
            return url.substringAfterLast('/').takeIf { ID.matches(it) } ?: throw ShareError("$HOST sent something unexpected")
        } catch (e: IOException) {
            throw ShareError("can't reach $HOST")
        } finally {
            c.disconnect()
        }
    }

    /** The widget behind [id]. Throws [ShareError], also for more than [Review.MAX_BYTES]. Blocking. */
    fun download(
        id: String,
        userAgent: String,
    ): String {
        require(ID.matches(id))
        val c = open("https://$HOST/$id.txt", userAgent)
        try {
            when (c.responseCode) {
                HttpURLConnection.HTTP_OK -> {}
                HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_GONE -> throw ShareError("it expired or was deleted from $HOST")
                else -> throw ShareError("$HOST said ${c.responseCode}")
            }
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > Review.MAX_BYTES) throw ShareError("the widget is over ${Review.MAX_BYTES / 1024} KB")
                }
            }
            return try {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(out.toByteArray())).toString()
            } catch (e: CharacterCodingException) {
                throw ShareError("the widget isn't text")
            }
        } catch (e: IOException) {
            throw ShareError("can't reach $HOST")
        } finally {
            c.disconnect()
        }
    }

    val ID = Regex("[A-Za-z0-9]{4,16}")

    private fun open(
        url: String,
        userAgent: String,
    ) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 15_000
        instanceFollowRedirects = false
        setRequestProperty("User-Agent", userAgent)
    }
}
