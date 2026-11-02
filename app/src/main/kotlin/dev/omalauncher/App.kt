package dev.omalauncher

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources

class App : Application() {
    val runtime by lazy { Runtime(this) }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        AppLanguage.load(this, base)
    }

    /** In the language picked in settings, if one is: what the app's context reads its text from. */
    override fun getResources(): Resources = AppLanguage.resources(baseContext, super.getResources())

    override fun onConfigurationChanged(newConfig: Configuration) {
        // Android has set the default Locale back to the phone's; the widgets' callbacks below read it.
        AppLanguage.applyDefault()
        super.onConfigurationChanged(newConfig)
    }
}
