package dev.omalauncher.linux

import java.util.Collections
import java.util.IdentityHashMap

/** One listener epoch owns every admitted peer, including incomplete packets.
 * Closing an old epoch cannot consume a restarted listener's capacity. */
internal class PortalConnections<T : Any>(
    private val limit: Int,
    private val authorizePeer: (T) -> Boolean = { true },
    private val closePeer: (T) -> Unit,
) {
    private val peers = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    private var stopped = false

    init { require(limit > 0) }

    fun admit(peer: T): Boolean {
        val accepted = synchronized(this) {
            if (stopped || peers.size >= limit) false
            else runCatching { authorizePeer(peer) }.getOrDefault(false) && peers.add(peer)
        }
        if (!accepted) close(peer)
        return accepted
    }

    fun release(peer: T) {
        val owned = synchronized(this) { peers.remove(peer) }
        if (owned) close(peer)
    }

    fun stop() {
        val pending = synchronized(this) {
            stopped = true
            peers.toList().also { peers.clear() }
        }
        pending.forEach(::close)
    }

    private fun close(peer: T) { runCatching { closePeer(peer) } }
}
