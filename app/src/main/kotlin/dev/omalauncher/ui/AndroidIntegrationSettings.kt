package dev.omalauncher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.StorageBind

/** The same controls are available before installation, after it, and when setup needs repair. */
@Composable
internal fun AndroidIntegrationSettings(runtime: Runtime) {
    val settings = runtime.androidIntegration
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var host by rememberSaveable { mutableStateOf("") }
    var guest by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        error = if (granted) null else "Storage access was not granted. App-owned directories remain available."
    }
    fun changeBinds(value: List<StorageBind>): Boolean = try {
        settings.updateBinds(value)
        error = null
        true
    } catch (e: Exception) {
        error = e.message
        false
    }
    Column(
        Modifier.fillMaxWidth().frame(1.dp, Palette.border, Palette.background, radius = 12.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label("linux environment")
        BasicText(
            "Connect Linux terminals and apps to Android commands and folders. " +
                "These options can be changed later in settings › linux.",
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
        Choice("Android commands (ando)", "Run Android tools as Omalauncher's app user", settings.andoEnabled) {
            runtime.setAndo(!settings.andoEnabled)
        }
        runtime.ando.error?.let { message ->
            BasicText(message, style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
            BracketAction("retry", Palette.accent) { runtime.setAndo(true) }
        }
        BracketAction("${if (expanded) "hide" else "manage"} storage binds (${settings.binds.size})", Palette.accent) { expanded = !expanded }
        if (expanded) {
            BasicText(
                "Share Android folders with Linux. Binds are writable; files stay on Android when Linux is removed. " +
                    "Changes apply to newly opened terminals and apps. Close old sessions to release removed binds.",
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
            BracketAction("allow shared storage access", Palette.accent) {
                if (Build.VERSION.SDK_INT >= 30) {
                    error = runCatching {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }.exceptionOrNull()?.message
                } else if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            for ((index, bind) in settings.binds.withIndex()) {
                Column(Modifier.fillMaxWidth().background(Palette.surface).padding(10.dp)) {
                    BasicText(bind.guestPath, style = Mono.copy(color = Palette.foreground))
                    BasicText(bind.hostPath, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                    Row {
                        BracketAction("edit", Palette.accent) { host = bind.hostPath; guest = bind.guestPath; editing = index }
                        BracketAction("remove", Palette.red) {
                            if (changeBinds(settings.binds.filterIndexed { i, _ -> i != index })) editing = null
                        }
                    }
                }
            }
            settings.error?.let {
                BasicText(it, style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
                BracketAction("remove invalid configuration", Palette.red) { changeBinds(emptyList()) }
            }
            Row {
                BracketAction("downloads", Palette.accent) {
                    @Suppress("DEPRECATION")
                    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    host = downloads.path; guest = "/root/Downloads"; editing = null
                }
                BracketAction("app storage", Palette.accent) {
                    host = context.getExternalFilesDir(null)?.path.orEmpty(); guest = "/mnt/android-app"; editing = null
                }
            }
            PathField("Android folder (absolute path)", host) { host = it }
            PathField("Linux folder (empty destination)", guest) { guest = it }
            Row {
                BracketAction(if (editing == null) "add bind" else "save bind", Palette.accent) {
                    val target = if (guest == "~") "/root" else if (guest.startsWith("~/")) "/root/${guest.removePrefix("~/")}" else guest
                    val bind = StorageBind(host, target)
                    val next = settings.binds.toMutableList()
                    val index = editing
                    if (index != null && index in next.indices) next[index] = bind else next.add(bind)
                    if (changeBinds(next)) { host = ""; guest = ""; editing = null }
                }
                if (editing != null) BracketAction("cancel", Palette.muted) { editing = null; host = ""; guest = "" }
            }
        }
        error?.let { BasicText(it, style = Mono.copy(color = Palette.red, fontSize = TextSize.Small)) }
    }
}

@Composable
private fun PathField(label: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(label, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        BasicTextField(value, onChange, Modifier.fillMaxWidth().background(Palette.surface).padding(10.dp),
            textStyle = Mono.copy(color = Palette.foreground), cursorBrush = SolidColor(Palette.accent), singleLine = true)
    }
}
