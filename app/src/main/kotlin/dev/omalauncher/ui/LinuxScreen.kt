package dev.omalauncher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.AGENTS
import dev.omalauncher.linux.JobState
import dev.omalauncher.linux.LinuxState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

// Request local network access only on Android 17+, where the permission exists.
internal fun needsLocalNetworkPermission(sdkInt: Int, granted: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.CINNAMON_BUN && !granted

/**
 * Everything about the optional Linux system in one place: what it adds and
 * an install button, the install's progress, or, once installed, its size
 * and a way to remove it. Every feature that needs Linux opens this.
 */
@Composable
fun LinuxScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val state by runtime.bootstrap.state.collectAsState()
    OverlayPanel("linux", onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            when (val s = state) {
                LinuxState.Checking -> {}
                LinuxState.NotInstalled -> InstallOffer(runtime)
                is LinuxState.Installing, is LinuxState.Failed -> LinuxProgress(s, runtime.bootstrap.chosenAgents, runtime::retryLinux)
                LinuxState.Installed -> Installed(runtime)
                LinuxState.Removing -> BasicText("removing linux…", style = Mono.copy(color = Palette.accent))
            }
        }
    }
}

/**
 * Starts the Linux install and asks for the permissions Linux needs: notifications
 * (Android 13+) for the session foreground service, and local network (target SDK 37+)
 * so Arch and widgets can reach LAN hosts. The install doesn't wait for the answers.
 */
@Composable
fun rememberLinuxInstall(runtime: Runtime): (List<String>) -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    return { bins ->
        runtime.installLinux(AGENTS.filter { it.bin in bins })
        val need = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (needsLocalNetworkPermission(
                    sdkInt = Build.VERSION.SDK_INT,
                    granted = context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED,
                )
            ) {
                add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }
        if (need.isNotEmpty()) ask.launch(need.toTypedArray())
    }
}

@Composable
private fun InstallOffer(runtime: Runtime) {
    var picked by rememberSaveable { mutableStateOf(listOf(AGENTS.first().bin)) }
    val install = rememberLinuxInstall(runtime)
    BasicText("arch linux, optional", style = Mono.copy(fontSize = TextSize.Large))
    Spacer(Modifier.height(8.dp))
    BasicText(
        "Linux apps, terminal widgets, coding agents, pacman and Files. About 1 GB to download; installs in the background.",
        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
    )
    Spacer(Modifier.height(16.dp))
    // Above the agent list, so it's in reach without scrolling.
    PrimaryButton(if (picked.isEmpty()) "install linux" else "install linux and ${picked.size} agent${if (picked.size == 1) "" else "s"}") {
        install(picked)
    }
    Spacer(Modifier.height(24.dp))
    AgentPicker(picked) { bin -> picked = if (bin in picked) picked - bin else picked + bin }
}

@Composable
private fun Installed(runtime: Runtime) {
    var size by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { size = withContext(Dispatchers.IO) { diskUsage(runtime.proot.rootfs) } }
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(LauncherStyle.shape(12.dp)).background(Palette.surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText("arch linux", style = Mono.copy(fontSize = TextSize.Item))
            BasicText(
                "installed · " + (size?.let(::formatSize) ?: "measuring…"),
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
        }
    }
    if (runtime.gpu.supported) {
        Spacer(Modifier.height(8.dp))
        GpuRow(runtime)
    }
    Spacer(Modifier.height(16.dp))
    if (!confirm) {
        BracketAction("remove linux", Palette.red) { confirm = true }
    } else {
        Column(
            Modifier.fillMaxWidth().clip(LauncherStyle.shape(12.dp)).background(Palette.surface).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText("remove linux?", style = Mono.copy(color = Palette.red))
            BasicText(
                "Deletes Linux apps, coding agents, packages and everything in /root, like Downloads. " +
                    "Themes, wallpapers, widgets and your home screen stay.",
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
            Row {
                BracketAction("remove", Palette.red, runtime::removeLinux)
                BracketAction("cancel", Palette.muted) { confirm = false }
            }
        }
    }
}

/** Opt-in GPU acceleration for Linux apps; see [dev.omalauncher.linux.Gpu]. */
@Composable
private fun GpuRow(runtime: Runtime) {
    val gpu = runtime.gpu
    val state = gpu.state
    val status =
        when {
            state is JobState.Working -> "${state.action}…"
            gpu.active -> "on · ${gpu.device ?: "GPU"} · for apps opened from now on"
            gpu.wanted && state is JobState.Failed -> "off: the GPU failed its check after a system update"
            gpu.wanted -> "on · waiting for its check"
            state is JobState.Failed -> "the GPU failed its check; apps stay in software"
            else -> "experimental · faster OpenGL and Vulkan apps and games; terminal apps don't change"
        }
    Column(Modifier.fillMaxWidth().clip(LauncherStyle.shape(12.dp)).background(Palette.surface).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText("gpu acceleration", style = Mono.copy(fontSize = TextSize.Item))
                BasicText(
                    status,
                    style = Mono.copy(color = if (gpu.active) Palette.green else Palette.muted, fontSize = TextSize.Small),
                )
            }
            when {
                state is JobState.Working -> {}
                gpu.wanted -> {
                    if (state is JobState.Failed) BracketAction("retry", Palette.accent) { runtime.setGpu(true) }
                    BracketAction("turn off", Palette.red) { runtime.setGpu(false) }
                }
                else -> BracketAction("turn on", Palette.accent) { runtime.setGpu(true) }
            }
        }
        JobLog(state)
    }
}

/** Bytes in [dir], without following symlinks (the rootfs is full of absolute guest links). */
private fun diskUsage(dir: File): Long {
    var total = 0L
    runCatching {
        Files.walkFileTree(
            dir.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ) = FileVisitResult.CONTINUE
            },
        )
    }
    return total
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1L shl 30).toDouble()) else "${bytes shr 20} MB"
