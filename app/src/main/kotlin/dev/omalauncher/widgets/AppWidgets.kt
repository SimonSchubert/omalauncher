package dev.omalauncher.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.util.DisplayMetrics
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.mapObjects
import org.json.JSONArray
import org.json.JSONObject

/**
 * An Android app widget on the home grid, hosted by [AppWidgets] under its app widget [id].
 * [size] is the cells it starts in; it resizes like any widget, down to [minSize].
 */
data class AppWidgetItem(
    val id: Int,
    override val page: Int,
    val provider: ComponentName,
    override val size: Pair<Int, Int>,
    override val minSize: Pair<Int, Int>,
) : HomeItem {
    override val key get() = "appwidget:$id"
}

/**
 * Android app widgets on the home pages: the host that draws them, and which are where, saved
 * launcher-side next to the grid layout like [Shortcuts]. Only the phone's own profile's widgets
 * are offered; a work profile or private space keeps its own.
 */
class AppWidgets(
    context: Context,
    private val layout: WidgetLayout,
) {
    private val prefs = context.getSharedPreferences("app_widgets", Context.MODE_PRIVATE)
    private val manager = AppWidgetManager.getInstance(context)
    val host = AppWidgetHost(context.applicationContext, HOST_ID)
    val items = mutableStateListOf<AppWidgetItem>()

    init {
        (Versioned.read(prefs.getString(ITEMS, null), VERSION) as? JSONArray)?.let { array -> items += array.mapObjects(::parse) }
    }

    /** The widget's provider as installed now; null once its app is gone. */
    fun info(id: Int): AppWidgetProviderInfo? = manager.getAppWidgetInfo(id)

    /** Widget [id] has a configuration screen that can be opened again after it's set up. */
    fun reconfigurable(id: Int) =
        info(id)?.let { it.configure != null && it.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0 } == true

    /** The widgets the phone's own apps offer. */
    fun providers(): List<AppWidgetProviderInfo> = manager.getInstalledProvidersForProfile(Process.myUserHandle())

    /** The cells [info] asks for at least, before it's resized: its minimum size, rounded up to whole cells. */
    fun cellsFor(
        info: AppWidgetProviderInfo,
        metrics: DisplayMetrics,
    ) = layout.cellsFor(info.minWidth to info.minHeight, metrics)

    /**
     * Puts widget [id], bound and configured, on [page] at the size its provider asks for. It may resize down to
     * its provider's minimum, or not below that size if the provider doesn't resize: the grid resizes every widget.
     */
    fun add(
        id: Int,
        info: AppWidgetProviderInfo,
        page: Int,
        metrics: DisplayMetrics,
    ) {
        val size = cellsFor(info, metrics)
        val min =
            if (info.resizeMode == AppWidgetProviderInfo.RESIZE_NONE) {
                size
            } else {
                // Zero (not set) or larger than the minimum size means it doesn't shrink below that.
                fun least(resize: Int, min: Int) = if (resize in 1..min) resize else min
                layout.cellsFor(least(info.minResizeWidth, info.minWidth) to least(info.minResizeHeight, info.minHeight), metrics)
            }
        items += AppWidgetItem(id, page, info.provider, size, min)
        save()
    }

    fun move(
        key: String,
        page: Int,
    ) {
        val i = items.indexOfFirst { it.key == key }
        if (i < 0) return
        items[i] = items[i].copy(page = page)
        save()
    }

    /** Takes a widget off the home screen and gives its id back to Android. */
    fun remove(key: String) {
        val item = items.firstOrNull { it.key == key } ?: return
        items.remove(item)
        host.deleteAppWidgetId(item.id)
        save()
    }

    /** Gives back ids the host holds that no widget here shows: left by a setup that was cancelled or cut short. */
    fun forgetStrays() {
        val known = items.map { it.id }.toSet()
        for (id in host.appWidgetIds) if (id !in known) host.deleteAppWidgetId(id)
    }

    /** Widgets update while home shows. Some devices throw here when the system is busy; the next start tries again. */
    fun startListening() =
        try {
            host.startListening()
        } catch (e: RuntimeException) {
            Log.w("widgets", "app widget host didn't start listening", e)
        }

    fun stopListening() =
        try {
            host.stopListening()
        } catch (e: RuntimeException) {
            Log.w("widgets", "app widget host didn't stop listening", e)
        }

    private fun parse(o: JSONObject): AppWidgetItem? =
        AppWidgetItem(
            id = o.getInt("id"),
            page = o.optInt("page", 1),
            provider = ComponentName.unflattenFromString(o.getString("provider")) ?: return null,
            size = o.optInt("w", 2) to o.optInt("h", 2),
            minSize = o.optInt("minW", 1) to o.optInt("minH", 1),
        )

    private fun save() {
        val array = JSONArray()
        for (w in items) {
            array.put(
                JSONObject()
                    .put("id", w.id)
                    .put("page", w.page)
                    .put("provider", w.provider.flattenToString())
                    .put("w", w.size.first)
                    .put("h", w.size.second)
                    .put("minW", w.minSize.first)
                    .put("minH", w.minSize.second),
            )
        }
        prefs.edit().putString(ITEMS, Versioned.write(VERSION, array)).apply()
    }

    companion object {
        /** Ours among the app's app widget hosts; Android keeps the widgets bound to it across restarts, so never change it. */
        const val HOST_ID = 1024

        private const val ITEMS = "items"
        private const val VERSION = 1
    }
}
