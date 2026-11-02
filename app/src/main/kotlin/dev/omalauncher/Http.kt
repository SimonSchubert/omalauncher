package dev.omalauncher

import dev.omalauncher.widgets.lang.Pins
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

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

/**
 * Trusts only the certificate whose SHA-256 is [pin] (normalized, see [Pins]), whoever signed it and
 * whatever host it names, so a server on the LAN with a certificate of its own, at any of its
 * addresses, is reachable and nothing else answering there is. A different certificate fails the
 * handshake with a message that says the SHA-256 it had.
 */
fun HttpsURLConnection.pin(pin: String) {
    sslSocketFactory = pinnedSocketFactory(pin)
    // The handshake checked the certificate; its names don't matter.
    hostnameVerifier = HostnameVerifier { _, _ -> true }
}

/** One factory per pin: the same one each time lets Android reuse a pinned connection. */
private val pinnedFactories = ConcurrentHashMap<String, SSLSocketFactory>()

/** Sockets that trust only the certificate [pin] names, as [pin] describes; they check no host names. */
fun pinnedSocketFactory(pin: String): SSLSocketFactory = pinnedFactories.getOrPut(pin) { pinnedFactory(pin) }

private fun pinnedFactory(pin: String): SSLSocketFactory {
    val trust =
        object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<out X509Certificate>,
                authType: String,
            ) = throw CertificateException("no client certificates")

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>,
                authType: String,
            ) {
                val had = Pins.of((chain.firstOrNull() ?: throw CertificateException("no certificate")).encoded)
                if (had != pin) throw CertificateException("the server's certificate isn't the pinned one; its SHA-256 is $had")
            }

            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        }
    return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }.socketFactory
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
