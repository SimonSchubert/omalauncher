package dev.omalauncher.linux

import android.content.Context
import android.system.Os
import android.util.Log
import dev.omalauncher.openUrl
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File
import kotlin.concurrent.thread

private const val TAG = "open"

/**
 * `xdg-open` for the guest: URLs are written to a FIFO in the shared runtime
 * dir and opened with Android's browser. That's what makes agent logins work:
 * the OAuth page opens on the phone and redirects back to the agent's
 * localhost listener, since the guest shares the phone's network.
 */
class OpenBridge(
    private val context: Context,
    private val proot: Proot,
    private val onOpenFiles: (String) -> Unit,
    /** A guest path to edit, and the host path of the FIFO that gets the reply once it's closed. */
    private val onEdit: (String, String) -> Unit,
) {
    private val fifo = File(proot.runtimeDir, "open.fifo")
    private val guestFifo = "${Proot.GUEST_RUNTIME_DIR}/${fifo.name}"

    private var started = false

    fun start() {
        if (started) return
        started = true
        fifo.delete()
        thread(name = "open-bridge", isDaemon = true) {
            while (true) {
                // Each writer closes the FIFO after one URL; reopen for the next.
                try {
                    if (!fifo.exists()) Os.mkfifo(fifo.path, 0x180) // 0600
                    fifo.bufferedReader().useLines { lines -> lines.forEach(::open) }
                } catch (e: Exception) {
                    // One bad request or a removed FIFO mustn't stop xdg-open until the next app start.
                    Log.w(TAG, "bridge failed; retrying", e)
                    Thread.sleep(1000)
                }
            }
        }
    }

    private fun open(target: String) {
        val url = target.trim()
        // Written by the omarchy-files shim: open the Files app at a guest path.
        if (url.startsWith(FILES_PREFIX)) return onOpenFiles(url.removePrefix(FILES_PREFIX))
        // Written by the omarchy-edit shim: its reply FIFO (no spaces, made by mktemp) and the file.
        if (url.startsWith(EDIT_PREFIX)) {
            val (reply, path) = url.removePrefix(EDIT_PREFIX).split(' ', limit = 2).takeIf { it.size == 2 } ?: return
            return onEdit(path, proot.guestFile(reply)?.path ?: return)
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Log.i(TAG, "ignoring non-URL $url")
            return
        }
        if (!context.openUrl(url)) Log.w(TAG, "no browser for $url")
    }

    /** The shims; /usr/local/bin precedes /usr/bin, so xdg-open wins over xdg-utils if that's installed. */
    fun managed(): List<Managed> {
        val bin = File(proot.rootfs, "usr/local/bin")
        return listOf(
            Managed.text(File(bin, "xdg-open"), Policy.Owned, executable = true) {
                """
                #!/bin/sh
                # omalauncher: opens URLs in the Android browser (see OpenBridge.kt)
                timeout 2 sh -c 'printf "%s\n" "${'$'}1" > $guestFifo' sh "${'$'}1"
                """.trimIndent() + "\n"
            },
            Managed.text(File(bin, FILES_COMMAND), Policy.Owned, executable = true) {
                """
                #!/bin/sh
                # omalauncher: opens the launcher's Files app, at a folder if given (see OpenBridge.kt)
                dir=${'$'}(realpath -- "${'$'}{1:-.}" 2>/dev/null) || dir=${'$'}PWD
                [ -d "${'$'}dir" ] || dir=${'$'}(dirname -- "${'$'}dir")
                timeout 2 sh -c 'printf "%s\n" "${'$'}1" > $guestFifo' sh "$FILES_PREFIX${'$'}dir"
                """.trimIndent() + "\n"
            },
            Managed.text(File(bin, EDIT_COMMAND), Policy.Owned, executable = true) {
                """
                #!/bin/sh
                # omalauncher: $EDIT_COMMAND, Linux's ${'$'}EDITOR: edits a file in the launcher's editor and waits
                # until it's closed. micro gets anything else (options, several files, a folder), files the
                # editor can't take (big, binary), and everything while the launcher doesn't answer (see OpenBridge.kt).
                [ ${'$'}# -eq 1 ] && [ ! -d "${'$'}1" ] || exec micro "${'$'}@"
                case ${'$'}1 in -* | +*) exec micro "${'$'}@" ;; esac
                file=${'$'}(realpath -m -- "${'$'}1") || exec micro "${'$'}@"
                reply=${'$'}(mktemp -u ${Proot.GUEST_RUNTIME_DIR}/edit.XXXXXX)
                mkfifo -m 600 "${'$'}reply" || exec micro "${'$'}@"
                trap 'rm -f "${'$'}reply"; exit 130' INT TERM HUP
                if timeout 2 sh -c 'printf "%s\n" "${'$'}1" > $guestFifo' sh "$EDIT_PREFIX${'$'}reply ${'$'}file"; then
                    read -r answer < "${'$'}reply"
                    rm -f "${'$'}reply"
                    [ "${'$'}answer" = micro ] && exec micro "${'$'}@"
                    exit 0
                fi
                rm -f "${'$'}reply"
                exec micro "${'$'}@"
                """.trimIndent() + "\n"
            },
        )
    }

    private companion object {
        const val FILES_PREFIX = "omarchy-files:"
        const val EDIT_PREFIX = "omarchy-edit:"
    }
}
