package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LinuxDnsTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun phoneServersReplaceTheFixedOnes() {
        val text = LinuxDns.resolvConf(listOf(ip("100.100.100.100"), ip("192.0.2.53")), null)
        assertEquals("${LinuxDns.MARKER}\nnameserver 100.100.100.100\nnameserver 192.0.2.53\n", text)
    }

    @Test fun noServersFallsBackToPublicOnes() {
        val text = LinuxDns.resolvConf(emptyList(), null)
        assertEquals("${LinuxDns.MARKER}\nnameserver 8.8.8.8\nnameserver 1.1.1.1\n", text)
    }

    @Test fun atMostThreeAndNoLinkLocal() {
        val text = LinuxDns.resolvConf(
            listOf(ip("fe80::1"), ip("192.0.2.1"), ip("2001:db8::53"), ip("192.0.2.2"), ip("192.0.2.3")),
            null,
        )
        assertFalse(text.contains("fe80"))
        assertEquals(3, text.lines().count { it.startsWith("nameserver ") })
        assertTrue(text.contains("nameserver 2001:db8:0:0:0:0:0:53") || text.contains("nameserver 2001:db8::53"))
    }

    @Test fun searchDomainsFollowTheNetwork() {
        val text = LinuxDns.resolvConf(listOf(ip("192.0.2.1")), "example.lan tail1234.ts.net")
        assertTrue(text.endsWith("search example.lan tail1234.ts.net\n"))
    }

    @Test fun onlyTheLaunchersOwnFileIsRewritten() {
        assertTrue(LinuxDns.managed(null))
        assertTrue(LinuxDns.managed("nameserver 8.8.8.8\nnameserver 1.1.1.1\n"))
        assertTrue(LinuxDns.managed(LinuxDns.resolvConf(emptyList(), null)))
        assertFalse(LinuxDns.managed("nameserver 192.0.2.10\nnameserver 8.8.8.8\n"))
    }
}
