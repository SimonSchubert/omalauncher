package dev.omalauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileKindTest {
    @Test fun android15ClassifiesAllKnownProfileKinds() {
        assertEquals(ProfileKind.Work, classifyProfile(35, "android.os.usertype.profile.MANAGED"))
        assertEquals(ProfileKind.Clone, classifyProfile(35, "android.os.usertype.profile.CLONE"))
        assertEquals(ProfileKind.Private, classifyProfile(35, "android.os.usertype.profile.PRIVATE"))
    }

    @Test fun unknownModernProfilesStayOutOfEveryTab() {
        assertNull(classifyProfile(35, null))
        assertNull(classifyProfile(37, "android.os.usertype.profile.UNKNOWN"))
    }

    @Test fun olderAndroidKeepsLegacyWorkProfileBehavior() {
        assertEquals(ProfileKind.Work, classifyProfile(34, null))
    }
}
