package dev.omalauncher.desktop

import org.junit.Assert.*
import org.junit.Test

class DesktopProtocolTest {
    private val host = DesktopIdentity(10001, "host.desktop", "certificate-a")

    @Test fun approvalAndExactIdentityAreRequired() {
        val lease = DesktopLease(host)
        lease.check(host, true)
        listOf(null, host.copy(uid = 10002), host.copy(packageName = "other.host"), host.copy(certificate = "certificate-b")).forEach {
            assertThrows(SecurityException::class.java) { lease.check(it, true) }
        }
        assertThrows(SecurityException::class.java) { lease.check(host, false) }
    }

    @Test fun revocationAndCloseCannotReviveStaleSessions() {
        val lease = DesktopLease(host)
        lease.close()
        lease.close()
        assertThrows(IllegalStateException::class.java) { lease.check(host, true) }
    }

    @Test fun metricsCannotAllocateUnboundedSurfaces() {
        DesktopRules.size(1920, 1080)
        DesktopRules.size(4096, 4096)
        listOf(0 to 1080, -1 to 1080, 8193 to 1, 8192 to 8192, Int.MAX_VALUE to Int.MAX_VALUE).forEach { (w, h) ->
            assertThrows(IllegalArgumentException::class.java) { DesktopRules.size(w, h) }
        }
    }

    @Test fun profileNamesCannotEscapeTheirStorageNamespace() {
        assertEquals("monitor-1", DesktopRules.profile("monitor-1"))
        listOf("", "../phone", "a/b", "x".repeat(41)).forEach {
            assertThrows(IllegalArgumentException::class.java) { DesktopRules.profile(it) }
        }
    }

    @Test fun launchRoutingDoesNotDependOnTheHostProduct() {
        assertEquals("host", DesktopRules.launchMode("host"))
        assertEquals("system", DesktopRules.launchMode("system"))
        assertThrows(IllegalArgumentException::class.java) { DesktopRules.launchMode("omalateral") }
    }
}
