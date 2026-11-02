package dev.omalauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

class WebSocketConnectTest {
    /** 192.0.2.x (TEST-NET) never answers, like a network whose IPv6 is broken. */
    private fun dead(n: Int) = InetAddress.getByName("192.0.2.$n")

    /** A dead first address doesn't hold up the next one, and IPv6 takes its turn second. */
    @Test fun fallsBackQuickly() {
        ServerSocket(0).use { server ->
            val began = System.currentTimeMillis()
            val socket = WebSocket.connectTcp(listOf(dead(1), dead(2), InetAddress.getByName("::1")), server.localPort, 15_000)
            socket.use {
                assertEquals(InetAddress.getByName("::1"), it.inetAddress)
                assertTrue("took ${System.currentTimeMillis() - began} ms", System.currentTimeMillis() - began < 2_000)
            }
        }
    }

    /** When nothing answers, the first address's error says why. */
    @Test fun failsWithTheFirstError() {
        val port = ServerSocket(0).use { it.localPort }
        val e =
            runCatching { WebSocket.connectTcp(listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1")), port, 5_000) }
                .exceptionOrNull()
        assertTrue("$e", e is IOException && e.suppressed.size == 1)
    }

    /** Nothing at all within the time given is a timeout. */
    @Test fun timesOut() {
        val began = System.currentTimeMillis()
        val e = runCatching { WebSocket.connectTcp(listOf(dead(1)), 443, 500) }.exceptionOrNull()
        assertTrue("$e", e is IOException)
        assertTrue(System.currentTimeMillis() - began < 2_000)
    }
}
