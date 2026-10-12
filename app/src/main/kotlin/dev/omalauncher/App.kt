package dev.omalauncher

import android.app.Application

class App : Application() {
    val runtime by lazy { Runtime(this) }
    // Discovery and consent never initialize the Linux runtime.
    val desktopAccess by lazy { dev.omalauncher.desktop.DesktopAccess(this) }
}
