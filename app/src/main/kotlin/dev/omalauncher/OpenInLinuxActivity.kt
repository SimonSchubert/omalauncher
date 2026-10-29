package dev.omalauncher

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.linux.GuestMime
import dev.omalauncher.linux.LinuxApp
import dev.omalauncher.linux.commandFor
import dev.omalauncher.linux.importGuestFile
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.PanelFrame
import dev.omalauncher.ui.PanelTitle
import dev.omalauncher.ui.ReadingWidth
import dev.omalauncher.ui.applySystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Open in Linux": Android's share sheet and "Open with" hand files to
 * Linux apps. They're copied into Linux's Downloads, then opened in the
 * Linux app picked from the ones that take their type (a model in a slicer,
 * a G-code in its viewer), or left there, or shown in Files.
 */
class OpenInLinuxActivity : InstantActivity() {

    /** The copies in Linux, as guest paths; null while copying. */
    private var copied by mutableStateOf<List<String>?>(null)

    /** The app being started, shown until its window opens. */
    private var starting by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!runtime.bootstrap.isInstalled) {
            Toast.makeText(this, "Linux isn't installed", Toast.LENGTH_SHORT).show()
            return finish()
        }
        val uris = urisOf(intent)
        if (uris.isEmpty()) return finish()
        // Shared into a launcher that wasn't running: Linux comes up while the files copy.
        runtime.start()
        applySystemBars(this, Palette.isLight)
        setInstantContent {
            LaunchedEffect(Palette.isLight) { applySystemBars(this@OpenInLinuxActivity, Palette.isLight) }
            LaunchedEffect(Unit) {
                copied = withContext(Dispatchers.IO) { uris.mapNotNull(::copyIn) }
                if (copied.isNullOrEmpty()) {
                    Toast.makeText(this@OpenInLinuxActivity, "couldn't read that file", Toast.LENGTH_SHORT).show()
                    closeInstantly()
                }
            }
            OpenInLinux(copied, starting, onOpen = ::open, onFiles = ::showInFiles, onDone = ::closeInstantly)
        }
    }

    /**
     * Starts [app] on the files and stays up until its window opens: Android lets an app start an activity
     * (the window's) only while it's in front, and an app takes seconds to show a window. Closing at once left
     * the app running without one, behind whatever shared the file. Gives up after a while, or when the app exits.
     */
    private fun open(app: LinuxApp) {
        val paths = copied ?: return
        if (starting != null) return
        starting = app.name
        lifecycleScope.launch {
            snapshotFlow { runtime.linux }.first { it }
            val before = runtime.wm.appWindows.toSet()
            val exited = mutableStateOf(false)
            runtime.launch(app.commandFor(paths)) { exited.value = true }
            withTimeoutOrNull(WINDOW_WAIT_MS) {
                snapshotFlow { exited.value || runtime.wm.appWindows.any { it !in before } }.first { it }
            }
            closeInstantly()
        }
    }

    private fun showInFiles() {
        runtime.openFiles(copied?.firstOrNull()?.substringBeforeLast('/'))
        closeInstantly()
    }

    /** Copies one shared file into Linux's Downloads, under its own name, numbered if it's taken; null if it can't be read. */
    private fun copyIn(uri: Uri): String? {
        val name =
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()?.replace('/', '_')?.takeIf { it.isNotBlank() && it != "." && it != ".." }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "shared"
        return runCatching {
            contentResolver.openInputStream(uri)!!.use { input -> importGuestFile(runtime.proot.guestFs, name, input) }
        }.getOrNull()
    }

    private companion object {
        const val WINDOW_WAIT_MS = 90_000L
    }

    private fun urisOf(intent: Intent): List<Uri> =
        when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.stream())
            Intent.ACTION_SEND_MULTIPLE -> intent.streams()
            else -> emptyList()
        }

    @Suppress("DEPRECATION")
    private fun Intent.stream(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streams(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
}

/** The copied files and the Linux apps that open them: those that take their type first, then the rest. */
@Composable
private fun OpenInLinux(
    copied: List<String>?,
    starting: String?,
    onOpen: (LinuxApp) -> Unit,
    onFiles: () -> Unit,
    onDone: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val runtime = (context.applicationContext as App).runtime
    var mime by remember { mutableStateOf<GuestMime?>(null) }
    LaunchedEffect(Unit) { mime = withContext(Dispatchers.IO) { GuestMime.load(runtime.proot.rootfs) } }
    val types = copied.orEmpty().mapNotNull { path -> mime?.forName(path.substringAfterLast('/')) }
    val apps = runtime.linuxApps.apps.filter { !it.terminal }
    val fits = apps.filter { app -> types.isNotEmpty() && types.all { mime?.opens(app.mimeTypes, it) == true } }
    val others = apps - fits.toSet()

    Box(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding().padding(12.dp)) {
        PanelFrame(onClose = onDone, title = { PanelTitle("open in Linux") }, contentWidth = ReadingWidth) {
            when {
                copied == null -> Choice("copying…", Palette.muted) {}
                starting != null -> Choice("starting $starting…", Palette.muted) {}
                else -> {
                    val names = copied.joinToString(", ") { it.substringAfterLast('/') }
                    BasicText("$names — in Downloads", Modifier.padding(bottom = 8.dp), style = Mono.copy(color = Palette.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(fits, key = { "fit:" + it.id }) { app -> Choice(app.name, Palette.foreground) { onOpen(app) } }
                        item { Choice("show in Files", Palette.accent, onFiles) }
                        item { Choice("just keep it there", Palette.accent, onDone) }
                        if (others.isNotEmpty()) {
                            item { BasicText("other apps", Modifier.padding(top = 12.dp, bottom = 4.dp), style = Mono.copy(color = Palette.muted)) }
                            items(others, key = { "other:" + it.id }) { app -> Choice(app.name, Palette.muted) { onOpen(app) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    BasicText(
        text,
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        style = Mono.copy(color = color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
