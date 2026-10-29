package dev.omalauncher.linux

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.StringRes
import dev.omalauncher.R
import dev.omalauncher.CountingInputStream
import dev.omalauncher.LauncherData
import dev.omalauncher.hex
import dev.omalauncher.openHttp
import dev.omalauncher.upgrade.Provisioner
import dev.omalauncher.upgrade.StepJournal
import dev.omalauncher.upgrade.writeAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

private const val TAG = "bootstrap"
private const val ROOTFS_URL = "http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz"
private const val TARBALL = "ArchLinuxARM-aarch64-latest.tar.gz"

/** Attempts per network step before setup (or an update) reports a failure. */
private const val NETWORK_TRIES = 3

/** How long `echo | cat` may take in the guest before setup calls the phone's kernel broken; it takes well under a second. */
private const val FORK_CHECK_SECONDS = 30L

/** Room for the download (~830 MB). */
private const val TARBALL_SPACE = 900_000_000L

/** Room for the unpacked system and what setup installs into it (~1.9 GB with an agent), with some to spare. */
private const val SYSTEM_SPACE = 2_100_000_000L

/** A failure that trying again can't fix, like a full disk: a network step stops at once rather than downloading again. */
private class NoRetry(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

private fun Throwable.outOfSpace() =
    generateSequence(this) { it.cause }.any {
        (it is ErrnoException && it.errno == OsConstants.ENOSPC) || it.message?.contains("ENOSPC") == true
    }

private fun gigabytes(bytes: Long) = "%.1f GB".format(bytes / 1e9)

/**
 * Omarchy's classic terminal apps. The Nerd Font glyphs come from the
 * symbols-only font: the full JetBrainsMono Nerd family is ~200 MB.
 * Changing this list needs a new id for the packages step.
 */
private val PACKAGES =
    listOf(
        "foot", "ttf-dejavu", "btop", "fastfetch", "ttf-jetbrains-mono", "ttf-nerd-fonts-symbols-mono",
        "neovim", "starship", "eza", "bat", "fd", "ripgrep", "fzf", "zoxide", "tmux", "gum", "jq", "tealdeer",
        "man-db", "less", "dua-cli", "inxi", "whois", "git", "bash-completion", "pulseaudio", "pulseaudio-alsa", "micro",
    )

/** Omarchy's Winamp-style player; not in Arch's ARM repos, but upstream ships an arm64 build. */
private const val CLIAMP_VERSION = "v2.3.0"
private const val CLIAMP_URL = "https://github.com/bjarneo/cliamp/releases/download/$CLIAMP_VERSION/cliamp-linux-arm64"
private const val CLIAMP_SHA256 = "827b4aa879feecab33ea621dc1460c60ffa765c210b5046d679ce5a4073d2b32"

private val MIRRORS =
    listOf(
        "de.mirror.archlinuxarm.org",
        "fl.us.mirror.archlinuxarm.org",
        "eu.mirror.archlinuxarm.org",
        "dk.mirror.archlinuxarm.org",
        "mirror.archlinuxarm.org",
    )

/** Firmware and kernel modules are useless under proot; pacman never unpacks them. */
private const val NO_EXTRACT = "NoExtract = usr/lib/firmware/* usr/lib/modules/*"

/** What the bootstrap installs; the packages screen doesn't offer to remove these. */
val CORE_PACKAGES = PACKAGES.toSet()

/** The phases of a fresh install, in order, as the setup screen lists them. */
enum class Stage(
    @StringRes val label: Int,
) {
    Download(R.string.linux_stage_download),
    Extract(R.string.linux_stage_extract),
    Configure(R.string.linux_stage_configure),
    Packages(R.string.linux_stage_packages),
    Agents(R.string.linux_stage_agents),
}

/** Where the optional Linux system stands. The launcher works in every state; Linux features only when [Installed]. */
sealed interface LinuxState {
    data object Checking : LinuxState

    /** Never installed, or removed: nothing of it is on disk. */
    data object NotInstalled : LinuxState

    data class Installing(
        val stage: Stage,
        val step: String,
        val progress: Float?,
        val log: List<String>,
    ) : LinuxState

    /** An install, or an update that must finish before Linux runs, stopped; [Bootstrap.run] retries it. */
    data class Failed(
        val stage: Stage,
        val message: String,
        val log: List<String>,
    ) : LinuxState

    data object Installed : LinuxState

    data object Removing : LinuxState
}

/**
 * One idempotent change to the rootfs. Once it succeeds its [id] is recorded
 * and it doesn't run again; to bring a changed step to existing installs,
 * give it a new id. [network] steps of an update run in the background, so
 * an offline phone still reaches the home screen. [isDone], when set, reads
 * the step's state from disk instead of the journal.
 */
private class Step(
    val id: String,
    val stage: Stage,
    @StringRes val label: Int,
    val network: Boolean,
    /** A failure doesn't stop a fresh install: the system works without it, and it's retried later. */
    val optional: Boolean = false,
    val isDone: (() -> Boolean)? = null,
    val run: suspend () -> Unit,
)

/**
 * Sets up the Arch Linux ARM rootfs and keeps it current. A fresh install
 * runs every [steps] entry behind the setup screen; after an app update only
 * the new ones run, see [Step].
 */
class Bootstrap(
    private val context: Context,
    private val proot: Proot,
    private val agents: AgentStore,
    private val pacman: Pacman,
    /** The guest's files; see [Provisioner]. */
    private val provisioner: Provisioner,
) {
    private val _state = MutableStateFlow<LinuxState>(LinuxState.Checking)
    val state = _state.asStateFlow()

    val isInstalled get() = _state.value == LinuxState.Installed

    /** An install was started (and may still need to finish), or one finished. */
    val wanted get() = journal.setupComplete || choiceFile.exists()

    /** Update steps running after the home screen is up; Idle when there are none. */
    private val _updates = MutableStateFlow<JobState>(JobState.Idle)
    val updates = _updates.asStateFlow()

    private val log = OutputTail(200)
    private val journal = StepJournal(File(proot.filesDir, "rootfs-steps.json"))

    /** The agents picked when installing Linux, one bin per line; its existence means an install was started. */
    private val choiceFile get() = File(proot.filesDir, "setup-agents")

    /** The coding agents setup installs, in the order they were listed. */
    val chosenAgents: List<Agent>
        get() = runCatching { choiceFile.readLines() }.getOrDefault(emptyList()).mapNotNull { bin -> AGENTS.find { it.bin == bin } }

    fun choose(picked: List<Agent>) {
        proot.filesDir.mkdirs()
        choiceFile.writeText(picked.joinToString("") { it.bin + "\n" })
    }

    private var stage = Stage.Download
    private var step = ""

    private val steps =
        listOf(
            Step("rootfs", Stage.Download, R.string.linux_step_download, network = true) { installRootfs() },
            Step("etc-2", Stage.Configure, R.string.linux_step_configure, network = false) { configure() },
            // Only before the first setup: a phone where Linux already runs can fork.
            Step("forks-1", Stage.Configure, R.string.linux_step_forks, network = false, isDone = { journal.setupComplete }) {
                checkForks()
            },
            Step("keyring-1", Stage.Configure, R.string.linux_step_keyring, network = false) {
                guest("pacman-key --init && pacman-key --populate archlinuxarm")
            },
            // The ALARM image ships a kernel and firmware (~1 GB, split into sub-packages) we can't use.
            Step("no-kernel-1", Stage.Configure, R.string.linux_step_no_kernel, network = false) {
                guest("pacman -Rdd --noconfirm linux-aarch64 \$(pacman -Qq | grep ^linux-firmware); true")
            },
            Step("packages-1", Stage.Packages, R.string.linux_step_packages, network = true) { guest(pacmanInstall(PACKAGES, refresh = true)) },
            Step("keymap-1", Stage.Packages, R.string.linux_step_keymap, network = false, isDone = { proot.keymap.exists() }) { compileKeymap() },
            Step("cliamp-$CLIAMP_VERSION", Stage.Packages, R.string.linux_step_cliamp, network = true) { installCliamp() },
            // Omarchy's themes were a step here ("omarchy-<ref>") until Linux became optional; ThemeStore fetches them now.
        )

    private fun Step.done() = isDone?.invoke() ?: (id in journal)

    /**
     * Runs the step; one that needs the network gets a few tries, since phone connections drop.
     * Retrying is cheap: pacman keeps what it already downloaded, and downloads resume.
     */
    private suspend fun Step.attempt() {
        repeat(if (network) NETWORK_TRIES - 1 else 0) { n ->
            try {
                return run()
            } catch (e: Exception) {
                if (e is NoRetry || e is CancellationException) throw e
                log.add(text(R.string.linux_log_retrying, "${e.message}", n + 2, NETWORK_TRIES))
                kotlinx.coroutines.delay(5_000L * (n + 1))
            }
        }
        run()
    }

    /**
     * Brings Linux to [LinuxState.Installed] if it's wanted: updates an
     * installed system, or continues an install that was started (also
     * after the app was killed during it). Otherwise reports NotInstalled.
     */
    suspend fun run() =
        withContext(Dispatchers.IO) {
            try {
                when {
                    journal.setupComplete -> update()
                    !choiceFile.exists() -> {
                        _state.value = LinuxState.NotInstalled
                        // Left by a removal that didn't finish (the app was killed, or a file resisted).
                        if (proot.rootfs.exists()) runCatching { LauncherData.deleteTree(proot.rootfs) }.onFailure { Log.e(TAG, "leftover rootfs", it) }
                        return@withContext
                    }
                    else -> setup()
                }
                _state.value = LinuxState.Installed
            } catch (e: Throwable) {
                // A cancelled install is about to be removed; it didn't fail.
                if (cancelled) {
                    Log.i(TAG, "setup cancelled")
                    return@withContext
                }
                // Errors too (out of memory): otherwise the screen shows the step running forever.
                Log.e(TAG, "setup failed", e)
                _state.value = LinuxState.Failed(stage, e.message ?: e.toString(), log.toList())
            }
        }

    @Volatile private var cancelled = false

    @Volatile private var connection: HttpURLConnection? = null

    /** Whether the install can still be cancelled: until setup completes. An update of an installed system can't. */
    val cancellable get() = !journal.setupComplete && !cancelled

    /**
     * Stops an install that hasn't completed: the download and the unpack stop at their next block. The caller kills
     * guest commands, waits for [run] to return, then calls [remove]. False when there's nothing to cancel.
     */
    fun cancel(): Boolean {
        if (!cancellable) return false
        cancelled = true
        connection?.disconnect()
        return true
    }

    private fun checkCancelled() {
        if (cancelled) throw CancellationException("cancelled")
    }

    /** Starts installing Linux with [picked] coding agents; see [run]. */
    suspend fun install(picked: List<Agent>) {
        choose(picked)
        run()
    }

    /**
     * Deletes the rootfs and what we recorded about it. Callers stop guest
     * processes first. Launcher data (themes, widgets) lives outside it and stays.
     */
    suspend fun remove() =
        withContext(Dispatchers.IO) {
            _state.value = LinuxState.Removing
            backlog = emptyList()
            pacman.lock.withLock {
                try {
                    LauncherData.deleteTree(proot.rootfs)
                } catch (e: Exception) {
                    // Whatever is left can't run; a later install unpacks over a fresh directory anyway.
                    Log.e(TAG, "rootfs not fully deleted", e)
                }
                journal.reset()
                provisioner.forgetAll()
                choiceFile.delete()
                proot.keymap.delete()
                context.cacheDir.listFiles { f -> f.name.startsWith(TARBALL) }?.forEach { it.delete() }
                log.clear()
                cancelled = false
            }
            _state.value = LinuxState.NotInstalled
        }

    /**
     * A fresh install: every step, in order. A failure stops here and a
     * retry picks up at the failed step. Agents are extras: the system
     * works without them, and settings can retry.
     */
    private suspend fun setup() {
        for (step in steps) {
            if (step.done()) continue
            checkCancelled()
            report(step.stage, text(step.label))
            try {
                step.attempt()
                journal.mark(step.id)
            } catch (e: Exception) {
                if (!step.optional || e is CancellationException) throw e
                log.add(text(R.string.linux_log_step_failed, text(step.label), "${e.message}"))
            }
        }
        installAgents()
        checkCancelled()
        journal.completeSetup()
    }

    /**
     * After an app update: offline steps run before the home screen, so it
     * never shows a half-configured system; from the first step that needs
     * the network on, the rest continue in the background (see [updates]).
     */
    private suspend fun update() {
        val pending = steps.filterNot { it.done() }
        val blocking = pending.takeWhile { !it.network }
        for (step in blocking) {
            report(step.stage, text(step.label))
            step.run()
            journal.mark(step.id)
        }
        backlog = pending.drop(blocking.size)
    }

    private var backlog: List<Step> = emptyList()

    private val backlogRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Runs update steps that need the network; call once the home screen is up. Safe to call again to retry. */
    suspend fun runBacklog() {
        if (!backlogRunning.compareAndSet(false, true)) return
        try {
            runSteps()
        } finally {
            backlogRunning.set(false)
        }
    }

    private suspend fun runSteps() =
        withContext(Dispatchers.IO) {
            val todo = backlog.filterNot { it.done() }
            if (todo.isEmpty()) return@withContext
            for (step in todo) {
                _updates.value = JobState.Working(step.label, emptyList())
                try {
                    // Package steps run pacman; nothing else may hold its lock meanwhile.
                    pacman.lock.withLock { step.attempt() }
                    journal.mark(step.id)
                } catch (e: Throwable) {
                    Log.w(TAG, "update step ${step.id} failed", e)
                    _updates.value = JobState.Failed(log.toList().takeLast(12) + (e.message ?: e.toString()))
                    return@withContext
                }
            }
            _updates.value = JobState.Idle
        }

    /**
     * Installs the agents picked on the welcome screen. A failed agent doesn't
     * fail setup: it can be installed again from settings.
     */
    private suspend fun installAgents() {
        val chosen = chosenAgents
        for (agent in chosen) {
            if (agents.isInstalled(agent)) continue
            checkCancelled()
            try {
                report(Stage.Agents, text(R.string.linux_step_agent, agent.name))
                guest(agents.installScript(agent))
                agents.onInstalled(agent)
            } catch (e: IllegalStateException) {
                log.add(text(R.string.linux_log_agent_failed, agent.name))
            }
        }
        chosen.firstOrNull { agents.isInstalled(it) }?.let(agents::choose)
    }

    private fun report(
        stage: Stage,
        step: String,
        progress: Float? = null,
    ) {
        this.stage = stage
        this.step = step
        if (journal.setupComplete && _state.value == LinuxState.Installed) return
        _state.value = LinuxState.Installing(stage, step, progress, log.toList())
    }

    /** Always a fresh unpack: this step only runs until one completed, so a rootfs here is a half-unpacked one. */
    private fun installRootfs() {
        // Unpacking replaces the rootfs: the user's home, packages and agents. Only ever before a setup finished.
        check(!journal.setupComplete) { "refusing to replace a set-up system" }
        // A half-unpacked rootfs from an earlier try only takes up room the download needs.
        LauncherData.deleteTree(proot.rootfs)
        val tarball =
            try {
                obtainTarball()
            } catch (e: Exception) {
                throw if (e.outOfSpace()) outOfSpace(e) else e
            }
        try {
            extract(tarball)
        } catch (e: Exception) {
            if (e is NoRetry || e is CancellationException || e.outOfSpace()) {
                // The download is fine and kept for the next try; what was unpacked gives its room back.
                runCatching { LauncherData.deleteTree(proot.rootfs) }
                throw if (e is NoRetry || e is CancellationException) e else outOfSpace(e)
            }
            // A truncated or bogus download (a captive portal's page) would fail every retry otherwise.
            if (tarball.parentFile == context.cacheDir) tarball.delete()
            throw e
        }
        tarball.delete() // ~830 MB we no longer need
    }

    /** Stops before writing when the app's storage has less than [bytes] free: a full phone fails at once, not after the download. */
    private fun requireSpace(bytes: Long) {
        val free = proot.filesDir.usableSpace
        if (free < bytes) {
            throw NoRetry(text(R.string.linux_error_no_space, gigabytes(bytes), gigabytes(free)))
        }
    }

    private fun outOfSpace(e: Exception) =
        NoRetry(text(R.string.linux_error_out_of_space, gigabytes(TARBALL_SPACE + SYSTEM_SPACE)), e)

    /**
     * Prefers a tarball pushed via adb to the app's external files dir; downloads otherwise.
     * An interrupted download resumes where it stopped, as long as the file on the server is
     * still the same one (If-Range): "latest" moves, and two versions mustn't be spliced.
     */
    private fun obtainTarball(): File {
        val external = context.getExternalFilesDir(null)?.let { File(it, TARBALL) }
        if (external != null && external.exists()) return external
        val target = File(context.cacheDir, TARBALL)
        if (target.exists()) return target
        val partial = File(context.cacheDir, "$TARBALL.part")
        val validator = File(context.cacheDir, "$TARBALL.version")
        requireSpace(TARBALL_SPACE - (if (partial.exists()) partial.length() else 0L) + SYSTEM_SPACE)
        report(Stage.Download, text(R.string.linux_step_download), 0f)
        val conn = openHttp(ROOTFS_URL)
        connection = conn
        try {
            checkCancelled()
            download(conn, partial, validator)
        } finally {
            connection = null
            conn.disconnect()
        }
        checkCancelled()
        // Only a complete download is kept for a retry.
        check(partial.renameTo(target)) { text(R.string.linux_error_move_download) }
        validator.delete()
        return target
    }

    /** Into [partial], continuing it if the server still has the version [validator] names. Throws if it ends short. */
    private fun download(
        conn: HttpURLConnection,
        partial: File,
        validator: File,
    ) {
        val have = if (partial.exists() && validator.exists()) partial.length() else 0L
        if (have > 0) {
            conn.setRequestProperty("Range", "bytes=$have-")
            conn.setRequestProperty("If-Range", validator.readText())
        }
        val resumed = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (conn.responseCode !in 200..299) error(text(R.string.linux_error_http, conn.responseCode))
        val start = if (resumed) have else 0L
        val total = conn.contentLengthLong.let { if (it > 0) it + start else -1L }
        (conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified"))?.let(validator::writeText) ?: validator.delete()
        conn.inputStream.use { input ->
            java.io.FileOutputStream(partial, resumed).use { out ->
                val buf = ByteArray(1 shl 16)
                var done = start
                var lastReport = 0L
                while (true) {
                    checkCancelled()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 4 shl 20) {
                        lastReport = done
                        report(
                            Stage.Download,
                            if (total > 0) text(R.string.linux_step_downloaded, done shr 20, total shr 20) else text(R.string.linux_step_downloaded_unknown, done shr 20),
                            if (total > 0) done.toFloat() / total else null,
                        )
                    }
                }
            }
        }
        check(total <= 0 || partial.length() == total) { text(R.string.linux_error_cut_short) }
    }

    private fun extract(tarball: File) {
        report(Stage.Extract, text(R.string.linux_step_extract_start), 0f)
        val root = proot.rootfs
        LauncherData.deleteTree(root)
        requireSpace(SYSTEM_SPACE)
        root.mkdirs()
        // Nothing we recorded about the old rootfs holds for this one.
        journal.reset()
        provisioner.forgetAll()
        val total = tarball.length()
        val counter = CountingInputStream(tarball.inputStream())
        TarArchiveInputStream(GZIPInputStream(BufferedInputStream(counter, 1 shl 16), 1 shl 16)).use { tar ->
            var count = 0
            while (true) {
                checkCancelled()
                val entry = tar.nextEntry ?: break
                val name = entry.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty() || skipEntry(name)) continue
                val file = File(root, name)
                file.parentFile?.mkdirs()
                when {
                    entry.isDirectory -> file.mkdirs()
                    entry.isSymbolicLink -> {
                        file.delete()
                        Os.symlink(entry.linkName, file.path)
                    }
                    // Hard links aren't allowed in app storage; copy the target instead.
                    entry.isLink -> {
                        val target = File(root, entry.linkName.removePrefix("./"))
                        if (target.exists()) target.copyTo(file, overwrite = true)
                    }
                    entry.isFile -> file.outputStream().use { tar.copyTo(it) }
                    else -> continue // device nodes, fifos
                }
                if (!entry.isSymbolicLink) runCatching { Os.chmod(file.path, entry.mode and 0x1ff or 0x180) }
                if (++count % 500 == 0) {
                    report(Stage.Extract, context.resources.getQuantityString(R.plurals.linux_step_extracted, count, count), counter.count.toFloat() / total)
                }
            }
        }
    }

    private fun skipEntry(name: String) =
        name == "boot" ||
            name.startsWith("boot/") ||
            name.startsWith("usr/lib/firmware/") ||
            name.startsWith("usr/lib/modules/") ||
            name.startsWith("dev/")

    private fun configure() {
        val root = proot.rootfs
        File(root, "etc/resolv.conf").apply {
            delete()
            writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(root, "etc/locale.conf").writeText("LANG=C.UTF-8\n")
        // The geo-balanced default mirror often lags behind; pacman falls through this list on 404s.
        File(root, "etc/pacman.d/mirrorlist").writeText(MIRRORS.joinToString("") { "Server = http://$it/\$arch/\$repo\n" })
        // pacman drops privileges and uses Landlock for downloads; neither works under proot.
        // Its low-speed timeout aborts big packages on slow phone connections, every retry.
        val conf = File(root, "etc/pacman.conf")
        conf.writeText(
            conf
                .readText()
                .replace(Regex("(?m)^(DownloadUser|CheckSpace|DisableSandbox|DisableDownloadTimeout|NoExtract)\\b.*\n"), "")
                .replaceFirst("[options]", "[options]\nDisableSandbox\nDisableDownloadTimeout\n$NO_EXTRACT"),
        )
    }

    private fun compileKeymap() {
        guest(
            "xkbcli compile-keymap --layout us > /tmp/keymap.xkb || " +
                "/usr/lib/xkbcommon/xkbcli-compile-keymap --layout us > /tmp/keymap.xkb",
        )
        // Clients mmap the keymap and parse it as a C string.
        writeAtomically(proot.keymap, proot.hostFile("/tmp/keymap.xkb").readBytes() + 0)
    }

    /**
     * Streamed to disk and hashed on the way: the binary is ~36 MB, and holding it while a buffer
     * doubles needs more heap than small phones give an app (48 MB on a 2 GB Android 10 device).
     */
    private fun installCliamp() {
        val target = File(proot.rootfs, "usr/local/bin/cliamp")
        val tmp = File(target.parentFile, ".cliamp.download")
        val digest = MessageDigest.getInstance("SHA-256")
        // Runs under pacman's lock, so a stalled download must time out rather than hold it.
        val conn = openHttp(CLIAMP_URL, connectTimeout = 10_000, readTimeout = 20_000)
        try {
            conn.useCaches = false
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $CLIAMP_URL")
            target.parentFile?.mkdirs()
            DigestInputStream(conn.inputStream, digest).use { input ->
                tmp.outputStream().use { out ->
                    input.copyTo(out, 1 shl 16)
                    out.fd.sync()
                }
            }
        } finally {
            conn.disconnect()
        }
        if (digest.digest().hex() != CLIAMP_SHA256) {
            tmp.delete()
            error(text(R.string.linux_error_cliamp_mismatch, CLIAMP_VERSION))
        }
        tmp.setExecutable(true, false)
        // One rename, so a running cliamp is never replaced by half a file.
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error(text(R.string.linux_error_cant_replace, target))
        }
    }

    /**
     * Runs a pipeline, the smallest guest command that forks. Some custom ROM kernels (4.14, with Android's cgroup v2
     * freezer backported but not its follow-up, upstream 05b289263772) make every fork of a traced process restart
     * forever (#105), and the next step, pacman-key, would hang without a word. Such a phone gets an error naming the cause.
     */
    private fun checkForks() {
        val process = proot.process("echo forks | cat").start()
        val output = StringBuffer()
        val reader = thread(isDaemon = true) { runCatching { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } } }
        if (process.waitFor(FORK_CHECK_SECONDS, TimeUnit.SECONDS)) {
            reader.join(1_000)
            output.lines().forEach(log::add)
            check(process.exitValue() == 0 && "forks" in output) { text(R.string.linux_error_cant_run, process.exitValue()) }
            return
        }
        process.destroyForcibly()
        // The guest shell is proot's tracee and outlives a killed proot.
        proot.killAll()
        log.add("a guest command that starts another one didn't finish in $FORK_CHECK_SECONDS s (kernel ${System.getProperty("os.version")})")
        error(text(R.string.linux_error_kernel))
    }

    private fun text(
        @StringRes id: Int,
        vararg args: Any,
    ) = context.getString(id, *args)

    private fun guest(script: String) {
        log.add("$ $script")
        val code = proot.run(script) { line -> if (log.add(line)) report(stage, step) }
        check(code == 0) { text(R.string.linux_error_command, script.take(60), code) }
    }
}
