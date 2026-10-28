package dev.omalauncher.linux

import android.content.Context
import android.util.Log
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File
import kotlin.concurrent.thread

/** Launcher-owned CUPS instance, not systemd and not a replacement for user CUPS. */
internal class GuestPrinting(private val context: Context, private val proot: Proot) {
    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var child: Process? = null
    private var worker: Thread? = null

    fun managed(): List<Managed> = with(GuestPrintingConfig) {
        listOf(
            Managed.text(proot.hostFile("$SERVER/cups-files.conf"), Policy.Owned) { files },
            Managed.text(proot.hostFile("$SERVER/cupsd.conf"), Policy.Owned) { daemon },
            Managed.text(proot.hostFile(PDF_CONFIG), Policy.Owned) { pdf },
            Managed.text(proot.hostFile(FILTER), Policy.Owned, executable = true) { filter },
            // cupsd rewrites this file. Never overwrite its saved state/settings.
            Managed.text(proot.hostFile("$STATE/printers.conf"), Policy.Seeded) { printer },
            Managed(proot.hostFile(BACKEND), Policy.Owned, executable = true) {
                context.assets.open("guest-portal/omalauncher-cups-backend").use { it.readBytes() }
            }.withMode(0b111000000),
        )
    }

    @Synchronized fun start() {
        if (running) return
        running = true
        val epoch = ++generation
        worker = thread(name = "guest-cups", isDaemon = true) {
            while (running && generation == epoch) {
                var owned: Process? = null
                try {
                    prepareState(epoch)
                    if (!running || generation != epoch) break
                    val p = proot.process("exec cupsd -f -c '${GuestPrintingConfig.SERVER}/cupsd.conf' -s '${GuestPrintingConfig.SERVER}/cups-files.conf'").start()
                    owned = p
                    watch(p, epoch)
                    p.inputStream.bufferedReader().useLines { lines -> lines.forEach { Log.d("guest-cups", it) } }
                    p.waitFor()
                } catch (e: Exception) {
                    if (running && generation == epoch) Log.w("guest-cups", "printing unavailable; retrying", e)
                } finally {
                    owned?.let { it.destroy(); unwatch(it) }
                }
                if (running && generation == epoch) try { Thread.sleep(5000) } catch (_: InterruptedException) { }
            }
        }
    }

    @Synchronized fun stop() {
        running = false
        generation++
        child?.destroy()
        worker?.interrupt()
        worker = null
    }

    @Synchronized private fun watch(p: Process, epoch: Int) {
        if (!running || generation != epoch) { p.destroy(); error("Printing stopped") }
        child = p
    }

    @Synchronized private fun unwatch(p: Process) { if (child === p) child = null }

    private fun prepareState(epoch: Int) = with(GuestPrintingConfig) {
        // Discover the package's actual driver path, rather than guessing a distro layout.
        val p = proot.process("pacman -Qlq cups-pdf").start()
        watch(p, epoch)
        val paths = try {
            p.inputStream.bufferedReader().use { it.readLines() }.also {
                check(p.waitFor() == 0) { "cups-pdf is not installed yet" }
            }
        } finally { p.destroy(); unwatch(p) }
        val driver = paths.firstOrNull { it.endsWith("/CUPS-PDF_noopt.ppd") }
            ?: paths.firstOrNull { it.endsWith("/CUPS-PDF_opt.ppd") }
            ?: error("cups-pdf supplied no driver")
        synchronized(this@GuestPrinting) {
        check(running && generation == epoch) { "Printing stopped" }
        for (path in listOf(STATE, "$STATE/ppd", SPOOL, "$SPOOL/tmp", "/var/cache/omalauncher/cups", "$OUTPUT/output", "$OUTPUT/tmp", "$OUTPUT/log")) {
            check(proot.hostFile(path).let { it.isDirectory || it.mkdirs() }) { "Cannot create $path" }
        }
        val ppd = proot.hostFile("$STATE/ppd/$QUEUE.ppd")
        val original = if (ppd.exists()) ppd.readText() else proot.hostFile(driver).readText()
        val updated = GuestPrintingConfig.driver(original)
        if (!ppd.exists() || original != updated) {
            // Preserve saved PPD defaults, updating only the owned filter rule.
            dev.omalauncher.upgrade.writeAtomically(ppd, updated.toByteArray(Charsets.UTF_8))
        }
        }
    }
}
