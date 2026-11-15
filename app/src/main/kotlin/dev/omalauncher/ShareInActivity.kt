package dev.omalauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dev.omalauncher.linux.Proot
import dev.omalauncher.system.AppLanguage
import dev.omalauncher.system.postNotification
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The Linux destination in Android's share sheet: another app's files, or the text it shared, are copied into the
 * guest's ~/Downloads (the same folder `omalauncher pick` uses too). A small card shows while they're copied; Back stops the
 * copy and leaves nothing behind.
 */
class ShareInActivity : ComponentActivity() {
    /** In the language picked in settings. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        AppLanguage.applyTo(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Brought back after the process was gone: what was shared went with it.
        if (savedInstanceState != null) return finish()
        val runtime = (application as? App)?.runtime ?: return finish()
        if (!runtime.bootstrap.wanted) {
            Toast.makeText(this, R.string.share_in_no_linux, Toast.LENGTH_LONG).show()
            return finish()
        }
        val uris = sharedUris(intent)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.takeIf { uris.isEmpty() && it.isNotBlank() }
        if (uris.isEmpty() && text == null) {
            Toast.makeText(this, R.string.share_in_failed, Toast.LENGTH_LONG).show()
            return finish()
        }
        setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(
                    stringResource(R.string.share_in_saving),
                    modifier =
                        Modifier
                            .clip(LauncherStyle.shape(12.dp))
                            .background(Palette.overlay)
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    style = Mono,
                )
            }
        }
        lifecycleScope.launch {
            val saved =
                try {
                    withContext(Dispatchers.IO) {
                        val dir =
                            runtime.proot.guestFile(Proot.GUEST_DOWNLOADS)?.takeIf { it.isDirectory || it.mkdirs() }
                                ?: throw IOException("no ${Proot.GUEST_DOWNLOADS}")
                        if (text != null) {
                            listOf(runtime.phoneFolders.writeShared(text, dir))
                        } else {
                            // Back, or the launcher closing, stops the copy: the half file goes with it.
                            uris.map { runtime.phoneFolders.copyIn(it, dir) { !isActive } }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "couldn't save what was shared", e)
                    null
                }
            if (saved == null) {
                Toast.makeText(this@ShareInActivity, R.string.share_in_failed, Toast.LENGTH_LONG).show()
            } else if (!postNotification(NOTIFY_CHANNEL, getString(R.string.linux_notify_channel), getString(R.string.activity_linux), getString(R.string.share_in_saved))) {
                Toast.makeText(this@ShareInActivity, R.string.share_in_saved, Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    internal companion object {
        const val TAG = "share"

        /** As PhoneBridge's, so everything the launcher says about Linux sits on one channel. */
        const val NOTIFY_CHANNEL = "linux"

        /** The alias the manifest exports to the share sheet; this activity itself isn't exported. */
        private val SHARE_SHEET = ComponentName(BuildConfig.APPLICATION_ID, "dev.omalauncher.ShareToLinux")

        /**
         * Lists Linux in Android's share sheet, or takes it out: Linux is installed and removed while the launcher
         * runs, and a destination with no guest to save into mustn't be offered. The manifest's alias starts off;
         * Runtime follows the install state.
         */
        fun listedInShareSheet(
            context: Context,
            listed: Boolean,
        ) {
            context.packageManager.setComponentEnabledSetting(
                SHARE_SHEET,
                if (listed) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                // The launcher is this process, and a toggle mustn't end it.
                PackageManager.DONT_KILL_APP,
            )
        }

        /** The files [intent] shared: its clip data, which most senders set, else EXTRA_STREAM. */
        fun sharedUris(intent: Intent): List<Uri> {
            val uris = ArrayList<Uri>()
            intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let(uris::add) }
            if (uris.isEmpty()) {
                val streams =
                    if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { arrayListOf(it) }
                    }
                streams?.let { uris += it }
            }
            return uris.filter { it.scheme == "content" || it.scheme == "file" }
        }
    }
}
