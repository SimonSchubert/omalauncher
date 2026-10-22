package dev.omalauncher.packageinstall

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.activity.ComponentActivity
import dev.omalauncher.BuildConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import rikka.shizuku.Shizuku

data class ShellExecutionResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val truncated: Boolean,
    val uid: Int,
    val output: String,
)

/** Bridges one explicitly approved request to a Shizuku shell/root user service. */
class ShizukuShellBridge(
    private val context: Context,
) {
    private val lock = ReentrantLock()
    private val random = SecureRandom()
    private val userServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(context, AdbShellUserService::class.java))
            .daemon(false)
            .processNameSuffix("adb-shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    fun status(): String = runCatching {
        when {
            !Shizuku.pingBinder() -> "Shizuku is not running"
            Shizuku.isPreV11() || Shizuku.getVersion() < 11 -> "Shizuku API 11 or newer is required"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Shizuku is running, but Omalauncher is not authorized"
            else -> "ready for approved device shell commands"
        }
    }.getOrElse { "Shizuku is unavailable: ${it.message ?: "unknown error"}" }

    fun requestPermission(
        activity: ComponentActivity,
        callback: (Boolean) -> Unit,
    ) {
        runCatching {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
                callback(false)
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                callback(true)
                return
            }
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    Shizuku.removeRequestPermissionResultListener(this)
                    callback(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }.onFailure { callback(false) }
    }

    fun execute(
        requestId: String,
        command: String,
    ): ShellExecutionResult = lock.withLock {
        ensureReady()
        val capability = newCapability()
        val digest = digest(command)
        val bound = bindUserService()
        try {
            check(bound.service.authorize(requestId, capability, digest)) { "shell service rejected the approved request" }
            val result = bound.service.executeOnce(
                requestId,
                capability,
                command,
                DeviceActionProtocol.SHELL_TIMEOUT_MS,
                DeviceActionProtocol.MAX_SHELL_OUTPUT_BYTES,
            )
            check(result.getBoolean(AdbShellUserService.KEY_AUTHORIZED, false)) {
                result.getString(AdbShellUserService.KEY_ERROR) ?: "shell service rejected the request"
            }
            ShellExecutionResult(
                exitCode = result.getInt(AdbShellUserService.KEY_EXIT_CODE, 1),
                timedOut = result.getBoolean(AdbShellUserService.KEY_TIMED_OUT, false),
                truncated = result.getBoolean(AdbShellUserService.KEY_TRUNCATED, false),
                uid = result.getInt(AdbShellUserService.KEY_UID, -1),
                output = result.getString(AdbShellUserService.KEY_OUTPUT).orEmpty(),
            )
        } finally {
            runCatching { Shizuku.unbindUserService(userServiceArgs, bound.connection, true) }
        }
    }

    private fun ensureReady() {
        check(Shizuku.pingBinder()) { "Shizuku is not running; start Shizuku first" }
        check(!Shizuku.isPreV11() && Shizuku.getVersion() >= 11) { "Shizuku API 11 or newer is required" }
        check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            "Omalauncher is not authorized in Shizuku"
        }
    }

    private fun bindUserService(): BoundService {
        val latch = CountDownLatch(1)
        var service: IAdbShellService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IAdbShellService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                latch.countDown()
            }

            override fun onBindingDied(name: ComponentName) {
                latch.countDown()
            }
        }
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (e: Throwable) {
            throw IllegalStateException("could not start the Shizuku shell service: ${e.message ?: e.javaClass.simpleName}", e)
        }
        check(latch.await(SERVICE_START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "timed out starting the Shizuku shell service"
        }
        return BoundService(service ?: error("Shizuku returned no shell service"), connection)
    }

    private fun newCapability(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class BoundService(
        val service: IAdbShellService,
        val connection: ServiceConnection,
    )

    companion object {
        private const val PERMISSION_REQUEST_CODE = 0x4f6d
        private const val SERVICE_START_TIMEOUT_MS = 5_000L
    }
}
