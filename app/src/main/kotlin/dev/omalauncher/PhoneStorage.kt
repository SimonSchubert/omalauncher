package dev.omalauncher

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.RequiresApi
import java.io.File

/**
 * The phone's shared storage (Download, DCIM, Pictures, Documents, …) for Linux, bound at [GUEST] once Android
 * allows the launcher all-files access (MANAGE_EXTERNAL_STORAGE). Nothing asks for it on its own: it's turned on
 * and off from the Linux screen, on Android's own page for it. Android 10 has no such page and goes without.
 */
object PhoneStorage {
    /** Where Linux sees it, as Termux and Android's own shell do: a guest path, not Android's. */
    @SuppressLint("SdCardPath")
    const val GUEST = "/sdcard"

    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Whether Linux sees it: Android allows all files. Can change while the launcher is away, in Android's settings. */
    fun granted(): Boolean = (supported() && Environment.isExternalStorageManager()).also { asked = Asked(it, SystemClock.elapsedRealtime()) }

    /**
     * [granted] as Android said it within the last second: for paths looked up one by one (a Files folder, a widget's
     * images), as asking is a call into another process each time.
     */
    fun grantedLately(): Boolean {
        val last = asked
        return if (last != null && SystemClock.elapsedRealtime() - last.at < LATELY_MS) last.granted else granted()
    }

    private class Asked(
        val granted: Boolean,
        val at: Long,
    )

    @Volatile private var asked: Asked? = null

    private const val LATELY_MS = 1_000L

    /** The storage itself, /storage/emulated/0 for the phone's owner. */
    val root: File get() = Environment.getExternalStorageDirectory()

    /** Opens Android's switch for it, the launcher's own; failing that (some ROMs), the list of every app's. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun openSettings(context: Context) {
        val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null))
        val all = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        for (intent in listOf(own, all)) {
            try {
                return context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }
}
