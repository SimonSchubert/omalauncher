package dev.omadroid

import android.app.Application

class App : Application() {
    val runtime by lazy { Runtime(this) }
}
