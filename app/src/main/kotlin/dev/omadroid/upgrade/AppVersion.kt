package dev.omadroid.upgrade

import android.content.Context

private var cached: String? = null

/**
 * Identifies the installed APK: its version code plus when it was installed,
 * so a reinstall during development (same version code) counts as new too.
 */
fun appStamp(context: Context): String =
    cached ?: context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.longVersionCode}-${it.lastUpdateTime}" }.also { cached = it }
