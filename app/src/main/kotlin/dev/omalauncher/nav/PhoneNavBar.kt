package dev.omalauncher.nav

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * The phone's own navigation bar, switched by the launcher so the phone never ends up with neither buttons
 * nor working gestures: its buttons are hidden only while [NavigationService] draws the launcher's gestures,
 * and come back as soon as the service stops or the gestures are turned off.
 *
 * Some phones give their gestures to their own launcher only: with Omalauncher as home, hiding the buttons
 * leaves the phone without any navigation unless the service runs. And Android stops the service (force stop,
 * some reinstalls, the phone's cleaners) without telling it, so [repair] also turns it back on whenever the
 * launcher's process starts.
 *
 * Both need WRITE_SECURE_SETTINGS, which only adb grants ([GRANT_COMMAND]); without it the user switches the
 * bar on Android's page ([GestureNav.openSystemNavigationSettings]) and turns the service on by hand.
 */
object PhoneNavBar {
    private const val TAG = "PhoneNavBar"

    /** Long enough for SystemUI to act on the first write: it only acts on a change it sees. */
    private const val TOGGLE_MS = 600L

    /** How long a service turned on by [repair] has to bind before the buttons come back instead. */
    private const val BIND_TIMEOUT_MS = 4000L

    private val handler = Handler(Looper.getMainLooper())

    /** A write of our own is in flight, including the flip-and-back, so the observer doesn't undo it. */
    private var applying = false

    /** Bumped when a new repair starts, so a flip left over from the previous one doesn't land late. */
    private var generation = 0

    private var watching = false

    fun grantCommand(context: Context) = "adb shell pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"

    /** A phone whose bar this switches: it has its own gesture switch, apart from Android's navigation mode. */
    fun applies(context: Context) = read(context) != null

    /** Granted WRITE_SECURE_SETTINGS (over adb): the launcher may switch the bar and turn its service on itself. */
    fun granted(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Both: [hideButtons], [showButtons] and [repair] do something. */
    fun canSwitch(context: Context) = applies(context) && granted(context)

    /** The launcher's gestures are up: the phone's buttons go, leaving only its thin handle. */
    fun hideButtons(context: Context) = reconcile(context, allowHide = true)

    /**
     * The launcher's gestures are gone: the phone's buttons come back. Also out of the half-way state Android's
     * own navigation page leaves (gestures there, buttons on the phone's switch, neither working): writing the
     * value the switch already has wouldn't reach SystemUI, so it's flipped there and back.
     */
    fun showButtons(context: Context) = reconcile(context, allowHide = false)

    /**
     * Run as the launcher's process starts, and again whenever the home screen comes back: puts the phone's
     * navigation in a state that works. With the launcher's gestures on, their service is turned back on if
     * Android stopped it (the buttons come back if it doesn't bind); with them off, the buttons come back.
     * While the process lives, a change to either setting is repaired the same way, so an app the user is
     * already in doesn't lose both the bar and the swipes.
     */
    fun repair(context: Context) {
        if (!canSwitch(context)) return
        val app = context.applicationContext
        watch(app)
        generation++
        applying = false
        val nav = app.gestureNav
        if (nav.settings.value.gestures && !nav.running.value) {
            if (!serviceEnabled(app)) enableService(app)
            reconcile(app, allowHide = false)
            val gen = generation
            handler.postDelayed({
                if (gen != generation) return@postDelayed
                reconcile(app, allowHide = nav.running.value)
            }, BIND_TIMEOUT_MS)
            return
        }
        reconcile(app, allowHide = true)
    }

    private fun serviceComponent(context: Context) = ComponentName(context, NavigationService::class.java)

    private fun enabledServices(context: Context) =
        Settings.Secure
            .getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty()
            .split(':')
            .filter { it.isNotBlank() }

    private fun serviceEnabled(context: Context): Boolean {
        val self = serviceComponent(context)
        return Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
            enabledServices(context).any { ComponentName.unflattenFromString(it) == self }
    }

    private fun enableService(context: Context) {
        val self = serviceComponent(context)
        val others = enabledServices(context).filter { ComponentName.unflattenFromString(it) != self }
        runCatching {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (others + self.flattenToString()).joinToString(":"),
            )
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.onFailure { Log.w(TAG, "couldn't turn the navigation service back on", it) }
    }

    /** Follows the two settings. Hiding the buttons waits for an explicit [hideButtons]: the phone puts them back on its own. */
    private fun reconcile(
        context: Context,
        allowHide: Boolean,
    ) {
        if (!canSwitch(context) || applying) return
        val app = context.applicationContext
        val nav = app.gestureNav
        // Not this phone's home, and not drawing its own gestures: leave whatever navigation it has.
        val drawing = nav.settings.value.gestures && nav.running.value
        if (!drawing && !defaultHome(app)) return
        val target = barTarget(nav.settings.value.gestures, nav.running.value)
        if (target == BarTarget.Gestures && !allowHide) return
        val plan = barWrite(target, read(app), mode(app)) ?: return
        applying = true
        val gen = generation
        if (plan.toggleFsg) {
            // The switch already holds the value we want, so SystemUI would ignore the write. Flip it first.
            // The mode is written now as well: it's the one that hides the bar, and the phone may set it again
            // while the switch is flipped.
            writeMode(app, plan.mode)
            writeFsg(app, 1 - plan.fsg)
            handler.postDelayed({
                if (gen != generation) return@postDelayed
                writeFsg(app, plan.fsg)
                writeMode(app, plan.mode)
                applying = false
            }, TOGGLE_MS)
        } else {
            writeFsg(app, plan.fsg)
            writeMode(app, plan.mode)
            applying = false
        }
    }

    private fun watch(context: Context) {
        if (watching) return
        watching = true
        val app = context.applicationContext
        val observer =
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    reconcile(app, allowHide = false)
                }
            }
        app.contentResolver.registerContentObserver(Settings.Secure.getUriFor(SystemNavigation.MODE_KEY), false, observer)
        app.contentResolver.registerContentObserver(Settings.Global.getUriFor(SystemNavigation.FULLSCREEN_GESTURES), false, observer)
    }

    /** This app is the home screen, so the phone's own gestures are the ones that don't run. */
    private fun defaultHome(context: Context) =
        runCatching { context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) }.getOrDefault(false)

    private fun read(context: Context): Int? =
        runCatching { Settings.Global.getString(context.contentResolver, SystemNavigation.FULLSCREEN_GESTURES)?.toIntOrNull() }.getOrNull()

    private fun mode(context: Context): Int? =
        runCatching { Settings.Secure.getInt(context.contentResolver, SystemNavigation.MODE_KEY) }.getOrNull()

    private fun writeFsg(
        context: Context,
        value: Int,
    ) {
        runCatching { Settings.Global.putInt(context.contentResolver, SystemNavigation.FULLSCREEN_GESTURES, value) }
            .onFailure { Log.w(TAG, "couldn't switch the phone's navigation bar", it) }
    }

    private fun writeMode(
        context: Context,
        value: Int,
    ) {
        runCatching { Settings.Secure.putInt(context.contentResolver, SystemNavigation.MODE_KEY, value) }
            .onFailure { Log.w(TAG, "couldn't switch the phone's navigation mode", it) }
    }
}

/** Buttons, unless the launcher's own gestures are on and their service is actually drawing them. */
internal enum class BarTarget { Buttons, Gestures }

/** One write of the phone's gesture switch and Android's mode. [toggleFsg] flips the switch away and back so SystemUI notices. */
internal data class BarWrite(
    val fsg: Int,
    val mode: Int,
    val toggleFsg: Boolean,
)

internal fun barTarget(
    gesturesEnabled: Boolean,
    serviceRunning: Boolean,
): BarTarget = if (gesturesEnabled && serviceRunning) BarTarget.Gestures else BarTarget.Buttons

/**
 * What to write so the bar matches [target]. Null where the phone has no gesture switch of its own, or where
 * both settings already agree. Two-button mode is left as it is; the stuck state is Android reporting gestures.
 */
internal fun barWrite(
    target: BarTarget,
    fsg: Int?,
    mode: Int?,
): BarWrite? {
    if (fsg == null) return null
    val wantFsg = if (target == BarTarget.Gestures) 1 else 0
    val wantMode =
        when {
            target == BarTarget.Gestures -> 2
            mode == 1 -> 1
            else -> 0
        }
    val fsgStale = fsg != wantFsg
    val modeStale = mode != null && mode != wantMode
    if (!fsgStale && !modeStale) return null
    return BarWrite(wantFsg, wantMode, toggleFsg = !fsgStale && modeStale)
}
