package dev.omalauncher.theme

import dev.omalauncher.upgrade.Versioned
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperProfilesTest {
    @Test fun newImagesStartAtDefaultsAndReturningRestoresEachImage() {
        val profiles = WallpaperProfiles()
        val original = WallpaperFraming(false, 0.2f, 0.8f, 2f, true)
        val first = profiles.choose("a", "b", original, original)
        assertEquals(WallpaperFraming(), first)
        assertEquals(original, profiles.choose("b", "a", first, first))
    }

    @Test fun editsAndExplicitCombinedChangesAreKept() {
        val profiles = WallpaperProfiles()
        val old = WallpaperFraming()
        val edit = old.copy(x = 0.1f, zoom = 1.7f)
        assertEquals(edit, profiles.choose("a", "a", old, edit))
        val explicit = edit.copy(fit = true)
        assertEquals(explicit, profiles.choose("a", "b", edit, explicit))
    }

    @Test fun profilesSurviveVersionedPersistence() {
        val profiles = WallpaperProfiles()
        val framing = WallpaperFraming(false, 1f, 0f, 3f, true)
        profiles.remember("omarchy/themes/a/backgrounds/photo.png", framing)
        val restored = WallpaperProfiles.read(Versioned.write(WallpaperProfiles.VERSION, profiles.data()))
        assertEquals(framing, restored.choose("other", "omarchy/themes/a/backgrounds/photo.png", WallpaperFraming(), WallpaperFraming()))
    }

    @Test fun aDamagedProfileDoesNotDiscardTheOthers() {
        val profiles = WallpaperProfiles()
        val framing = WallpaperFraming(zoom = 2f)
        profiles.remember("good", framing)
        val json = profiles.data().put("bad", JSONObject().put("x", -5))
        val restored = WallpaperProfiles.read(Versioned.write(WallpaperProfiles.VERSION, json))
        assertEquals(framing, restored.choose("other", "good", WallpaperFraming(), WallpaperFraming()))
        assertEquals(WallpaperFraming(), restored.choose("good", "bad", framing, framing))
    }
}
