package dev.omalauncher.ui.splitdrag

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.omalauncher.AndroidApp
import kotlin.math.roundToInt

/** How an app dropped in a zone opens; [A] is the app, generic so the decision is testable without Android. */
sealed interface OpenPlan<out A> {
    /** As a tap on it in the strip does: full screen, counted as used. */
    data class AsTapped<A>(val app: A) : OpenPlan<A>

    /** In a pop-up window at [bounds]. */
    data class Windowed<A>(
        val app: A,
        val bounds: ZoneBox,
    ) : OpenPlan<A>

    /**
     * Split screen: [first] opens full screen, then [adjacent] next to it, which the system puts on its default side
     * (bottom, or right when wide); [first] keeps the other.
     */
    data class Split<A>(
        val first: A,
        val adjacent: A,
    ) : OpenPlan<A>
}

/**
 * What dropping [dragged] in [zone] opens. Splitting needs a [partner], another app, for the other half: the dragged one
 * goes first for the top or left half, after the partner for the bottom or right one. Null when there's no partner.
 */
fun <A> planOpen(
    zone: DropZone,
    dragged: A,
    partner: A?,
): OpenPlan<A>? =
    when (zone.kind) {
        ZoneKind.FullScreen -> OpenPlan.AsTapped(dragged)
        ZoneKind.PopUp -> OpenPlan.Windowed(dragged, zone.preview)
        ZoneKind.SplitTop, ZoneKind.SplitLeft -> partner?.let { OpenPlan.Split(dragged, it) }
        ZoneKind.SplitBottom, ZoneKind.SplitRight -> partner?.let { OpenPlan.Split(it, dragged) }
    }

/** Carries out an [OpenPlan] from the launcher's activity. */
object SplitOpen {
    /** How long the second app of a split waits, so the first is in front to be put next to. */
    private const val ADJACENT_DELAY_MS = 450L
    private const val TAG = "SplitOpen"
    private val handler = Handler(Looper.getMainLooper())

    fun run(
        context: Context,
        plan: OpenPlan<AndroidApp>,
        asTapped: () -> Unit,
    ) {
        when (plan) {
            is OpenPlan.AsTapped -> asTapped()
            is OpenPlan.Windowed -> context.activity()?.let { start(it, plan.app, ActivityOptions.makeBasic().setLaunchBounds(plan.bounds.toRect()).toBundle()) }
            is OpenPlan.Split -> {
                val activity = context.activity() ?: return
                start(activity, plan.first, null)
                // A newer drop replaces a second app still waiting from an earlier one.
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ start(activity, plan.adjacent, null, Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT) }, ADJACENT_DELAY_MS)
            }
        }
    }

    private fun start(
        activity: Activity,
        app: AndroidApp,
        options: Bundle?,
        flags: Int = 0,
    ) {
        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or flags)
        runCatching { activity.startActivity(intent, options) }.onFailure { Log.w(TAG, "couldn't open ${app.component.flattenToShortString()}", it) }
    }

    private fun ZoneBox.toRect() = Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

    private tailrec fun Context.activity(): Activity? = this as? Activity ?: (this as? ContextWrapper)?.baseContext?.activity()
}
