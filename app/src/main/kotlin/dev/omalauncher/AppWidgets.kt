package dev.omalauncher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.util.Log
import android.util.SizeF
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.omalauncher.widgets.AndroidWidget
import dev.omalauncher.widgets.WidgetLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * The phone's apps' widgets (app widgets: a clock, the weather, a calendar) and the host that shows them on home.
 * Only apps in the launcher's own profile offer theirs: a work profile's widgets would need its own consent and
 * badging. The list follows apps being installed, updated and removed, like the drawer's.
 */
class AppWidgetCatalog(
    private val context: Context,
) {
    private val manager = AppWidgetManager.getInstance(context)
    private val host = AppWidgetHost(context, HOST_ID)
    private val me = Process.myUserHandle()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** What the picker lists: every home screen widget of the launcher's own profile's apps. */
    var providers by mutableStateOf(emptyList<AppWidgetProviderInfo>())
        private set

    /** Packages that offer a widget; the drawer's long-press menu offers "widgets" for these. */
    val packages by derivedStateOf { providers.map { it.provider.packageName }.toSet() }

    /** Whether [app] has widgets to add: one of the launcher's own profile's apps that offers any. */
    fun offers(app: AndroidApp) = app.profile == null && app.component.packageName in packages

    fun start() {
        context.getSystemService(LauncherApps::class.java).registerCallback(
            object : LauncherApps.Callback() {
                override fun onPackageAdded(packageName: String, user: UserHandle) = changed(user)

                override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(user)

                override fun onPackageChanged(packageName: String, user: UserHandle) = changed(user)

                override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)

                override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)
            },
        )
        reload()
    }

    private fun changed(user: UserHandle) {
        if (user == me) reload()
    }

    private fun reload() {
        scope.launch {
            providers =
                withContext(Dispatchers.IO) {
                    runCatching { manager.getInstalledProvidersForProfile(me) }
                        .onFailure { Log.w(TAG, "cannot list widgets", it) }
                        .getOrDefault(emptyList())
                        .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 && it.provider.packageName != context.packageName }
                }
        }
    }

    /** Frees ids Android bound for home that [keep] doesn't hold: widgets whose adding was cut off. */
    fun forgetAllBut(keep: Set<Int>) {
        runCatching { host.appWidgetIds.filter { it !in keep }.forEach(host::deleteAppWidgetId) }
    }

    /** Widgets get their updates while home is started; Android says to stop when it isn't. */
    fun startListening() {
        runCatching { host.startListening() }.onFailure { Log.w(TAG, "cannot listen for widget updates", it) }
    }

    fun stopListening() {
        runCatching { host.stopListening() }
    }

    /** What Android says [id] is now; null once its app is gone. */
    fun info(id: Int): AppWidgetProviderInfo? = runCatching { manager.getAppWidgetInfo(id) }.getOrNull()

    fun provider(component: ComponentName) = providers.firstOrNull { it.provider == component }

    fun allocate() = host.allocateAppWidgetId()

    fun delete(id: Int) {
        runCatching { host.deleteAppWidgetId(id) }
    }

    /** Binds [id] to [info] if the user allowed this launcher to add widgets before; otherwise [bindIntent] asks. */
    fun bind(
        id: Int,
        info: AppWidgetProviderInfo,
    ): Boolean = runCatching { manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null) }.getOrDefault(false)

    /** Android's own "allow omalauncher to add widgets?" for [id]. */
    fun bindIntent(
        id: Int,
        info: AppWidgetProviderInfo,
    ) = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)

    /** Whether [info] wants its settings shown before it goes on home; one that says they can wait (Android 12) doesn't. */
    fun needsConfiguring(info: AppWidgetProviderInfo) =
        info.configure != null &&
            (android.os.Build.VERSION.SDK_INT < 31 || info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL == 0)

    /** Whether [info]'s settings can be opened again once it's on home. */
    fun reconfigurable(info: AppWidgetProviderInfo) = info.configure != null && info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0

    /** Opens [id]'s settings in its app; false when they couldn't be. */
    fun configure(
        activity: Activity,
        id: Int,
        requestCode: Int,
    ): Boolean =
        runCatching { host.startAppWidgetConfigureActivityForResult(activity, id, 0, requestCode, null) }
            .onFailure { Log.w(TAG, "cannot configure widget $id", it) }
            .isSuccess

    /** A view of [id], live while home listens. Each call makes a new one, and the newest gets the updates. */
    fun createView(
        context: Context,
        id: Int,
        info: AppWidgetProviderInfo,
    ): AppWidgetHostView = host.createView(context, id, info)

    /** What the picker shows for [info]: its preview, or its icon when it has none. */
    suspend fun preview(
        info: AppWidgetProviderInfo,
        maxPx: Int,
    ): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val density = context.resources.displayMetrics.densityDpi
            runCatching { (info.loadPreviewImage(context, density) ?: info.loadIcon(context, density))?.let { bitmap(it, maxPx) } }.getOrNull()
        }

    private fun bitmap(
        d: Drawable,
        maxPx: Int,
    ): ImageBitmap {
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: maxPx
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: maxPx
        val scale = minOf(1f, maxPx.toFloat() / maxOf(w, h))
        val bmp = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, bmp.width, bmp.height)
        d.draw(Canvas(bmp))
        return bmp.asImageBitmap()
    }

    /**
     * [info] as a home tile on [page], its size in cells from what it asks for in pixels: the cells it takes on home's
     * grid now, at least one each way, no wider than the grid. On its side in landscape, as home's tiles are.
     */
    fun item(
        id: Int,
        info: AppWidgetProviderInfo,
        page: Int,
        layout: WidgetLayout,
    ): AndroidWidget {
        val metrics = context.resources.displayMetrics
        val (cellW, cellH, gap) =
            layout.geometry ?: run {
                val gap = Math.round(6 * metrics.density)
                val cell = (metrics.widthPixels - gap) / layout.columns.toFloat()
                Triple(cell, cell, gap)
            }
        fun span(
            px: Int,
            cell: Float,
        ) = ceil((px + gap) / cell).toInt().coerceAtLeast(1)
        // Across and down as drawn; the grid's own columns run down the screen in landscape.
        fun cells(
            across: Int,
            down: Int,
        ): Pair<Int, Int> {
            val (w, h) = if (layout.turned) span(down, cellH) to span(across, cellW) else span(across, cellW) to span(down, cellH)
            return w.coerceAtMost(layout.columns) to h
        }
        val size = cells(info.minWidth, info.minHeight)
        val resizes = info.resizeMode
        val min =
            cells(
                if (resizes and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0 && info.minResizeWidth > 0) info.minResizeWidth else info.minWidth,
                if (resizes and AppWidgetProviderInfo.RESIZE_VERTICAL != 0 && info.minResizeHeight > 0) info.minResizeHeight else info.minHeight,
            )
        return AndroidWidget(id, page, info.provider, label(info), size, minOf(min.first, size.first) to minOf(min.second, size.second))
    }

    fun label(info: AppWidgetProviderInfo): String = runCatching { info.loadLabel(context.packageManager) }.getOrNull() ?: info.provider.shortClassName

    companion object {
        private const val TAG = "AppWidgets"

        /** The launcher's one widget host; Android keeps its ids across restarts. */
        private const val HOST_ID = 1024
    }
}

/** Tells [view] it's [width] by [height] dp, so its app can pick a layout for that size. */
fun sizeWidget(
    view: AppWidgetHostView,
    width: Float,
    height: Float,
) {
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        view.updateAppWidgetSize(Bundle(), listOf(SizeF(width, height)))
    } else {
        @Suppress("DEPRECATION")
        view.updateAppWidgetSize(null, width.toInt(), height.toInt(), width.toInt(), height.toInt())
    }
}
