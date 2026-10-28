package dev.omalauncher.linux

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PortalStatusModelTest {
    @get:Rule val dir = TemporaryFolder()

    @Test fun unknownIsNotAFalsePowerOrOfflineNetwork() {
        assertEquals("power-saver=unknown\navailable=unknown\nmetered=unknown\nconnectivity=unknown\n", PortalStatusSnapshot().text())
        assertEquals("power-saver=1\navailable=unknown\nmetered=unknown\nconnectivity=unknown\n",
            PortalStatusSnapshot(powerSaver = true).text())
        assertEquals("power-saver=unknown\navailable=0\nmetered=1\nconnectivity=1\n",
            PortalStatusSnapshot(network = PortalNetworkStatus.OFFLINE).text())
        assertEquals("power-saver=0\navailable=1\nmetered=0\nconnectivity=4\n",
            PortalStatusSnapshot(false, PortalNetworkStatus(true, false, 4)).text())
    }

    @Test fun capabilityPriorityAndConservativeMeteredPolicy() {
        fun status(internet: Boolean, valid: Boolean, captive: Boolean, free: Boolean) =
            PortalNetworkStatus.fromCapabilities(internet, valid, captive, free)
        assertEquals(PortalNetworkStatus(true, true, 4), status(true, true, false, false))
        assertEquals(PortalNetworkStatus(true, false, 4), status(true, true, true, true))
        assertEquals(PortalNetworkStatus(true, true, 3), status(true, false, true, false))
        assertEquals(PortalNetworkStatus(true, true, 2), status(true, false, false, false))
        assertEquals(PortalNetworkStatus(true, true, 1), status(false, false, false, false))
        assertEquals(PortalNetworkStatus(true, false, 1), status(false, false, false, true))
    }

    @Test fun initialQueryCannotOverwriteAReceivedCallbackAndLateCapabilitiesBelongToCurrentNetworkOnly() {
        val owner = PortalStatusOwnership()
        val token = owner.start()
        owner.power(token, false)
        val old = "old"
        val next = "next"
        val full = PortalNetworkStatus.fromCapabilities(true, true, false, true)
        val captive = PortalNetworkStatus.fromCapabilities(true, false, true, false)
        owner.initialNetwork(token, old, full)
        assertEquals(full, owner.snapshot.network)
        owner.available(token, next)
        assertNull(owner.snapshot.network) // no old connectivity during the replacement gap
        owner.initialNetwork(token, old, full)
        owner.capabilities(token, old, full)
        owner.lost(token, old)
        assertNull(owner.snapshot.network)
        owner.capabilities(token, next, captive)
        assertEquals(captive, owner.snapshot.network)
        owner.available(token, next)
        assertEquals(captive, owner.snapshot.network) // duplicate available doesn't erase capabilities
        owner.lost(token, next)
        assertEquals(PortalNetworkStatus.OFFLINE, owner.snapshot.network)
        assertEquals(false, owner.snapshot.powerSaver)
    }

    @Test fun confirmedNoDefaultAndUnresolvedCapabilitiesAreDifferent() {
        val owner = PortalStatusOwnership()
        val token = owner.start()
        owner.initialNetwork(token, "active", null) // null capabilities is unknown
        assertNull(owner.snapshot.network)
        owner.initialNetwork(token, null, null) // confirmed absence
        assertEquals(PortalNetworkStatus.OFFLINE, owner.snapshot.network)
        owner.available(token, "active")
        assertNull(owner.snapshot.network)
    }

    @Test fun stopAndRestartFenceOldCallbacksIncludingPowerAndLost() {
        val owner = PortalStatusOwnership()
        val first = owner.start()
        owner.available(first, "old")
        owner.stop()
        assertEquals(PortalStatusSnapshot(), owner.snapshot)
        val second = owner.start()
        assertNotEquals(first, second)
        owner.power(first, true)
        owner.available(first, "old")
        owner.capabilities(first, "old", PortalNetworkStatus.OFFLINE)
        owner.lost(first, "old")
        assertEquals(PortalStatusSnapshot(), owner.snapshot)
        owner.power(second, false)
        owner.initialNetwork(second, null, null)
        assertEquals(false, owner.snapshot.powerSaver)
        assertEquals(PortalNetworkStatus.OFFLINE, owner.snapshot.network)
    }

    @Test fun atomicReplacementDoesNotOpenSymlinkAndLeavesNoTemporaryEntries() {
        val runtime = dir.newFolder("runtime")
        val outside = dir.newFile("private").apply { writeText("untouched") }
        val destination = File(runtime, PortalStatusFile.NAME).toPath()
        Files.createSymbolicLink(destination, outside.toPath())
        PortalStatusFile.write(runtime, PortalStatusSnapshot(powerSaver = true))
        assertEquals("untouched", outside.readText())
        assertFalse(Files.isSymbolicLink(destination))
        PortalStatusFile.write(runtime, PortalStatusSnapshot(network = PortalNetworkStatus.OFFLINE))
        assertEquals("power-saver=unknown\navailable=0\nmetered=1\nconnectivity=1\n", Files.readString(destination))
        assertEquals(listOf(PortalStatusFile.NAME), runtime.list()!!.toList())
        PortalStatusFile.remove(runtime)
        assertFalse(Files.exists(destination))
    }
}
