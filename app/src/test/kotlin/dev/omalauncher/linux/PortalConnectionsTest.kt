package dev.omalauncher.linux

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PortalConnectionsTest {
    private class Peer(val authorized: Boolean = true) { val closes = AtomicInteger() }
    private fun owner(limit: Int = 64) = PortalConnections<Peer>(limit) { it.closes.incrementAndGet() }

    @Test fun unauthorizedPeersNeverConsumeTheBudget() {
        val connections = PortalConnections<Peer>(1, authorizePeer = { it.authorized }) { it.closes.incrementAndGet() }
        val refused = Peer(false)
        val allowed = Peer()
        assertFalse(connections.admit(refused))
        assertEquals(1, refused.closes.get())
        assertTrue(connections.admit(allowed))
        connections.stop()
        assertEquals(1, allowed.closes.get())
    }

    @Test fun credentialFailureClosesThePeerWithoutStoppingAdmission() {
        var credentialFailure = true
        val connections = PortalConnections<Peer>(1, authorizePeer = {
            if (credentialFailure) error("credential read failed") else true
        }) { it.closes.incrementAndGet() }
        val refused = Peer()
        assertFalse(connections.admit(refused))
        assertEquals(1, refused.closes.get())
        credentialFailure = false
        assertTrue(connections.admit(Peer()))
        connections.stop()
    }

    @Test fun stopClosesEveryUndecodedPeerOnce() {
        val connections = owner()
        val peers = List(64) { Peer() }
        peers.forEach { assertTrue(connections.admit(it)) }
        connections.stop()
        connections.stop()
        peers.forEach { connections.release(it); assertEquals(1, it.closes.get()) }
    }

    @Test fun lateAcceptIsRejectedAndClosed() {
        val connections = owner()
        connections.stop()
        val late = Peer()
        assertFalse(connections.admit(late))
        assertEquals(1, late.closes.get())
    }

    @Test fun capacityIsReleasedByReceiveCompletion() {
        val connections = owner(1)
        val first = Peer()
        val refused = Peer()
        assertTrue(connections.admit(first))
        assertFalse(connections.admit(refused))
        assertEquals(1, refused.closes.get())
        connections.release(first)
        connections.release(first)
        assertEquals(1, first.closes.get())
        val next = Peer()
        assertTrue(connections.admit(next))
        connections.stop()
        assertEquals(1, next.closes.get())
    }

    @Test fun oldCompletionCannotReleaseNewEpochCapacity() {
        val old = owner()
        val peers = List(64) { Peer() }
        peers.forEach { assertTrue(old.admit(it)) }
        old.stop()
        val fresh = owner(1)
        val current = Peer()
        assertTrue(fresh.admit(current))
        peers.forEach { old.release(it) }
        assertFalse(fresh.admit(Peer()))
        assertEquals(0, current.closes.get())
        fresh.stop()
        assertEquals(1, current.closes.get())
    }

    @Test fun concurrentAcceptAndStopCannotLoseAPeer() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(64) {
                val connections = owner()
                val peer = Peer()
                val gate = CountDownLatch(1)
                val accept = executor.submit { check(gate.await(2, TimeUnit.SECONDS)); connections.admit(peer) }
                val stop = executor.submit { check(gate.await(2, TimeUnit.SECONDS)); connections.stop() }
                gate.countDown()
                accept.get(2, TimeUnit.SECONDS)
                stop.get(2, TimeUnit.SECONDS)
                connections.release(peer)
                assertEquals(1, peer.closes.get())
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun oneCloseFailureDoesNotAbandonOtherPeers() {
        val closes = AtomicInteger()
        val connections = PortalConnections<Peer>(2) { closes.incrementAndGet(); error("close failed") }
        assertTrue(connections.admit(Peer()))
        assertTrue(connections.admit(Peer()))
        connections.stop()
        assertEquals(2, closes.get())
    }

    /** Real JVM TCP read/close, not Android LocalSocket or SCM_RIGHTS coverage. */
    @Test fun stopUnblocksARealIncompleteHeaderRead() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            Socket(listener.inetAddress, listener.localPort).use { client ->
                listener.accept().use { peer ->
                    peer.soTimeout = 2_000
                    val connections = PortalConnections<Socket>(1) { it.close() }
                    assertTrue(connections.admit(peer))
                    client.getOutputStream().write(0)
                    val firstByte = CountDownLatch(1)
                    val executor = Executors.newSingleThreadExecutor()
                    try {
                        val read = executor.submit<Boolean> {
                            try {
                                assertEquals(0, peer.getInputStream().read())
                                firstByte.countDown()
                                peer.getInputStream().read() < 0
                            } catch (_: java.io.IOException) { true }
                            finally { connections.release(peer) }
                        }
                        assertTrue(firstByte.await(2, TimeUnit.SECONDS))
                        connections.stop()
                        assertTrue(read.get(2, TimeUnit.SECONDS))
                        assertTrue(peer.isClosed)
                    } finally { connections.stop(); executor.shutdownNow() }
                }
            }
        }
    }
}
