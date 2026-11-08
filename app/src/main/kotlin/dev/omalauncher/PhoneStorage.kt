package dev.omalauncher

import android.annotation.SuppressLint
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * The phone's shared storage (Download, Documents, Pictures, …) for Linux, bound at [GUEST], with no permission:
 * Android shows the launcher, and so Linux, only the files it made there itself, and lets it make them only where
 * they belong (any file in Download and Documents, pictures in Pictures and DCIM, …). Folders of anyone's files come
 * through [PhoneFolders] instead. Android 10 gives an app without the storage permission no path into it.
 */
object PhoneStorage {
    /** Where Linux sees it, as Termux and Android's own shell do: a guest path, not Android's. */
    @SuppressLint("SdCardPath")
    const val GUEST = "/sdcard"

    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** The storage itself, /storage/emulated/0 for the phone's owner. */
    val root: File get() = Environment.getExternalStorageDirectory()
}
