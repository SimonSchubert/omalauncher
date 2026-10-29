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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.R
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
    OverlayPanel(stringResource(R.string.linux_title), onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            when (val s = state) {
                LinuxState.Checking -> {}
                LinuxState.NotInstalled -> InstallOffer(runtime)
                is LinuxState.Installing, is LinuxState.Failed -> LinuxProgress(s, runtime.bootstrap.chosenAgents, runtime::retryLinux, runtime::cancelLinux.takeIf { runtime.bootstrap.cancellable })
                LinuxState.Installed -> Installed(runtime)
                LinuxState.Removing -> BasicText(stringResource(R.string.linux_removing), style = Mono.copy(color = Palette.accent))
            }
        }
    }
}

/**
 * Starts the Linux install and asks (Android 13+) to post notifications: the foreground
 * service's notification shows the install's progress and that Linux is running.
 * The install doesn't wait for the answer, which may never come back: on the welcome
 * screen, becoming the home app moves home to a new task, away from the dialog.
 */
@Composable
fun rememberLinuxInstall(runtime: Runtime): (List<String>) -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return { bins ->
        runtime.installLinux(AGENTS.filter { it.bin in bins })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun InstallOffer(runtime: Runtime) {
    var picked by rememberSaveable { mutableStateOf(listOf(AGENTS.first().bin)) }
    val install = rememberLinuxInstall(runtime)
    BasicText(stringResource(R.string.linux_offer_title), style = Mono.copy(fontSize = TextSize.Large))
    Spacer(Modifier.height(8.dp))
    BasicText(
        stringResource(R.string.linux_offer_body),
        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
    )
    Spacer(Modifier.height(16.dp))
    // Above the agent list, so it's in reach without scrolling.
    PrimaryButton(if (picked.isEmpty()) stringResource(R.string.linux_install) else pluralStringResource(R.plurals.linux_install_with_agents, picked.size, picked.size), Modifier.takesPassedKeys().passesKeys()) {
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
        Modifier.card(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(stringResource(R.string.linux_arch), style = Mono.copy(fontSize = TextSize.Item))
            BasicText(
                stringResource(R.string.linux_installed_size, size?.let(::formatSize) ?: stringResource(R.string.linux_measuring)),
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
        }
    }
    if (runtime.gpu.supported) {
        Spacer(Modifier.height(8.dp))
        GpuRow(runtime)
    }
    Spacer(Modifier.height(16.dp))
    // Keys go between remove linux and its cancel as they swap, and wait out the removal for the install it ends with.
    val ask = remember { FocusRequester() }
    val cancel = remember { FocusRequester() }
    if (!confirm) {
        BracketAction(stringResource(R.string.linux_remove), Palette.red, Modifier.focusRequester(ask).passesKeys(cancel)) { confirm = true }
    } else {
        Column(
            Modifier.card(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(stringResource(R.string.linux_remove_ask), style = Mono.copy(color = Palette.red))
            BasicText(
                stringResource(R.string.linux_remove_body),
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
            Row {
                BracketAction(stringResource(R.string.linux_remove_confirm), Palette.red, Modifier.passesKeys(), runtime::removeLinux)
                BracketAction(stringResource(R.string.linux_cancel), Palette.muted, Modifier.focusRequester(cancel).passesKeys(ask)) { confirm = false }
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
            state is JobState.Working -> stringResource(R.string.linux_job_working, stringResource(state.action))
            gpu.active -> stringResource(R.string.linux_gpu_on, gpu.device ?: "GPU")
            gpu.wanted && state is JobState.Failed -> stringResource(R.string.linux_gpu_off_failed)
            gpu.wanted -> stringResource(R.string.linux_gpu_waiting)
            state is JobState.Failed -> stringResource(R.string.linux_gpu_failed)
            else -> stringResource(R.string.linux_gpu_offer, gpu.driverLabel)
        }
    Column(Modifier.card()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText(stringResource(R.string.linux_gpu_title), style = Mono.copy(fontSize = TextSize.Item))
                BasicText(
                    status,
                    style = Mono.copy(color = if (gpu.active) Palette.green else Palette.muted, fontSize = TextSize.Small),
                )
            }
            // Keys on one wait out its job, for the one it ends with.
            val passed = Modifier.takesPassedKeys().passesKeys()
            when {
                state is JobState.Working -> {}
                gpu.wanted -> {
                    if (state is JobState.Failed) BracketAction(stringResource(R.string.linux_retry), Palette.accent, passed) { runtime.setGpu(true) }
                    BracketAction(stringResource(R.string.linux_gpu_turn_off), Palette.red, passed) { runtime.setGpu(false) }
                }
                else -> BracketAction(stringResource(R.string.linux_gpu_turn_on), Palette.accent, passed) { runtime.setGpu(true) }
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
