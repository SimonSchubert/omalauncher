package dev.omalauncher

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Puts one of the phone's apps' widgets on home: binds it to a new id, which the first time takes Android's "allow
 * omalauncher to create widgets?", then shows the widget's own settings if it has any to set first. Cancelled at
 * either step, the id is freed and nothing changes. No window of its own; its own task, as [PinShortcutActivity].
 */
class AddWidgetActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime
    private val catalog get() = runtime.appWidgets
    private var id = NO_ID
    private var page = 1
    private var info: AppWidgetProviderInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = intent.getIntExtra(PAGE, 1)
        @Suppress("DEPRECATION")
        val provider = intent.getParcelableExtra<ComponentName>(PROVIDER)
        info = provider?.let(catalog::provider)
        val info = info
        if (info == null) {
            finish()
            return
        }
        // Back from Android's question or the widget's settings after this activity was recreated: the id is still ours.
        id = savedInstanceState?.getInt(ID, NO_ID) ?: NO_ID
        if (id != NO_ID) return
        id = catalog.allocate()
        if (catalog.bind(id, info)) {
            configure(info)
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(catalog.bindIntent(id, info), BIND)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(ID, id)
    }

    @Deprecated("The widget host starts the settings for a result through the activity, not a contract")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val info = info
        if (info == null || id == NO_ID) return finish()
        if (resultCode != RESULT_OK) return cancel()
        when (requestCode) {
            BIND -> configure(info)
            CONFIGURE -> add(info)
        }
    }

    private fun configure(info: AppWidgetProviderInfo) {
        if (!catalog.needsConfiguring(info)) return add(info)
        // Settings that won't open: the widget goes on home as it is, as stock launchers do.
        if (!catalog.configure(this, id, CONFIGURE)) add(info)
    }

    private fun add(info: AppWidgetProviderInfo) {
        val widgets = runtime.widgets
        widgets.androidWidgets.add(catalog.item(id, info, page, widgets.layout))
        id = NO_ID
        finish()
    }

    private fun cancel() {
        catalog.delete(id)
        id = NO_ID
        finish()
    }

    companion object {
        private const val PROVIDER = "provider"
        private const val PAGE = "page"
        private const val ID = "id"
        private const val NO_ID = -1
        private const val BIND = 1
        private const val CONFIGURE = 2

        /** Adds [provider]'s widget to home [page]. */
        fun start(
            context: Context,
            provider: ComponentName,
            page: Int,
        ) = context.startActivity(
            Intent(context, AddWidgetActivity::class.java)
                .putExtra(PROVIDER, provider)
                .putExtra(PAGE, page)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
