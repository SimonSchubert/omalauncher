package dev.omalauncher

internal enum class ProfileKind { Personal, Work, Clone, Private }

/** Android's public user type values. Unknown Android 15+ profiles are not shown. */
internal fun classifyProfile(sdk: Int, userType: String?): ProfileKind? =
    if (sdk < 35) ProfileKind.Work else when (userType) {
        "android.os.usertype.profile.MANAGED" -> ProfileKind.Work
        "android.os.usertype.profile.CLONE" -> ProfileKind.Clone
        "android.os.usertype.profile.PRIVATE" -> ProfileKind.Private
        else -> null
    }
