package dev.omalauncher

import org.junit.Assert.assertFalse
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
}
