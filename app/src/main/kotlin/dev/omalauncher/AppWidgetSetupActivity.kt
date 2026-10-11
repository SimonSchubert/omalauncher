package dev.omalauncher

import android.app.Activity
import android.app.ActivityOptions
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Sets up an Android app widget, invisibly over home: allocates an id, binds it to the provider (Android
 * asks the user the first time), runs the widget's configuration if it needs one, and puts the widget on
 * its home page. Cancelled or failed, it gives the id back to Android instead. Started with an id, it only
 * reconfigures that widget. Its own activity because both steps answer through onActivityResult, and
 * AppWidgetHost.startAppWidgetConfigureActivityForResult wants an Activity for that; it adds the widget
 * itself, so nothing waits for its result in a screen that may be gone by then.
 */
class AppWidgetSetupActivity : Activity() {
    private val widgets get() = (application as App).runtime.widgets.appWidgets
    private val manager by lazy { AppWidgetManager.getInstance(this) }
    private var id = AppWidgetManager.INVALID_APPWIDGET_ID

    /** The id was allocated here, so it's ours to give back if setup doesn't finish. */
    private var fresh = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated mid-setup: the step under way answers in onActivityResult.
        if (savedInstanceState != null) {
            id = savedInstanceState.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            fresh = savedInstanceState.getBoolean(FRESH)
            return
        }
        val existing = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        if (existing != AppWidgetManager.INVALID_APPWIDGET_ID) {
            id = existing
            configure(always = true)
            return
        }
        val provider = intent.getStringExtra(PROVIDER)?.let(ComponentName::unflattenFromString) ?: return cancel()
        id = widgets.host.allocateAppWidgetId()
        fresh = true
        if (manager.bindAppWidgetIdIfAllowed(id, provider)) {
            configure(always = false)
        } else {
            val ask =
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
            startActivityForResult(ask, BIND)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        outState.putBoolean(FRESH, fresh)
    }

    /**
     * Runs the widget's configuration activity, if it has one. A new widget skips it when the widget says
     * it's optional and can be done later (configuration_optional and reconfigurable); [always] for later.
     */
    private fun configure(always: Boolean) {
        val info = manager.getAppWidgetInfo(id) ?: return cancel()
        val later = AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL or AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE
        if (info.configure == null || (!always && info.widgetFeatures and later == later)) return done()
        try {
            widgets.host.startAppWidgetConfigureActivityForResult(this, id, 0, CONFIGURE, startOptions())
        } catch (e: ActivityNotFoundException) {
            cancel()
        }
    }

    /** Lets the widget's app start its configuration from our request, which Android 14 on wants said outright. */
    private fun startOptions(): Bundle? {
        val mode =
            when {
                Build.VERSION.SDK_INT >= 36 -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                Build.VERSION.SDK_INT >= 34 -> @Suppress("DEPRECATION") ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                else -> return null
            }
        return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        when {
            resultCode != RESULT_OK -> cancel()
            requestCode == BIND -> configure(always = false)
            else -> done()
        }
    }

    private fun done() {
        if (fresh) {
            // Gone meanwhile (its app uninstalled): nothing to show, so the id goes back too.
            val info = manager.getAppWidgetInfo(id)
            if (info != null) widgets.add(id, info, intent.getIntExtra(PAGE, 1), resources.displayMetrics) else widgets.host.deleteAppWidgetId(id)
        }
        fresh = false
        finish()
    }

    /** A new widget gives its id back; one being reconfigured stays as it was. */
    private fun cancel() {
        if (fresh) widgets.host.deleteAppWidgetId(id)
        fresh = false
        finish()
    }

    companion object {
        private const val PROVIDER = "provider"
        private const val PAGE = "page"
        private const val FRESH = "fresh"
        private const val BIND = 1
        private const val CONFIGURE = 2

        /** Sets up a new widget from [provider] and puts it on home [page]. */
        fun add(
            context: Context,
            provider: ComponentName,
            page: Int,
        ) = Intent(context, AppWidgetSetupActivity::class.java).putExtra(PROVIDER, provider.flattenToString()).putExtra(PAGE, page)

        /** Runs widget [id]'s configuration again. */
        fun reconfigure(
            context: Context,
            id: Int,
        ) = Intent(context, AppWidgetSetupActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
    }
}
