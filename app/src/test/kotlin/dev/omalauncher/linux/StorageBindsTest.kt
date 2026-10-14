package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StorageBindsTest {
    @Test fun roundTripsPathsIncludingSpacesAndUnicode() {
        val binds = listOf(StorageBind("/storage/emulated/0/My photos 📷", "/root/Pictures"), StorageBind("/storage/USB", "/mnt/usb"))
        assertEquals(binds, AndroidIntegration.decode(AndroidIntegration.encode(binds)))
    }

    @Test fun rejectsPathsThatChangeBindArgumentMeaning() {
        for (path in listOf("relative", "/a:b", "/a\nb", "/a\u0000b", "/a\u007fb", "/a/../b", "/a/./b", "/a//b", "/a/")) {
            assertThrows(path, IllegalArgumentException::class.java) { StorageBinds.validate(listOf(StorageBind(path, "/mnt/data"))) }
            assertThrows(path, IllegalArgumentException::class.java) { StorageBinds.validate(listOf(StorageBind("/storage/data", path))) }
        }
    }

    @Test fun protectsSystemAndLauncherPathsAndTheirAncestors() {
        for (path in listOf("/", "/root", "/usr", "/usr/bin", "/run/omalauncher-ando", "/root/.local/share", "/root/.config/omalauncher", "/dev", "/tmp")) {
            assertThrows(path, IllegalArgumentException::class.java) { StorageBinds.validate(listOf(StorageBind("/storage/data", path))) }
        }
        StorageBinds.validate(listOf(StorageBind("/storage/data", "/root/Documents"), StorageBind("/storage/data", "/mnt/usr")))
    }

    @Test fun rejectsOverlappingDestinationsInEitherOrderButAllowsSiblingPrefixes() {
        val parent = StorageBind("/storage/a", "/mnt/data")
        val child = StorageBind("/storage/b", "/mnt/data/sub")
        for (list in listOf(listOf(parent, child), listOf(child, parent), listOf(parent, parent))) {
            assertThrows(IllegalArgumentException::class.java) { StorageBinds.validate(list) }
        }
        StorageBinds.validate(listOf(parent, StorageBind("/storage/b", "/mnt/database")))
    }

    @Test fun limitsConfigurationSize() {
        assertThrows(IllegalArgumentException::class.java) {
            StorageBinds.validate((0..StorageBinds.MAX_BINDS).map { StorageBind("/storage/a", "/mnt/$it") })
        }
    }

    @Test fun malformedSavedBindsCannotSilentlyDisappear() {
        for (text in listOf("{}", "not json", """{"v":2,"data":[]}""", """{"v":1,"data":[{"host":"/a"}]}""")) {
            assertThrows(Exception::class.java) { AndroidIntegration.decode(text) }
        }
    }
}
