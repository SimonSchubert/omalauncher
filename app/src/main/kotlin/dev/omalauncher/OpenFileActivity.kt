package dev.omalauncher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import dev.omalauncher.system.AppLanguage
import dev.omalauncher.ui.KeyIndicated
import dev.omalauncher.ui.OpenFileDialog
import dev.omalauncher.ui.files.mimeOf
import java.io.File

/**
 * Asks how a file a Linux app handed to xdg-open opens: in the launcher's Files, or handed over to
 * Android's apps, where the default one for its type opens it (see OpenBridge). Its own task out of
 * Recents; only the dialog shows, over whatever was on screen, and it's gone once it's answered.
 */
class OpenFileActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        AppLanguage.applyTo(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A plain Application while a backup or device transfer runs: nothing to ask with.
        if (application !is App) return finish()
        val path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        val file = intent.getStringExtra(EXTRA_FILE)?.let(::File) ?: return finish()
        setContent {
            KeyIndicated {
                OpenFileDialog(
                    name = file.name,
                    onFiles = {
                        runtime.openFiles(path.substringBeforeLast('/').ifEmpty { "/" })
                        finish()
                    },
                    onAndroid = {
                        handOver(file)
                        finish()
                    },
                    onDismiss = ::finish,
                )
            }
        }
    }

    /** The file to Android's apps, as Files hands one over (see FilesScreen's openWithAndroid). */
    private fun handOver(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val view =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeOf(file.name) ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.files_no_app, file.name), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_FILE = "file"

        fun intent(
            context: Context,
            path: String,
            file: File,
        ): Intent =
            Intent(context, OpenFileActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_FILE, file.path)
    }
}
