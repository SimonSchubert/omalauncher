package dev.omalauncher

import android.app.Application
import dev.omalauncher.nav.GestureNav

class App : Application() {
    val runtime by lazy { Runtime(this) }

    /** Apart from [runtime]: the navigation service runs without the launcher's Linux and widgets. */
    val gestureNav by lazy { GestureNav(this) }
}
