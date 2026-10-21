package dev.omalauncher.packageinstall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageInstallProtocolTest {
    @Test
    fun parsesPathAndReason() {
        assertEquals(
            PackageInstallRequest("123-test", "/root/build/app.apk", "debug build"),
            PackageInstallProtocol.parse("123-test", "/root/build/app.apk\ndebug build\n".toByteArray()),
        )
    }

    @Test
    fun rejectsRelativePath() {
        assertThrows(IllegalArgumentException::class.java) {
            PackageInstallProtocol.parse("id", "build/app.apk\n".toByteArray())
        }
    }

    @Test
    fun rejectsControlCharactersInReason() {
        assertThrows(IllegalArgumentException::class.java) {
            PackageInstallProtocol.parse("id", "/root/app.apk\ninstall\u0007\n".toByteArray())
        }
    }

    @Test
    fun rejectsControlCharactersAndMalformedUtf8InPath() {
        assertThrows(IllegalArgumentException::class.java) {
            PackageInstallProtocol.parse("id", "/root/app\u0007.apk\n".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackageInstallProtocol.parse("id", byteArrayOf('/'.code.toByte(), 0xC3.toByte(), 0x28))
        }
    }

    @Test
    fun boundsErrorResponsesAndRemovesNewlines() {
        val response = PackageInstallProtocol.response("failed\nwith details")
        assertEquals("error: failed with details", response)
        assertEquals("ok", PackageInstallProtocol.response("ok"))
    }

    @Test
    fun rejectsTrailingRequestData() {
        assertThrows(IllegalArgumentException::class.java) {
            PackageInstallProtocol.parse("id", "/root/app.apk\nreason\nextra".toByteArray())
        }
    }
}
