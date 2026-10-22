package dev.omalauncher.packageinstall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageInstallProtocolTest {
    @Test
    fun parsesAllowlistedDeviceActions() {
        assertEquals(
            DeviceActionRequest("open-1", DeviceActionProtocol.OPEN_PACKAGE, "org.example.app", "open the test app"),
            DeviceActionProtocol.parse("open-1", "open-package\norg.example.app\nopen the test app\n".toByteArray()),
        )
        assertEquals(
            DeviceActionRequest("url-1", DeviceActionProtocol.OPEN_URL, "https://example.com", "open docs"),
            DeviceActionProtocol.parse("url-1", "open-url\nhttps://example.com\nopen docs\n".toByteArray()),
        )
        assertEquals(
            DeviceActionRequest(
                "split-1",
                DeviceActionProtocol.OPEN_SPLIT_PACKAGES,
                "org.example.first,org.example.second",
                "compare the apps",
            ),
            DeviceActionProtocol.parse(
                "split-1",
                "open-split-packages\norg.example.first,org.example.second\ncompare the apps\n".toByteArray(),
            ),
        )
    }

    @Test
    fun rejectsUnboundedDeviceActions() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceActionProtocol.parse("id", "shell\ninput tap 1 1\n".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceActionProtocol.parse("id", "open-url\nfile:///etc/passwd\n".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceActionProtocol.parse("id", "open-component\norg.example/.Not Valid\n".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceActionProtocol.parse("id", "open-split-packages\norg.example.only\n".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceActionProtocol.parse("id", "open-split-packages\norg.example.same,org.example.same\n".toByteArray())
        }
    }

    @Test
    fun normalModeKeepsBothApprovalPrompts() {
        val policy = PackageInstallApprovalPolicy(yolo = false)
        assertEquals(true, policy.showLauncherApproval)
        assertEquals(true, policy.requestAndroidUserAction)
    }

    @Test
    fun yoloOnlySkipsTheHostPromptAndRequestsNonInteractiveInstall() {
        val policy = PackageInstallApprovalPolicy(yolo = true)
        assertEquals(false, policy.showLauncherApproval)
        assertEquals(false, policy.requestAndroidUserAction)
    }

    @Test
    fun launcherUpdatesAlwaysNeedExplicitApproval() {
        assertEquals(true, requiresExplicitApproval("dev.omalauncher", "dev.omalauncher"))
        assertEquals(false, requiresExplicitApproval("com.example.app", "dev.omalauncher"))
    }

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
