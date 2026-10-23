package dev.omalauncher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.SettingsPage
import dev.omalauncher.ui.SettingsPages
import dev.omalauncher.ui.SystemBarsEffect
import dev.omalauncher.ui.applySystemBars

/**
 * The launcher's settings and the screens they open (style, agents, store, Linux, …):
 * their own task and Recents card, so going home or to a terminal and back finds
 * them as they were. Home, widgets and anything that needs Linux open a screen
 * directly; closing the last one closes the task.
 */
class SettingsActivity : InstantActivity() {
    private val runtime get() = (application as App).runtime

    private val stack = mutableStateListOf<SettingsPage>()

    /** The home page that was showing; the store puts widgets there. */
    private var homePage by mutableIntStateOf(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        open(intent)
        applySystemBars(this, Palette.isLight)
        setInstantContent {
            SystemBarsEffect(this@SettingsActivity)
            SettingsPages(runtime, stack, homePage, onClose = ::closeInstantly)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    /**
     * Settings again comes back to where you were in them. A screen asked for by
     * name (a widget's link, Linux for a terminal) shows on its own: back from it
     * returns to whatever asked.
     */
    private fun open(intent: Intent) {
        homePage = intent.getIntExtra(EXTRA_HOME_PAGE, homePage)
        val page = intent.getStringExtra(EXTRA_PAGE)?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } } ?: SettingsPage.Settings
        if (page == SettingsPage.Settings && stack.firstOrNull() == SettingsPage.Settings) return
        stack.clear()
        stack += page
    }

    companion object {
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_HOME_PAGE = "home_page"

        fun intent(
            context: Context,
            page: SettingsPage,
            homePage: Int?,
        ): Intent =
            Intent(context, SettingsActivity::class.java)
                .putExtra(EXTRA_PAGE, page.name)
                .apply { if (homePage != null) putExtra(EXTRA_HOME_PAGE, homePage) }
    }
}
