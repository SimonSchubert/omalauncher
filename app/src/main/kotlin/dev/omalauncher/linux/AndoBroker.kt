package dev.omalauncher.linux

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Owns an untraced Android process. Its stdin is a lifetime pipe, never command input. */
class AndoBroker(private val binary: File, val directory: File) {
    private var process: Process? = null
    var error by mutableStateOf<String?>(null)
        private set

    @Synchronized
    fun start() {
        if (process?.isAlive == true) return
        stop()
        try {
            check(directory.mkdirs() || directory.isDirectory) { "Cannot create the ando runtime directory" }
            val child = ProcessBuilder(binary.path, "--omalauncher-broker", File(directory, "ando.sock").path)
                .redirectErrorStream(true).start()
            process = child
            val ready = CompletableFuture.supplyAsync { child.inputStream.bufferedReader().readLine() }
            check(ready.get(8, TimeUnit.SECONDS) == "READY") { "Android command broker could not start" }
            error = null
        } catch (e: Exception) {
            stop()
            error = "Android commands unavailable: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    @Synchronized
    fun stop() {
        process?.let { child ->
            runCatching { child.outputStream.close() }
            if (!child.waitFor(7, TimeUnit.SECONDS)) {
                child.destroyForcibly()
                child.waitFor(1, TimeUnit.SECONDS)
            }
            child.inputStream.close()
            child.errorStream.close()
        }
        process = null
        File(directory, "ando.sock").delete()
        error = null
    }

    fun managed(rootfs: File): List<Managed> = listOf(
        Managed(File(rootfs, "usr/local/bin/ando"), Policy.Owned, executable = true) { binary.readBytes() },
    )
}
