package dev.omalauncher.taskbar

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.window.SplashScreen
import dev.omalauncher.AndroidApp
import dev.omalauncher.LinuxWindowActivity
import dev.omalauncher.ui.splitdrag.LaunchStep
import dev.omalauncher.ui.splitdrag.ZoneBox
import kotlin.math.roundToInt

/**
 * One thing the trampoline does, [delayMs] after the step before. [A] is an app and [I] an intent, generic so the steps
 * are testable without Android.
 */
sealed interface TrampolineStep<out A, out I> {
    val delayMs: Long

    /** Opens [app], next to the app in front when [adjacent]. */
    data class Open<A>(
        val app: A,
        val adjacent: Boolean,
        override val delayMs: Long,
    ) : TrampolineStep<A, Nothing>

    /** Starts [intent] as it is, next to the app in front when [adjacent]. */
    data class Start<I>(
        val intent: I,
        val adjacent: Boolean,
        override val delayMs: Long,
    ) : TrampolineStep<Nothing, I>

    /** Brings this launcher's task [taskId] to the front. */
    data class Front(
        val taskId: Int,
        override val delayMs: Long,
    ) : TrampolineStep<Nothing, Nothing>

    /** Closes this launcher's task [taskId], and takes it out of Recents. */
    data class Remove(
        val taskId: Int,
        override val delayMs: Long,
    ) : TrampolineStep<Nothing, Nothing>
}

/** A split's [LaunchStep] as a trampoline step. */
fun <A> LaunchStep<A>.asStep(): TrampolineStep<A, Nothing> = TrampolineStep.Open(app, adjacent, delayMs)

/** Whether the middle of a view at [left], [top] sized [width]×[height] (screen pixels) is inside [half]. */
internal fun landsIn(
    half: ZoneBox,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
): Boolean {
    val x = left + width / 2
    val y = top + height / 2
    return x >= half.left && x < half.right && y >= half.top && y < half.bottom
}

/**
 * An invisible activity the taskbar opens apps through. LAUNCH_ADJACENT needs an activity to start from, and the
 * taskbar's window isn't one; this one starts each step in turn, then leaves. It's started once per drop and must not
 * repeat on being recreated (it may land in a split half): see the manifest's configChanges and the check below. A
 * recreated one drops the steps still to come; a Linux window's old task may then stay behind, harmlessly.
 */
class TaskbarTrampoline : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        // The untyped getters: the typed ones are API 33, the app's floor is 29 (the taskbar itself needs 35).
        @Suppress("DEPRECATION")
        val steps = intent.getParcelableArrayListExtra<Bundle>(EXTRA_STEPS).orEmpty()
        var at = 0L
        for (step in steps) {
            at += step.getLong(DELAY)
            handler.postDelayed({ start(step) }, at)
        }
        handler.postDelayed({ finish() }, at + 1000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun start(step: Bundle) {
        when {
            step.containsKey(FRONT) -> ownTask(step.getInt(FRONT))?.let { runCatching { it.moveToFront() }.onFailure { e -> Log.w(TAG, "couldn't bring a task forward", e) } }
            step.containsKey(REMOVE) -> remove(step.getInt(REMOVE))
            step.containsKey(INTENT) -> {
                val intent = Intent(step.getParcelable<Intent>(INTENT) ?: return).addFlags(adjacentFlag(step))
                // No opening animation and a splash in the window's own colour (see the theme): a Linux window's activity,
                // which shows a window already there or just made.
                val options = ActivityOptions.makeCustomAnimation(this, 0, 0)
                if (Build.VERSION.SDK_INT >= 33) options.setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR)
                runCatching { startActivity(intent, options.toBundle()) }.onFailure { Log.w(TAG, "couldn't start ${intent.data ?: intent.component}", it) }
            }
            else -> open(step)
        }
    }

    @Suppress("DEPRECATION")
    private fun open(step: Bundle) {
        val component = step.getParcelable<ComponentName>(COMPONENT) ?: return
        val adjacent = adjacentFlag(step)
        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or adjacent)
        runCatching { startActivity(intent) }.onFailure { Log.w(TAG, "couldn't open ${component.flattenToShortString()}", it) }
    }

    /** LAUNCH_ADJACENT for a [step] that opens next to the app in front, or that replaces a half and sits in the other one. */
    @Suppress("DEPRECATION")
    private fun adjacentFlag(step: Bundle) =
        if (step.getBoolean(ADJACENT) || (step.getBoolean(REPLACE) && !inHalf(step.getParcelable<Rect>(TARGET)))) Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT else 0

    /** This launcher's task [id], if it's still there. */
    private fun ownTask(id: Int): ActivityManager.AppTask? = getSystemService(ActivityManager::class.java).appTasks.firstOrNull { runCatching { it.taskInfo?.taskId }.getOrNull() == id }

    /**
     * Closes task [id] where it is, behind: nothing comes to the front, so nothing animates. A Linux window's task whose
     * activity still owns the window stays, unless it hands the window over within [KEPT_TRIES] looks [KEPT_MS] apart.
     */
    private fun remove(
        id: Int,
        tries: Int = KEPT_TRIES,
    ) {
        if (LinuxWindowActivity.keeps(id)) {
            // The new activity may not have taken the window over yet: looked at again a little later.
            if (tries > 0) handler.postDelayed({ remove(id, tries - 1) }, KEPT_MS) else Log.w(TAG, "a Linux window's old task kept its window; left open")
            return
        }
        ownTask(id)?.let { runCatching { it.finishAndRemoveTask() }.onFailure { e -> Log.w(TAG, "couldn't close a task", e) } }
    }

    /** Whether this trampoline sits in [half] (screen pixels): its middle is inside it. */
    private fun inHalf(half: Rect?): Boolean {
        half ?: return false
        val view = window.decorView
        val at = IntArray(2).also(view::getLocationOnScreen)
        return landsIn(ZoneBox(half.left.toFloat(), half.top.toFloat(), half.right.toFloat(), half.bottom.toFloat()), at[0].toFloat(), at[1].toFloat(), view.width.toFloat(), view.height.toFloat())
    }

    companion object {
        private const val TAG = "TaskbarTrampoline"
        private const val EXTRA_STEPS = "steps"
        private const val COMPONENT = "component"
        private const val ADJACENT = "adjacent"
        private const val DELAY = "delay"
        private const val REPLACE = "replace"
        private const val TARGET = "target"
        private const val INTENT = "intent"
        private const val FRONT = "front"
        private const val REMOVE = "remove"

        /** How long until a Linux window's old task that still owns its window is looked at again. */
        private const val KEPT_MS = 300L

        /** How many more times a Linux window's old task that still owns its window is looked at, within the trampoline's last second. */
        private const val KEPT_TRIES = 2

        /** How long a replacing trampoline waits to have settled in its half before looking where it is. */
        private const val SETTLE_MS = 200L

        /**
         * Carries out [steps] in order from a new trampoline.
         */
        fun start(
            context: Context,
            steps: List<TrampolineStep<AndroidApp, Intent>>,
        ) {
            val bundles =
                steps.mapTo(ArrayList()) { s ->
                    Bundle().apply {
                        when (s) {
                            is TrampolineStep.Open -> {
                                putParcelable(COMPONENT, s.app.component)
                                putBoolean(ADJACENT, s.adjacent)
                            }
                            is TrampolineStep.Start -> {
                                putParcelable(INTENT, s.intent)
                                putBoolean(ADJACENT, s.adjacent)
                            }
                            is TrampolineStep.Front -> putInt(FRONT, s.taskId)
                            is TrampolineStep.Remove -> putInt(REMOVE, s.taskId)
                        }
                        putLong(DELAY, s.delayMs)
                    }
                }
            launch(context, bundles)
        }

        /**
         * Opens [app] in the split half at [target] (screen pixels), replacing what's there. A new trampoline lands in one
         * of the halves, which one is up to the system (One UI: the bottom or right one, whatever is focused). Once it's
         * there it looks where it is: in the target half it opens the app plainly, which keeps it in that half; in the
         * other half it opens it LAUNCH_ADJACENT, which puts it in the half other than its source's.
         */
        fun replace(
            context: Context,
            app: AndroidApp,
            target: ZoneBox,
        ) {
            launch(context, arrayListOf(replaceStep(target) { putParcelable(COMPONENT, app.component) }))
        }

        /**
         * Starts [intent] (a Linux window's, in a new task) in the split half at [target], as [replace] opens an app.
         * [leaving] is the window's old task, if it had one: it goes once the new activity has taken the window over.
         */
        fun replace(
            context: Context,
            intent: Intent,
            target: ZoneBox,
            leaving: Int?,
        ) {
            val steps = arrayListOf(replaceStep(target) { putParcelable(INTENT, intent) })
            if (leaving != null) {
                steps +=
                    Bundle().apply {
                        putInt(REMOVE, leaving)
                        putLong(DELAY, HANDOVER_DELAY_MS)
                    }
            }
            launch(context, steps)
        }

        /** A step that opens what [what] puts in it in the half at [target], replacing what's there. */
        private fun replaceStep(
            target: ZoneBox,
            what: Bundle.() -> Unit,
        ) = Bundle().apply {
            what()
            putBoolean(REPLACE, true)
            putParcelable(TARGET, Rect(target.left.roundToInt(), target.top.roundToInt(), target.right.roundToInt(), target.bottom.roundToInt()))
            putLong(DELAY, SETTLE_MS)
        }

        private fun launch(
            context: Context,
            bundles: ArrayList<Bundle>,
        ) {
            val intent =
                Intent(context, TaskbarTrampoline::class.java)
                    // A task of its own every time: a trampoline started while an earlier one still lingers would otherwise just bring that one back, without running its steps.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putParcelableArrayListExtra(EXTRA_STEPS, bundles)
            runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "couldn't start the trampoline", it) }
        }
    }
}
