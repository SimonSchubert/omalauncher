package dev.omalauncher.packageinstall

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.system.Os
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Shizuku-instantiated service. It runs with shell (ADB) or root identity, but
 * accepts no command until the host app authorizes this exact request once.
 */
@Keep
class AdbShellUserService : IAdbShellService.Stub() {
    private val authorizations = ConcurrentHashMap<String, Authorization>()

    override fun authorize(
        requestId: String,
        capability: String,
        commandDigest: String,
    ): Boolean {
        if (!requestId.matches(REQUEST_ID) || capability.length !in 32..128 || commandDigest.length != 64) return false
        if (commandDigest.any { it !in HEX_DIGITS }) return false
        authorizations[requestId] = Authorization(
            capability = capability,
            commandDigest = commandDigest,
            callerUid = Binder.getCallingUid(),
            expiresAt = System.currentTimeMillis() + AUTHORIZATION_TTL_MS,
        )
        return true
    }

    override fun executeOnce(
        requestId: String,
        capability: String,
        command: String,
        timeoutMs: Int,
        maxOutputBytes: Int,
    ): Bundle {
        val authorization = authorizations.remove(requestId)
        val callerUid = Binder.getCallingUid()
        if (
            authorization == null ||
            authorization.expiresAt < System.currentTimeMillis() ||
            authorization.callerUid != callerUid ||
            !constantTimeEquals(authorization.capability, capability) ||
            !constantTimeEquals(authorization.commandDigest, digest(command))
        ) {
            return Bundle().apply {
                putBoolean(KEY_AUTHORIZED, false)
                putString(KEY_ERROR, "shell request was not authorized")
            }
        }
        require(command.length in 1..DeviceActionProtocol.MAX_COMMAND_LENGTH) { "shell command is invalid" }
        require(command.none { it == '\u0000' || it == '\r' || it == '\n' || it.isISOControl() }) {
            "shell command contains a control character"
        }
        require(timeoutMs in 1..DeviceActionProtocol.SHELL_TIMEOUT_MS) { "shell timeout is invalid" }
        require(maxOutputBytes in 1..DeviceActionProtocol.MAX_SHELL_OUTPUT_BYTES) { "shell output limit is invalid" }
        return runCommand(command, timeoutMs, maxOutputBytes)
    }

    /** Reserved by Shizuku to tear down the user service. */
    override fun destroy() {
        System.exit(0)
    }

    private fun runCommand(
        command: String,
        timeoutMs: Int,
        maxOutputBytes: Int,
    ): Bundle {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = ByteArrayOutputStream(minOf(maxOutputBytes, OUTPUT_BUFFER_SIZE))
        var truncated = false
        val reader = thread(name = "omalauncher-shell-output", isDaemon = true) {
            process.inputStream.use { input ->
                val buffer = ByteArray(OUTPUT_READ_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val remaining = maxOutputBytes - total
                    if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                    if (count > remaining) truncated = true
                    total += count
                }
            }
        }
        var timedOut = false
        val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (!finished) {
            timedOut = true
            process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        reader.join(2_000)
        if (reader.isAlive) reader.interrupt()
        val exitCode = if (timedOut) TIMEOUT_EXIT_CODE else process.exitValue()
        return Bundle().apply {
            putBoolean(KEY_AUTHORIZED, true)
            putInt(KEY_EXIT_CODE, exitCode)
            putBoolean(KEY_TIMED_OUT, timedOut)
            putBoolean(KEY_TRUNCATED, truncated)
            putInt(KEY_UID, runCatching { Os.getuid() }.getOrDefault(-1))
            putString(KEY_OUTPUT, output.toString(StandardCharsets.UTF_8.name()))
        }
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(first: String, second: String): Boolean =
        MessageDigest.isEqual(first.toByteArray(StandardCharsets.UTF_8), second.toByteArray(StandardCharsets.UTF_8))

    private data class Authorization(
        val capability: String,
        val commandDigest: String,
        val callerUid: Int,
        val expiresAt: Long,
    )

    companion object {
        const val KEY_AUTHORIZED = "authorized"
        const val KEY_ERROR = "error"
        const val KEY_EXIT_CODE = "exit_code"
        const val KEY_TIMED_OUT = "timed_out"
        const val KEY_TRUNCATED = "truncated"
        const val KEY_UID = "uid"
        const val KEY_OUTPUT = "output"

        private val REQUEST_ID = Regex("[A-Za-z0-9._-]{1,96}")
        private const val HEX_DIGITS = "0123456789abcdef"
        private const val AUTHORIZATION_TTL_MS = 30_000L
        private const val OUTPUT_READ_BUFFER_SIZE = 4096
        private const val OUTPUT_BUFFER_SIZE = 8192
        private const val TIMEOUT_EXIT_CODE = 124
    }
}
