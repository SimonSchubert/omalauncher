package dev.omalauncher.linux

import android.content.Context
import android.system.Os
import android.util.Log
import dev.omalauncher.system.openUrl
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
    /** A guest path to open, and its Android file, handed over by a Linux app's xdg-open. */
    private val onOpenFile: (String, File) -> Unit,
    private val onOpenVoice: () -> Unit,
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
        // Written by the omarchy-voice shim: open the voice screen.
        if (url == VOICE_PREFIX) return onOpenVoice()
        // Written by the omarchy-edit shim: its reply FIFO (no spaces, made by mktemp) and the file.
        if (url.startsWith(EDIT_PREFIX)) {
            val (reply, path) = url.removePrefix(EDIT_PREFIX).split(' ', limit = 2).takeIf { it.size == 2 } ?: return
            return onEdit(path, proot.guestFile(reply)?.path ?: return)
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            // A file a Linux app asked to open (a path, or a file:// URI): the launcher's Files or
            // Android's apps, whichever the user picks. A folder, or what's gone, stays ignored.
            val path = xdgOpenPath(url) ?: run { Log.i(TAG, "ignoring non-URL $url"); return }
            val file = proot.guestFile(path)
            if (file?.isFile != true) { Log.i(TAG, "ignoring non-URL $url"); return }
            return onOpenFile(path, file)
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
                # omalauncher: opens URLs in the Android browser, and asks where a file opens: in Files or Android's apps (see OpenBridge.kt)
                case ${'$'}1 in /*|*://*) ;; *) set -- "${'$'}{PWD}/${'$'}1" ;; esac
                timeout 2 sh -c 'printf "%s\n" "${'$'}1" > $guestFifo' sh "${'$'}1"
                """.trimIndent() + "\n"
            },
            // npm's open package (DeepSeek Harness, agents' logins) runs its own copy of xdg-utils' xdg-open, which
            // skips BROWSER=xdg-open, so as not to call itself, and tries x-www-browser first.
            Managed.text(File(bin, "x-www-browser"), Policy.Owned, executable = true) {
                """
                #!/bin/sh
                # omalauncher: opens URLs in the Android browser, for scripts that look for a browser by this name
                exec xdg-open "${'$'}@"
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
            Managed.text(File(bin, VOICE_COMMAND), Policy.Owned, executable = true) {
                """
                #!/bin/sh
                # omalauncher: opens the launcher's voice screen, a spoken conversation with an agent (see OpenBridge.kt)
                timeout 2 sh -c 'printf "%s\n" "${'$'}1" > $guestFifo' sh "$VOICE_PREFIX"
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
        const val VOICE_PREFIX = "omarchy-voice:"
    }
}

/**
 * The guest path a Linux app asked to open through xdg-open: an absolute path, or a file:// URI with
 * its escapes read out and its fragment and query left off. Null for anything else, like a URL, which
 * the bridge opens in the browser.
 */
fun xdgOpenPath(target: String): String? {
    val path =
        when {
            target.startsWith("file:") -> {
                val rest = target.removePrefix("file:").removePrefix("//")
                decodeEscapes(rest.substringBefore('#').substringBefore('?'))
            }
            target.startsWith("/") -> target
            else -> return null
        }
    return path.takeIf { it.startsWith("/") }
}

/** Reads the percent escapes out of a file URI's path: a run of them is bytes, read as UTF-8. */
private fun decodeEscapes(path: String): String {
    val out = StringBuilder(path.length)
    val run = ArrayList<Byte>()
    var at = 0
    while (at < path.length) {
        val hi = if (path[at] == '%' && at + 2 < path.length) path[at + 1].digitToIntOrNull(16) else null
        val lo = hi?.let { path[at + 2].digitToIntOrNull(16) }
        if (hi != null && lo != null) {
            run.add((hi * 16 + lo).toByte())
            at += 3
        } else {
            readRun(out, run)
            out.append(path[at])
            at++
        }
    }
    readRun(out, run)
    return out.toString()
}

/** Appends [run], the bytes of a row of escapes, as UTF-8 text. */
private fun readRun(out: StringBuilder, run: ArrayList<Byte>) {
    if (run.isNotEmpty()) {
        out.append(String(run.toByteArray(), Charsets.UTF_8))
        run.clear()
    }
}
