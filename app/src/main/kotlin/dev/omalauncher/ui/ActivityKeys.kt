package dev.omalauncher.ui

import android.app.Activity
import android.view.KeyEvent
import android.view.Window

/** Intercepts keys before focused views, preserving AndroidX's normal keyboard and Back dispatch. */
internal fun Activity.interceptKeys(handle: (KeyEvent) -> Boolean) {
    val original = checkNotNull(window.callback)
    window.callback = object : Window.Callback by original {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = handle(event) || original.dispatchKeyEvent(event)
    }
}
