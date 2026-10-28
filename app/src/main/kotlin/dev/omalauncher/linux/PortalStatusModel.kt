package dev.omalauncher.linux

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Android-free snapshot and callback ownership policy. null means unobserved, not false. */
internal data class PortalNetworkStatus(val available: Boolean, val metered: Boolean, val connectivity: Int) {
    init { require(connectivity in 1..4) }

    companion object {
        // A confirmed absence of a default network is not an unknown query or a free connection.
        val OFFLINE = PortalNetworkStatus(false, true, 1)

        fun fromCapabilities(internet: Boolean, validated: Boolean, captive: Boolean, notMetered: Boolean): PortalNetworkStatus =
            PortalNetworkStatus(true, !notMetered, when {
                internet && validated -> 4
                captive -> 3
                internet -> 2
                else -> 1
            })
    }
}

internal data class PortalStatusSnapshot(val powerSaver: Boolean? = null, val network: PortalNetworkStatus? = null) {
    fun text(): String = buildString {
        append("power-saver=").append(powerSaver?.let { if (it) "1" else "0" } ?: "unknown").append('\n')
        append("available=").append(network?.let { if (it.available) "1" else "0" } ?: "unknown").append('\n')
        append("metered=").append(network?.let { if (it.metered) "1" else "0" } ?: "unknown").append('\n')
        append("connectivity=").append(network?.connectivity ?: "unknown").append('\n')
    }
}

/** One generation per registration; a callback from a previous registration cannot mutate a restart. */
internal class PortalStatusOwnership {
    private var generation = 0L
    private var running = false
    private var callbackObserved = false
    private var currentNetwork: Any? = null
    var snapshot = PortalStatusSnapshot()
        private set

    fun start(): Long {
        generation++
        running = true
        callbackObserved = false
        currentNetwork = null
        snapshot = PortalStatusSnapshot()
        return generation
    }

    fun stop() {
        generation++
        running = false
        currentNetwork = null
        snapshot = PortalStatusSnapshot()
    }

    fun accepts(token: Long): Boolean = running && token == generation

    fun power(token: Long, saving: Boolean?) {
        if (accepts(token)) snapshot = snapshot.copy(powerSaver = saving)
    }

    /** Only an initial query, never a callback, may establish a confirmed offline baseline. */
    fun initialNetwork(token: Long, network: Any?, status: PortalNetworkStatus?) {
        if (!accepts(token) || callbackObserved) return
        currentNetwork = network
        snapshot = snapshot.copy(network = if (network == null) PortalNetworkStatus.OFFLINE else status)
    }

    fun available(token: Long, network: Any) {
        if (!accepts(token)) return
        callbackObserved = true
        if (network != currentNetwork) {
            currentNetwork = network
            snapshot = snapshot.copy(network = null) // wait for onCapabilitiesChanged
        }
    }

    fun capabilities(token: Long, network: Any, status: PortalNetworkStatus) {
        if (!accepts(token) || !callbackObserved || network != currentNetwork) return
        snapshot = snapshot.copy(network = status)
    }

    fun lost(token: Long, network: Any) {
        if (!accepts(token) || !callbackObserved || network != currentNetwork) return
        currentNetwork = null
        snapshot = snapshot.copy(network = PortalNetworkStatus.OFFLINE)
    }
}

/** Creates in the Android-owned runtime directory and replaces the directory entry, never opens destination. */
internal object PortalStatusFile {
    const val NAME = "omalauncher-system-status"

    fun write(runtimeDir: File, snapshot: PortalStatusSnapshot) {
        val temporary = Files.createTempFile(runtimeDir.toPath(), ".portal-system-status-", ".tmp")
        try {
            Files.write(temporary, snapshot.text().toByteArray(Charsets.UTF_8))
            Files.move(temporary, File(runtimeDir, NAME).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun remove(runtimeDir: File) {
        Files.deleteIfExists(File(runtimeDir, NAME).toPath())
    }
}
