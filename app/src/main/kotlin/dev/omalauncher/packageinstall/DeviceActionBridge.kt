package dev.omalauncher.packageinstall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import dev.omalauncher.R
import dev.omalauncher.linux.Proot
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class PendingDeviceAction(
    val request: DeviceActionRequest,
    val createdAt: Long = System.currentTimeMillis(),
)

sealed interface DeviceActionResult {
    data object Started : DeviceActionResult

    data class Rejected(val message: String) : DeviceActionResult
}

/**
 * Host-owned, allowlisted Android action bridge. It is intentionally not an
 * ADB shell: guest input is parsed here, then Android APIs perform only the
 * supported action after the user's approval policy is applied.
 */
class DeviceActionBridge(
    private val context: Context,
    private val proot: Proot,
    private val yoloEnabled: () -> Boolean,
) {
    private val pending = ConcurrentHashMap<String, PendingDeviceAction>()
    private val approving = ConcurrentHashMap.newKeySet<String>()
    private val started = AtomicBoolean(false)
    private val inbox get() = File(proot.runtimeDir, INBOX_NAME)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        thread(name = "device-action-bridge", isDaemon = true) {
            while (started.get()) {
                scan()
                expire()
                Thread.sleep(POLL_MS)
            }
        }
    }

    fun pending(id: String): PendingDeviceAction? = pending[id]

    fun approve(id: String): DeviceActionResult = start(id, unattended = false)

    fun reject(
        id: String,
        message: String = "rejected by user",
    ) {
        if (approving.contains(id)) return
        rejectNow(id, message)
    }

    private fun start(
        id: String,
        unattended: Boolean,
    ): DeviceActionResult {
        if (unattended && !yoloEnabled()) return DeviceActionResult.Rejected("YOLO mode is disabled")
        val request = pending[id] ?: return DeviceActionResult.Rejected("request is no longer pending")
        if (!approving.add(id)) return DeviceActionResult.Rejected("device action is already starting")
        try {
            return try {
                perform(request.request)
                pending.remove(id, request)
                writeReply(id, "ok")
                cancelNotification(id)
                DeviceActionResult.Started
            } catch (e: Exception) {
                val message = e.message ?: "device action failed"
                rejectNow(id, message)
                DeviceActionResult.Rejected(message)
            }
        } finally {
            approving.remove(id)
        }
    }

    private fun perform(request: DeviceActionRequest) {
        val intent =
            when (request.action) {
                DeviceActionProtocol.OPEN_PACKAGE ->
                    context.packageManager.getLaunchIntentForPackage(request.target)
                        ?: error("package has no launcher activity")

                DeviceActionProtocol.OPEN_COMPONENT -> {
                    val component = ComponentName.unflattenFromString(request.target) ?: error("component is invalid")
                    val info = context.packageManager.getActivityInfo(component, 0)
                    require(info.exported) { "component is not exported" }
                    Intent().setComponent(component)
                }

                DeviceActionProtocol.OPEN_URL ->
                    Intent(Intent.ACTION_VIEW, Uri.parse(request.target)).addCategory(Intent.CATEGORY_BROWSABLE)

                else -> error("unsupported device action")
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        require(context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            "no activity can handle this action"
        }
        context.startActivity(intent)
    }

    private fun scan() {
        val dir = safeInbox(create = true) ?: return
        dir.listFiles()
            .orEmpty()
            .filter { it.name.endsWith(REQUEST_SUFFIX) && it.name.removeSuffix(REQUEST_SUFFIX).matches(REQUEST_ID) }
            .forEach(::readRequest)
    }

    private fun readRequest(file: File) {
        val dir = safeInbox(create = false) ?: return
        if (file.parentFile?.canonicalFile != dir.canonicalFile || Files.isSymbolicLink(file.toPath())) return
        val id = file.name.removeSuffix(REQUEST_SUFFIX)
        val bytes = readBounded(file, DeviceActionProtocol.MAX_REQUEST_BYTES)
        file.delete()
        val request = runCatching { DeviceActionProtocol.parse(id, bytes ?: error("request is too large")) }.getOrElse {
            writeReply(id, DeviceActionProtocol.response(it.message ?: "invalid request"))
            return
        }
        if (pending.containsKey(id)) return
        val result = PendingDeviceAction(request)
        pending[id] = result
        if (yoloEnabled()) startUnattended(id) else prompt(result)
    }

    private fun startUnattended(id: String) {
        thread(name = "device-action-yolo", isDaemon = true) {
            val result = start(id, unattended = true)
            if (result is DeviceActionResult.Rejected && pending.containsKey(id)) rejectNow(id, result.message)
        }
    }

    private fun prompt(request: PendingDeviceAction) {
        val intent =
            Intent(context, DeviceActionActivity::class.java)
                .putExtra(DeviceActionActivity.EXTRA_REQUEST_ID, request.request.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.i(TAG, "approval activity deferred to notification", e)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "device action approvals", NotificationManager.IMPORTANCE_HIGH))
            val pendingIntent =
                PendingIntent.getActivity(
                    context,
                    request.request.id.hashCode(),
                    intent.setData(Uri.parse("omalauncher://device-action/approval/${request.request.id}")),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            manager.notify(
                request.request.id,
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("agent device action")
                    .setContentText("${request.request.action} · ${request.request.target}")
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun expire() {
        val now = System.currentTimeMillis()
        pending.values.filter { now - it.createdAt > REQUEST_TIMEOUT_MS }.forEach { reject(it.request.id, "request expired") }
    }

    private fun rejectNow(
        id: String,
        message: String,
    ) {
        pending.remove(id)?.let { writeReply(id, DeviceActionProtocol.response(message)) }
        cancelNotification(id)
    }

    private fun writeReply(
        id: String,
        reply: String,
    ) {
        if (!REQUEST_ID.matches(id)) return
        val dir = safeInbox(create = false) ?: return
        val target = File(dir, "$id.done")
        val temp = runCatching { Files.createTempFile(dir.toPath(), ".reply-", ".tmp") }.getOrNull() ?: return
        try {
            Files.newByteChannel(temp, StandardOpenOption.WRITE).use { it.write(ByteBuffer.wrap(reply.toByteArray())) }
            runCatching {
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse { Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            Log.w(TAG, "can't answer device action", e)
        }
    }

    private fun safeInbox(create: Boolean): File? {
        val runtime = proot.runtimeDir
        if (Files.isSymbolicLink(runtime.toPath())) return null
        if (create) runtime.mkdirs()
        if (!Files.isDirectory(runtime.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val dir = inbox
        if (create && !Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) runCatching { Files.createDirectory(dir.toPath()) }
        if (!Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dir.toPath())) return null
        if (dir.canonicalFile.parentFile != runtime.canonicalFile) return null
        return dir
    }

    private fun cancelNotification(id: String) = context.getSystemService(NotificationManager::class.java).cancel(id, NOTIFICATION_ID)

    /** Guest helper and the Codex skill are launcher-owned so each APK update keeps them current. */
    fun managed(): List<Managed> {
        val bin = File(proot.rootfs, "usr/local/bin")
        return listOf(
            Managed.text(File(bin, "omalauncher-device"), Policy.Owned, executable = true) {
                """
                #!/bin/bash
                # Request a bounded Android action from the host.
                set -u
                if [ "${'$'}#" -lt 2 ] || [ "${'$'}#" -gt 3 ]; then
                  echo "usage: omalauncher-device open-package|open-component|open-url target [reason]" >&2
                  exit 2
                fi
                action=${'$'}1
                target=${'$'}2
                reason=${'$'}{3-}
                case "${'$'}action" in open-package|open-component|open-url) ;; *) echo "unsupported device action" >&2; exit 2 ;; esac
                case "${'$'}target" in *$'\n'*|*$'\r'*) echo "target contains a newline" >&2; exit 2 ;; esac
                dir="${Proot.GUEST_RUNTIME_DIR}/device-action"
                mkdir -p -- "${'$'}dir"
                id="${'$'}(date +%s%N)-${'$'}${'$'}"
                tmp="${'$'}dir/.${'$'}id.tmp"
                done="${'$'}dir/${'$'}id.done"
                {
                  printf '%s\n' "${'$'}action"
                  printf '%s\n' "${'$'}target"
                  printf '%.240s\n' "${'$'}reason"
                } >"${'$'}tmp" || exit 1
                mv -- "${'$'}tmp" "${'$'}dir/${'$'}id.req" || exit 1
                for _ in ${'$'}(seq 1 6000); do
                  if [ -f "${'$'}done" ]; then
                    reply=${'$'}(cat -- "${'$'}done")
                    rm -f -- "${'$'}done"
                    [ "${'$'}reply" = ok ] && exit 0
                    echo "${'$'}reply" >&2
                    exit 1
                  fi
                  sleep 0.1
                done
                rm -f -- "${'$'}dir/${'$'}id.req"
                echo "Omalauncher did not answer the device action" >&2
                exit 1
                """.trimIndent() + "\n"
            },
            Managed.text(File(proot.home, ".codex/skills/omalauncher-device/SKILL.md"), Policy.Owned) { DEVICE_SKILL },
        )
    }

    companion object {
        private const val TAG = "device-action"
        private const val CHANNEL = "device-action"
        private const val NOTIFICATION_ID = 2
        private const val INBOX_NAME = "device-action"
        private const val REQUEST_SUFFIX = ".req"
        private val REQUEST_ID = Regex("[A-Za-z0-9._-]{1,96}")
        private const val POLL_MS = 200L
        private const val REQUEST_TIMEOUT_MS = 10 * 60 * 1000L

        private fun readBounded(file: File, max: Int): ByteArray? {
            if (!file.isFile || file.length() > max) return null
            return runCatching {
                FileInputStream(file).use { input ->
                    val out = ByteArray(max + 1)
                    var count = 0
                    while (count < out.size) {
                        val read = input.read(out, count, out.size - count)
                        if (read < 0) break
                        count += read
                    }
                    if (count > max) null else out.copyOf(count)
                }
            }.getOrNull()
        }

        private const val DEVICE_SKILL = """---
name: omalauncher-device
description: Use Omalauncher's approved Android device-action bridge from inside the phone.
---

# Omalauncher device actions

This Codex session runs inside Omalauncher's Android app, inside an Arch proot guest. The guest does not have an ADB socket, Android credentials, root, or signing keys. Use the host bridge when a user asks you to open an installed Android app, open a web URL, or open an exported Android component:

```sh
omalauncher-device open-package com.example.app "why it is needed"
omalauncher-device open-component com.example.app/.MainActivity "why it is needed"
omalauncher-device open-url https://example.com "why it is needed"
```

The helper blocks until Omalauncher responds. In normal mode the user sees an approval screen with the action and target. If the user explicitly enabled Settings > agent device access > YOLO, the host may perform the validated action without Omalauncher's extra prompt. Android and the target app can still reject or confirm the action. Never try to write the request files yourself or bypass the helper.

For an APK built in the guest, use `omalauncher-install /absolute/path/app-debug.apk "reason"`; it remains the package-install path and has its own APK inspection and Android PackageInstaller policy.

Supported actions are deliberately narrow: launch a package's launcher activity, launch an exported component in an installed package, or open an http/https URL. There is no arbitrary `adb shell`, tap/swipe injection, package-data dump, uninstall, settings mutation, or root capability. If a task needs something outside these actions, explain the limitation and ask the user to perform it or use a separate host-side, approval-gated development transport.

Build and test Android apps from source with the guest's Java/Gradle/SDK paths, then request installation through the helper. Do not download malware or untrusted APKs to test the bridge.
"""
    }
}
