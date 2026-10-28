package dev.omalauncher.linux

import android.app.UiModeManager
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Public Android accessibility preferences, observed only while the guest is in use.
 * Older Android releases have no public UI contrast API: export no preference
 * there rather than reading hidden secure settings or inventing an equivalent. */
internal class PortalAccessibility(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val state = MutableStateFlow(PortalAppearance.Preferences())
    val preferences = state.asStateFlow()
    private var current: Any? = null
    private var observer: ContentObserver? = null
    private var closeContrast: (() -> Unit)? = null

    @MainThread
    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (current != null) return
        val epoch = Any()
        current = epoch
        val changes = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                if (current === epoch) refresh()
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, changes,
            )
            observer = changes
        }.onFailure { Log.w(TAG, "cannot observe animation preference", it) }
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                closeContrast = Contrast.observe(context) { if (current === epoch) refresh() }
            }.onFailure { Log.w(TAG, "cannot observe contrast preference", it) }
        }
        // Register first, then read: a concurrent settings update cannot be lost
        // between the initial snapshot and listener registration.
        refresh()
    }

    @MainThread
    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        current = null // Already queued callbacks cannot publish into a replacement guest.
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) }
            .onFailure { failure -> Log.w(TAG, "cannot release animation observer", failure) } }
        observer = null
        closeContrast?.let { runCatching(it).onFailure { failure -> Log.w(TAG, "cannot release contrast observer", failure) } }
        closeContrast = null
        state.value = PortalAppearance.Preferences()
    }

    private fun refresh() {
        val contrast = if (Build.VERSION.SDK_INT >= 34) runCatching { Contrast.read(context) }.getOrNull() else null
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        }.getOrNull()
        state.value = PortalAppearance.preferences(contrast, scale)
    }

    /** Keep post-minSdk callback types out of the older-release class path. */
    @RequiresApi(34)
    private object Contrast {
        fun read(context: Context): Float? = context.getSystemService(UiModeManager::class.java)?.contrast

        fun observe(context: Context, changed: () -> Unit): (() -> Unit)? {
            val manager = context.getSystemService(UiModeManager::class.java) ?: return null
            val listener = UiModeManager.ContrastChangeListener { changed() }
            manager.addContrastChangeListener(context.mainExecutor, listener)
            return { manager.removeContrastChangeListener(listener) }
        }
    }

    companion object { private const val TAG = "PortalAccessibility" }
}
