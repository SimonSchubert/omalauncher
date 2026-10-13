package dev.omalauncher.ui.splitdrag

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import dev.omalauncher.AndroidApp

/**
 * Drags an app out of the launcher for the system to open where it's dropped: split screen, a pop-up window or full
 * screen. The drag carries a PendingIntent the system starts on an unhandled drop (Android 15+); One UI also offers
 * its split-screen zones for it, and there the launcher never takes the drop. Stock Android takes it only at its top
 * and bottom strips, so a drop over the launcher's window anywhere else is the launcher's: it opens the app full
 * screen itself.
 */
object SplitDrag {
    /**
     * Android 15 has the drag. Apps in a work or clone profile are left out: no public way found yet to start them
     * from a PendingIntent of ours.
     */
    @ChecksSdkIntAtLeast(api = 35)
    fun canDrag(app: AndroidApp) = Build.VERSION.SDK_INT >= 35 && app.profile == null

    /** Samsung's One UI, or stock Android. */
    fun platform(): SplitPlatform {
        val oneUi =
            Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                runCatching { Class.forName("com.samsung.android.multiwindow.MultiWindowManager") }.isSuccess
        return if (oneUi) SplitPlatform.OneUi else SplitPlatform.Stock
    }

    /**
     * Starts dragging [app] from [view]; false, with [session] ended, if the system turned the drag down. [open] opens
     * the app full screen, for a drop the launcher takes itself (see [launcherOpensOnDrop]).
     */
    @RequiresApi(35)
    @Suppress("DEPRECATION") // MODE_BACKGROUND_ACTIVITY_START_ALLOWED: the plain "allow" the launcher needs on API 35
    fun start(
        view: View,
        app: AndroidApp,
        icon: Bitmap?,
        session: SplitDragSession,
        open: () -> Unit,
    ): Boolean {
        if (!canDrag(app)) return false
        val context = view.context
        val launch =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // The system starts it after the drop, when we're no longer in front: let it, as the launcher would.
        val options =
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        val pending = PendingIntent.getActivity(context, app.key.hashCode(), launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, options)
        val item = ClipData.Item.Builder().setIntentSender(pending.intentSender).build()
        val clip = ClipData(ClipDescription(app.label, arrayOf(ClipDescription.MIMETYPE_TEXT_INTENT)), item)
        session.begin(app.label, open)
        val started = view.startDragAndDrop(clip, IconShadow(view, icon), null, View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_START_INTENT_SENDER_ON_UNHANDLED_DRAG)
        if (!started) session.end()
        return started
    }

    /** The app's icon under the finger, at a home icon's size. */
    private class IconShadow(
        view: View,
        private val icon: Bitmap?,
    ) : View.DragShadowBuilder(view) {
        private val size = (view.resources.displayMetrics.density * 56).toInt()

        override fun onProvideShadowMetrics(
            outShadowSize: Point,
            outShadowTouchPoint: Point,
        ) {
            outShadowSize.set(size, size)
            outShadowTouchPoint.set(size / 2, size / 2)
        }

        override fun onDrawShadow(canvas: Canvas) {
            icon?.let { canvas.drawBitmap(it, null, Rect(0, 0, size, size), null) }
        }
    }
}
