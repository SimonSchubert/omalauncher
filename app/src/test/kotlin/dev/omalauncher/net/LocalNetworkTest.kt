package dev.omalauncher.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    @Test fun localAddresses() {
        for (url in listOf("wss://192.168.1.20:8759/stream", "http://10.0.2.2/", "https://172.16.0.1/a", "ws://169.254.1.1/", "wss://[fd12::1]:9443/", "ws://[fe80::1]/")) {
            assertTrue(url, LocalNetwork.isLocal(url))
        }
        for (url in listOf("https://1.1.1.1/", "wss://[2606:4700::1111]/", "ws://127.0.0.1:9001/", "not a url")) {
            assertFalse(url, LocalNetwork.isLocal(url))
        }
    }

    @Test fun refuses() {
        for (url in
            listOf(
                "http://127.0.0.1/",
                "wss://[::1]:9443/",
                "http://0.0.0.0:8080/",
                "ws://[::]/",
                "http://192.168.1.20/",
                "wss://[fd12::1]:9443/",
                // A path character java.net.URI rejects but java.net.URL (what the real connection parses with)
                // tolerates: the host check must still catch this, not silently let it through unchecked.
                "http://127.0.0.1/not a path",
            )
        ) {
            assertNotNull(url, LocalNetwork.refuse(url))
        }
        for (url in listOf("https://1.1.1.1/", "wss://[2606:4700::1111]/", "not a url")) {
            assertNull(url, LocalNetwork.refuse(url))
        }
    }
}
