package dev.omalauncher.ui

import android.content.Context
import android.util.Log

/**
 * Expands Android's notification shade (the top bar pulled down).
 * Used by swipe-down on home in Omalauncher+.
 *
 * Uses StatusBarManager.expandNotificationsPanel() via reflection: the method is
 * hidden (@hide) from the SDK but callable, guarded by the normal
 * android.permission.EXPAND_STATUS_BAR (manifest only, no runtime prompt).
 * Fails silently (log only) when the device blocks it.
 */
fun expandNotificationsPanel(context: Context) {
    try {
        val service = context.getSystemService("statusbar") ?: return
        val manager = Class.forName("android.app.StatusBarManager")
        val expand = manager.getMethod("expandNotificationsPanel")
        expand.invoke(service)
    } catch (e: Exception) {
        Log.w("Omalauncher+", "expandNotificationsPanel failed: ${e.message}")
    }
}

/** Collapses the shade / quick settings. Used in tests and for symmetry. */
fun collapseStatusBarPanels(context: Context) {
    try {
        val service = context.getSystemService("statusbar") ?: return
        val manager = Class.forName("android.app.StatusBarManager")
        val collapse = manager.getMethod("collapsePanels")
        collapse.invoke(service)
    } catch (e: Exception) {
        Log.w("Omalauncher+", "collapsePanels failed: ${e.message}")
    }
}
