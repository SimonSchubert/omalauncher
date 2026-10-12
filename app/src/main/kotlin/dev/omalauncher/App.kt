package dev.omalauncher

import android.app.Application
import dev.omalauncher.nav.GestureNav
import dev.omalauncher.nav.PhoneNavBar

class App : Application() {
    val runtime by lazy { Runtime(this) }

    /** Apart from [runtime]: the navigation service runs without the launcher's Linux and widgets. */
    val gestureNav by lazy { GestureNav(this) }

    override fun onCreate() {
        super.onCreate()
        // Before any activity: a phone left with gestures selected in one place and buttons in the other has no
        // navigation in the app that's already open.
        PhoneNavBar.repair(this)
    }
}
