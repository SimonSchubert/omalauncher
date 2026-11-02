package dev.omalauncher

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * A WebSocket client (RFC 6455): text and binary messages, without extensions, proxies or redirects, and
 * with a subprotocol only when a Sec-WebSocket-Protocol header offers one. [connect] does the handshake;
 * [listen] then reads on the caller's thread until the connection ends, while [send], [close] and [cancel]
 * may be called from any thread, Android's main thread too: they return at once and leave the network to
 * threads of their own. scripts/autobahn.sh runs it through the Autobahn TestSuite.
 */
class WebSocket private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    private val maxMessage: Int,
) {
    /** How a connection ended with a close handshake: the server's code and reason. */
    data class Closed(
        val code: Int,
        val reason: String,
    )

    /** The server broke the protocol, and the connection was closed with [code]. */
    class ProtocolException(
        val code: Int,
        message: String,
    ) : IOException(message)

    private val random = SecureRandom()

    /** Guards writes and [closeSent]: a frame goes out whole, and nothing follows a close frame. */
    private val lock = Any()

    @Volatile private var closeSent = false

    /** A ping is out and nothing has arrived since. */
    private var pingSent = false

    /** Writes [send]'s messages and the close frames, in order, on a thread that goes when it's idle. */
    private val writer =
        ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue()) { r -> Thread(r, "websocket-send").apply { isDaemon = true } }
            .apply { allowCoreThreadTimeOut(true) }

    /**
     * Reads messages until the connection ends, handing each to [onText] or [onBinary] on this thread, and
     * answers pings. Returns how the server closed it; throws an [IOException] when the connection broke
     * (a [ProtocolException] when the server broke the protocol), or went quiet and didn't answer a ping.
     */
    fun listen(
        onText: (String) -> Unit,
        onBinary: (ByteArray) -> Unit = {},
    ): Closed {
        try {
            val head = ByteArray(8)
            var message: ByteArrayOutputStream? = null
            var text = false
            val utf8 = Utf8()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                readFully(head, 2)
                val b0 = head[0].toInt()
                val b1 = head[1].toInt()
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0f
                if (b0 and 0x70 != 0) fail(1002, "reserved bits set")
                if (b1 and 0x80 != 0) fail(1002, "a masked frame from the server")
                var length = (b1 and 0x7f).toLong()
                if (length == 126L) {
                    readFully(head, 2)
                    length = number(head, 2)
                } else if (length == 127L) {
                    readFully(head, 8)
                    length = number(head, 8)
                    if (length < 0) fail(1002, "a frame over 2^63 bytes")
                }
                when (opcode) {
                    CLOSE, PING, PONG -> {
                        if (!fin) fail(1002, "a fragmented control frame")
                        if (length > 125) fail(1002, "a control frame over 125 bytes")
                        val payload = ByteArray(length.toInt())
                        readFully(payload, payload.size)
                        when (opcode) {
                            PING -> write(PONG, payload)
                            CLOSE -> return closed(payload)
                        }
                    }
                    CONTINUATION, TEXT, BINARY -> {
                        if (opcode == CONTINUATION && message == null) fail(1002, "a continuation without a message")
                        if (opcode != CONTINUATION && message != null) fail(1002, "a message before the last one ended")
                        if (message == null) {
                            message = ByteArrayOutputStream()
                            text = opcode == TEXT
                            utf8.reset()
                        }
                        if (message.size() + length > maxMessage) fail(1009, "a message over $maxMessage bytes")
                        // In chunks as they arrive, so text that isn't UTF-8 fails before the rest of it comes.
                        var left = length.toInt()
                        while (left > 0) {
                            val n = read(chunk, minOf(left, chunk.size))
                            if (text && !utf8.feed(chunk, n)) fail(1007, "text that isn't UTF-8")
                            message.write(chunk, 0, n)
                            left -= n
                        }
                        if (fin) {
                            if (text && !utf8.complete) fail(1007, "text that isn't UTF-8")
                            val bytes = message.toByteArray()
                            message = null
                            // After our close frame, the server's last messages are dropped.
                            if (!closeSent) if (text) onText(String(bytes, Charsets.UTF_8)) else onBinary(bytes)
                        }
                    }
                    else -> fail(1002, "an unknown opcode $opcode")
                }
            }
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    /** No close frame went out yet and the connection isn't broken. */
    val isOpen get() = !closeSent && !socket.isClosed

    /** Queues a text message; false once the connection is closing or broken. */
    fun send(text: String) = queue(TEXT, text.toByteArray(Charsets.UTF_8))

    /** Queues a binary message; false once the connection is closing or broken. */
    fun send(bytes: ByteArray) = queue(BINARY, bytes)

    /**
     * Starts the close handshake with [code] and [reason] (at most 123 bytes of it); [listen] returns when
     * the server answers. A server that doesn't, or a write stuck on a dead connection, is cut off after
     * [CLOSE_WAIT_MS]. The frame follows the messages queued before it.
     */
    fun close(
        code: Int = 1000,
        reason: String = "",
    ) {
        scheduler.schedule({ cancel() }, CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
        queue(CLOSE, closePayload(code, reason))
    }

    /** Drops the connection without a close handshake; [listen] throws. */
    fun cancel() {
        thread(name = "websocket-close", isDaemon = true) { socket.close() }
    }

    /** Answers the server's close frame, waits for it to end the connection, and says how it closed. */
    private fun closed(payload: ByteArray): Closed {
        val code =
            when (payload.size) {
                0 -> 1005
                1 -> fail(1002, "a close frame with a 1-byte code")
                else -> number(payload, 2).toInt()
            }
        if (payload.size >= 2 && !validCloseCode(code)) fail(1002, "the close code $code")
        val reason = payload.copyOfRange(minOf(2, payload.size), payload.size)
        val utf8 = Utf8()
        if (!utf8.feed(reason, reason.size) || !utf8.complete) fail(1007, "a close reason that isn't UTF-8")
        // Queued, so it follows what we're still sending, like echoes of the server's last messages.
        queue(CLOSE, if (code == 1005) ByteArray(0) else payload.copyOf(2))
        // The server ends the TCP connection (RFC 6455 7.1.1); give it a moment, then end it ourselves.
        try {
            socket.soTimeout = CLOSE_WAIT_MS.toInt()
            val skip = ByteArray(1024)
            while (input.read(skip) >= 0) Unit
        } catch (_: IOException) {
        }
        socket.close()
        return Closed(code, String(reason, Charsets.UTF_8))
    }

    /** Closes the connection with [code], saying why, and throws a [ProtocolException]. */
    private fun fail(
        code: Int,
        why: String,
    ): Nothing {
        write(CLOSE, closePayload(code, why))
        socket.close()
        throw ProtocolException(code, "the server sent $why")
    }

    /** Hands a frame to [writer]; false if the connection is closing or broken. */
    private fun queue(
        opcode: Int,
        payload: ByteArray,
    ): Boolean {
        if (!isOpen) return false
        writer.execute { write(opcode, payload) }
        return true
    }

    /** Sends one masked frame; false if a close frame went out before it or the connection broke. */
    private fun write(
        opcode: Int,
        payload: ByteArray,
    ): Boolean {
        val n = payload.size
        val lengthBytes = if (n < 126) 0 else if (n <= 0xffff) 2 else 8
        val start = 2 + lengthBytes + 4
        val frame = ByteArray(start + n)
        frame[0] = (0x80 or opcode).toByte()
        frame[1] = (0x80 or if (n < 126) n else if (lengthBytes == 2) 126 else 127).toByte()
        for (i in 0 until lengthBytes) frame[2 + i] = (n.toLong() ushr (8 * (lengthBytes - 1 - i))).toByte()
        val mask = ByteArray(4).also(random::nextBytes)
        mask.copyInto(frame, 2 + lengthBytes)
        for (i in 0 until n) frame[start + i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        synchronized(lock) {
            if (closeSent) return false
            if (opcode == CLOSE) closeSent = true
            try {
                output.write(frame)
                output.flush()
                return true
            } catch (_: IOException) {
                socket.close()
                return false
            }
        }
    }

    /**
     * Reads up to [max] bytes into [buf]. A quiet [PING_EVERY_MS] sends a ping; another one with nothing
     * from the server, not even a pong, means the connection is dead.
     */
    private fun read(
        buf: ByteArray,
        max: Int,
        offset: Int = 0,
    ): Int {
        while (true) {
            try {
                val n = input.read(buf, offset, max)
                if (n < 0) throw EOFException("the server ended the connection without a close frame")
                pingSent = false
                return n
            } catch (e: SocketTimeoutException) {
                if (pingSent || closeSent) throw IOException("the server stopped answering", e)
                pingSent = true
                write(PING, ByteArray(0))
            }
        }
    }

    private fun readFully(
        buf: ByteArray,
        n: Int,
    ) {
        var done = 0
        while (done < n) done += read(buf, n - done, done)
    }

    companion object {
        private const val CONTINUATION = 0x0
        private const val TEXT = 0x1
        private const val BINARY = 0x2
        private const val CLOSE = 0x8
        private const val PING = 0x9
        private const val PONG = 0xa

        /** How long a close handshake may take before the connection is cut. */
        private const val CLOSE_WAIT_MS = 5_000L

        /** How long a connection may stay quiet before we ping, and then before it counts as dead. */
        const val PING_EVERY_MS = 30_000

        /** Like http(...)'s answers, a message is capped so a runaway server can't fill memory. */
        const val MAX_MESSAGE = 1 shl 20

        /** How long one address gets before the next one is tried alongside it (RFC 8305 suggests 250 ms). */
        private const val STAGGER_MS = 250L

        /** Addresses of a host tried at most. */
        private const val MAX_ADDRESSES = 6

        private const val ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** Cuts connections whose close handshake takes too long; one daemon thread for all of them. */
        private val scheduler by lazy {
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "websocket-close").apply { isDaemon = true } }
        }

        /**
         * Connects to a ws:// or wss:// [url] and does the handshake, sending [headers] with it. With [pin],
         * wss trusts only the certificate with that SHA-256 (normalized, see [pinnedSocketFactory]), whatever
         * host it names; without it the system's CAs and [hostnameVerifier] decide. Anything but a 101 that
         * accepts our key is an [IOException], so redirects aren't followed. Blocking.
         */
        fun connect(
            url: String,
            headers: Map<String, String> = emptyMap(),
            pin: String? = null,
            connectTimeout: Int = 15_000,
            maxMessage: Int = MAX_MESSAGE,
            pingEvery: Int = PING_EVERY_MS,
            hostnameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
        ): WebSocket {
            val uri =
                try {
                    URI(url)
                } catch (e: Exception) {
                    throw IOException("not a URL: $url", e)
                }
            val secure =
                when (uri.scheme?.lowercase()) {
                    "ws" -> false
                    "wss" -> true
                    else -> throw IOException("not a ws:// or wss:// URL: $url")
                }
            if (pin != null && !secure) throw IOException("pin: needs a wss:// URL: $url")
            val host = uri.host?.removeSurrounding("[", "]") ?: throw IOException("no host in $url")
            val port = if (uri.port != -1) uri.port else if (secure) 443 else 80
            if (port !in 1..65535) throw IOException("no port $port in $url")
            for ((k, v) in headers) {
                if (k.isEmpty() || k.any { it <= ' ' || it == ':' || it.code > 126 } || v.any { it == '\r' || it == '\n' }) {
                    throw IOException("not an HTTP header: $k")
                }
            }

            val tcp = connectTcp(InetAddress.getAllByName(host).toList(), port, connectTimeout)
            try {
                tcp.soTimeout = connectTimeout
                tcp.tcpNoDelay = true
                val socket =
                    if (!secure) {
                        tcp
                    } else {
                        val factory = if (pin != null) pinnedSocketFactory(pin) else SSLSocketFactory.getDefault() as SSLSocketFactory
                        (factory.createSocket(tcp, host, port, true) as SSLSocket).apply {
                            if (!host.isIpLiteral()) sslParameters = sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
                            startHandshake()
                            // A pin trusts its certificate at any address; otherwise it must name the host.
                            if (pin == null && !hostnameVerifier.verify(host, session)) {
                                throw SSLPeerUnverifiedException("the server's certificate isn't for $host")
                            }
                        }
                    }
                val input = BufferedInputStream(socket.inputStream, 16 * 1024)
                val output = socket.outputStream
                val key = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
                val path = (uri.rawPath?.ifEmpty { null } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                val hostHeader = (if (':' in host) "[$host]" else host) + (if (uri.port != -1) ":$port" else "")
                val request =
                    buildString {
                        append("GET $path HTTP/1.1\r\n")
                        append("Host: $hostHeader\r\n")
                        append("Upgrade: websocket\r\n")
                        append("Connection: Upgrade\r\n")
                        append("Sec-WebSocket-Key: $key\r\n")
                        append("Sec-WebSocket-Version: 13\r\n")
                        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) append("User-Agent: omalauncher-widgets\r\n")
                        for ((k, v) in headers) append("$k: $v\r\n")
                        append("\r\n")
                    }
                output.write(request.toByteArray(Charsets.UTF_8))
                output.flush()

                // The whole answer, not each read: a server dripping it a byte at a time would keep soTimeout from firing.
                val deadline = System.nanoTime() + connectTimeout * 1_000_000L
                val status = readLine(input, deadline)
                val code = status.split(' ').getOrNull(1)?.toIntOrNull()
                if (!status.startsWith("HTTP/1.1 ") || code == null) throw IOException("not an HTTP answer: $status")
                val answer = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input, deadline)
                    if (line.isEmpty()) break
                    if (answer.size > 100) throw IOException("too many headers in the answer")
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val name = line.substring(0, colon).trim().lowercase()
                        val value = line.substring(colon + 1).trim()
                        answer[name] = answer[name]?.let { "$it, $value" } ?: value
                    }
                }
                if (code != 101) throw IOException("HTTP $code for $url: ${status.substringAfter(" $code").trim()}")
                val expected =
                    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + ACCEPT_GUID).toByteArray(Charsets.US_ASCII)))
                // Subprotocols offered in [headers], like graphql-ws's; the server may pick one of them.
                val offered =
                    headers.filterKeys { it.equals("Sec-WebSocket-Protocol", ignoreCase = true) }.values
                        .flatMap { it.split(',') }.map { it.trim() }
                val chosen = answer["sec-websocket-protocol"]?.trim().orEmpty()
                when {
                    !answer["upgrade"].equals("websocket", ignoreCase = true) -> throw IOException("the server didn't upgrade to a WebSocket")
                    answer["connection"]?.split(',')?.none { it.trim().equals("upgrade", ignoreCase = true) } != false ->
                        throw IOException("the server didn't upgrade the connection")
                    answer["sec-websocket-accept"] != expected -> throw IOException("the server didn't accept our key")
                    !answer["sec-websocket-extensions"].isNullOrBlank() -> throw IOException("the server chose extensions we didn't offer")
                    chosen.isNotEmpty() && chosen !in offered -> throw IOException("the server chose the subprotocol $chosen, which we didn't offer")
                }
                socket.soTimeout = pingEvery
                return WebSocket(socket, input, output, maxMessage)
            } catch (e: IOException) {
                tcp.close()
                throw e
            }
        }

        /**
         * Connects to whichever of a host's [addresses] answers first (RFC 8305's Happy Eyeballs, simply): they
         * take turns between IPv6 and IPv4, and each next one starts [STAGGER_MS] after the one before, so a
         * network with broken IPv6 falls back to IPv4 at once instead of waiting out [timeout]. The others are
         * closed. Fails with the first address's error when none connects.
         */
        internal fun connectTcp(
            addresses: List<InetAddress>,
            port: Int,
            timeout: Int,
        ): Socket {
            val (first, other) = addresses.partition { it.javaClass == addresses[0].javaClass }
            val order = (0 until maxOf(first.size, other.size)).flatMap { listOfNotNull(first.getOrNull(it), other.getOrNull(it)) }.take(MAX_ADDRESSES)
            val sockets = order.map { Socket() }
            // Each attempt's socket, or its IOException, by index.
            val results = LinkedBlockingQueue<Pair<Int, Any>>()
            val done = AtomicBoolean()
            order.forEachIndexed { i, address ->
                thread(name = "websocket-connect", isDaemon = true) {
                    if (i > 0) Thread.sleep(i * STAGGER_MS)
                    if (done.get()) return@thread
                    results.put(
                        i to
                            try {
                                sockets[i].connect(InetSocketAddress(address, port), timeout)
                                sockets[i]
                            } catch (e: IOException) {
                                e
                            },
                    )
                }
            }
            val errors = arrayOfNulls<IOException>(order.size)
            val deadline = System.nanoTime() + timeout * 1_000_000L
            try {
                repeat(order.size) {
                    val (i, r) =
                        results.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                            ?: throw SocketTimeoutException("no answer from ${order.first().hostAddress} in $timeout ms")
                    if (r is Socket) {
                        done.set(true)
                        sockets.forEachIndexed { j, s -> if (j != i) s.close() }
                        return r
                    }
                    errors[i] = r as IOException
                }
                throw errors.filterNotNull().let { e -> e.first().apply { e.drop(1).forEach(::addSuppressed) } }
            } catch (e: IOException) {
                done.set(true)
                sockets.forEach { it.close() }
                throw e
            }
        }

        /** An HTTP header line without its CRLF; the handshake times out at [deadline] ([System.nanoTime]). */
        private fun readLine(
            input: InputStream,
            deadline: Long,
        ): String {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("the server ended the connection during the handshake")
                if (System.nanoTime() > deadline) throw SocketTimeoutException("the server took too long to answer the handshake")
                if (b == '\n'.code) break
                if (line.size() > 8 * 1024) throw IOException("a header line over 8 KB in the answer")
                line.write(b)
            }
            return line.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
        }

        private fun String.isIpLiteral() = ':' in this || all { it.isDigit() || it == '.' }

        /** A big-endian number from the first [n] bytes. */
        private fun number(
            b: ByteArray,
            n: Int,
        ): Long {
            var v = 0L
            for (i in 0 until n) v = (v shl 8) or (b[i].toLong() and 0xff)
            return v
        }

        /** Codes a close frame may carry (RFC 6455 7.4): the defined ones and 3000–4999 for applications. */
        private fun validCloseCode(code: Int) = code in 1000..1003 || code in 1007..1011 || code in 3000..4999

        private fun closePayload(
            code: Int,
            reason: String,
        ): ByteArray {
            var r = reason.toByteArray(Charsets.UTF_8)
            if (r.size > 123) {
                // Cut at a character boundary, not inside one.
                var end = 123
                while (end > 0 && (r[end].toInt() and 0xc0) == 0x80) end--
                r = r.copyOf(end)
            }
            return byteArrayOf((code shr 8).toByte(), code.toByte()) + r
        }
    }

    /** Checks UTF-8 as it streams in, so a message split anywhere, even inside a character, is checked whole. */
    private class Utf8 {
        /** Continuation bytes still to come, and the range the next one must be in. */
        private var need = 0
        private var lo = 0x80
        private var hi = 0xbf

        val complete get() = need == 0

        fun reset() {
            need = 0
        }

        fun feed(
            b: ByteArray,
            n: Int,
        ): Boolean {
            for (i in 0 until n) {
                val c = b[i].toInt() and 0xff
                if (need > 0) {
                    if (c < lo || c > hi) return false
                    need--
                    lo = 0x80
                    hi = 0xbf
                    continue
                }
                // Ranges that rule out overlong forms, surrogates and code points past U+10FFFF.
                when (c) {
                    in 0x00..0x7f -> Unit
                    in 0xc2..0xdf -> need = 1
                    0xe0 -> start(2, 0xa0, 0xbf)
                    0xed -> start(2, 0x80, 0x9f)
                    in 0xe1..0xef -> need = 2
                    0xf0 -> start(3, 0x90, 0xbf)
                    in 0xf1..0xf3 -> need = 3
                    0xf4 -> start(3, 0x80, 0x8f)
                    else -> return false
                }
            }
            return true
        }

        private fun start(
            n: Int,
            first: Int,
            last: Int,
        ) {
            need = n
            lo = first
            hi = last
        }
    }
}
