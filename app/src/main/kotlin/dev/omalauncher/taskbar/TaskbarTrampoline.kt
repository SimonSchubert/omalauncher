package dev.omalauncher.taskbar

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.omalauncher.AndroidApp
import dev.omalauncher.ui.splitdrag.LaunchStep
import kotlin.math.roundToInt

/**
 * An invisible activity the taskbar opens apps through. LAUNCH_ADJACENT needs an activity to start from, and the
 * taskbar's window isn't one; this one starts each step in turn, then leaves. It's started once per drop and must not
 * repeat on being recreated (it may land in a split half): see the manifest's configChanges and the check below.
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
        // Relayed: started from a fresh trampoline, which lands in the half in front now. LAUNCH_ADJACENT puts an app in
        // the half other than its source's, so this makes the app replace the half that wasn't just brought forward.
        if (step.getBoolean(RELAY)) {
            step.remove(RELAY)
            step.putLong(DELAY, RELAY_DELAY_MS)
            launch(this, arrayListOf(step))
            return
        }
        val component = step.getParcelable<ComponentName>(COMPONENT) ?: return
        val adjacent = if (step.getBoolean(ADJACENT)) Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT else 0
        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or adjacent)
        val options = step.getParcelable<Rect>(BOUNDS)?.let { ActivityOptions.makeBasic().setLaunchBounds(it).toBundle() }
        runCatching { startActivity(intent, options) }.onFailure { Log.w(TAG, "couldn't open ${component.flattenToShortString()}", it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STARTED, true)
    }

    companion object {
        private const val TAG = "TaskbarTrampoline"
        private const val EXTRA_STEPS = "steps"
        private const val COMPONENT = "component"
        private const val ADJACENT = "adjacent"
        private const val BOUNDS = "bounds"
        private const val DELAY = "delay"
        private const val STARTED = "started"
        private const val RELAY = "relay"

        /** How long a relayed trampoline waits before its start, to have settled in its half. */
        private const val RELAY_DELAY_MS = 250L

        /**
         * Opens [steps] in order from a new trampoline. With [relayLast] the last step is started from a second trampoline,
         * opened once the steps before it are done: replacing a half of a split, where the first step brings the half to
         * keep forward and the second trampoline lands in it.
         */
        fun start(
            context: Context,
            steps: List<LaunchStep<AndroidApp>>,
            relayLast: Boolean = false,
        ) {
            val bundles =
                steps.mapTo(ArrayList()) { s ->
                    Bundle().apply {
                        putParcelable(COMPONENT, s.app.component)
                        putBoolean(ADJACENT, s.adjacent)
                        s.bounds?.let { putParcelable(BOUNDS, Rect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt())) }
                        putLong(DELAY, s.delayMs)
                    }
                }
            if (relayLast) bundles.lastOrNull()?.putBoolean(RELAY, true)
            launch(context, bundles)
        }

        private fun launch(
            context: Context,
            bundles: ArrayList<Bundle>,
        ) {
            val intent =
                Intent(context, TaskbarTrampoline::class.java)
                    // A task of its own every time: a relayed trampoline would otherwise just bring the first one's back.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putParcelableArrayListExtra(EXTRA_STEPS, bundles)
            runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "couldn't start the trampoline", it) }
        }
    }
}
