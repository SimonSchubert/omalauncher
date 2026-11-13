package dev.omalauncher

import dev.omalauncher.LauncherUpdates.Companion.compareVersions
import dev.omalauncher.LauncherUpdates.Companion.newerRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherUpdatesTest {
    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"html_url":"https://github.com/x/releases/tag/$tag"}"""

    private fun releases(vararg r: String) = r.joinToString(",", "[", "]")

    @Test fun findsTheNewestNewerRelease() {
        val json = releases(release("v1.4.0"), release("v1.6.0"), release("v1.5.0"))
        assertEquals("1.6.0" to "https://github.com/x/releases/tag/v1.6.0", newerRelease(json, "1.4.0"))
    }

    @Test fun nothingWhenUpToDateOrAhead() {
        val json = releases(release("v1.4.0"), release("v1.3.0"))
        assertNull(newerRelease(json, "1.4.0"))
        // A build from main after a release, with its version already bumped.
        assertNull(newerRelease(json, "1.5.0"))
    }

    @Test fun skipsDraftsPrereleasesAndOtherTags() {
        val json = releases(release("v2.0.0", draft = true), release("v1.9.0", prerelease = true), release("mesa-zink-26.2.4"), release("v1.4.0"))
        assertNull(newerRelease(json, "1.4.0"))
    }

    @Test fun comparesVersionPartsAsNumbers() {
        assertTrue(compareVersions("1.10.0", "1.9.2") > 0)
        assertTrue(compareVersions("0.20.0", "1.0.0") < 0)
        assertEquals(0, compareVersions("1.4", "1.4.0"))
    }
}
