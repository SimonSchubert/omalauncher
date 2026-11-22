package dev.omalauncher.linux

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import dev.omalauncher.upgrade.writeAtomically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import kotlin.concurrent.thread

private const val TAG = "devicesync"

/**
 * This phone following other devices, as an Omarchy computer's plugin does: the other half of [ComputerLink], which
 * lets devices follow this one. The work is omalauncher-sync (assets/omalauncher-sync.sh, the plugin's script, the
 * same file), run in the guest: connecting (a QR code another device scans), watching each connected device, and
 * keeping widgets, their values, the theme and the background the same, the newest winning. What it changes on this
 * phone goes through the launcher (`omalauncher link <verb> self`, see [ComputerLink.handle]).
 *
 * The launcher runs its follower while Linux is up and a device is connected, and finds devices on the network for it
 * (Android's NSD; the guest can't run avahi), in /run/user/0/omalauncher-devices.
 */
class DeviceSync(
    private val context: Context,
    private val proot: Proot,
    /** This phone's own id, which discovery leaves out. */
    private val ownId: () -> String,
    /** Connected devices are paused (ComputerLink.paused): nothing is followed or asked. */
    private val paused: () -> Boolean,
    /** Main. */
    private val scope: CoroutineScope,
) {
    /** A device this phone follows, as omalauncher-sync keeps it ([sync]: its settings there, null until asked). */
    data class Device(
        val id: String,
        val name: String,
        val sync: JSONObject? = null,
    )

    var devices by mutableStateOf<List<Device>>(emptyList())
        private set

    /** `omalauncher devices connect` asked for a code on screen: the connected devices page shows one when it opens. */
    var showCode by mutableStateOf(false)

    private val stateDir get() = File(proot.rootfs, "root/.local/state/omalauncher/sync")
    private val phonesDir get() = File(stateDir, "phones")

    fun managed(): List<Managed> =
        listOf(Managed.asset(context, SCRIPT, File(proot.rootfs, "usr/local/bin/$COMMAND"), Policy.Owned, executable = true))

    @Volatile
    private var linux = false

    fun linuxChanged(up: Boolean) {
        linux = up
        reload()
    }

    /** The connected devices, from omalauncher-sync's files, and then each one's settings there. Main thread. */
    fun reload() {
        val list =
            phonesDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
                runCatching { JSONObject(f.readText()).let { Device(it.getString("id"), it.optString("name", "device"), devices.firstOrNull { d -> d.id == it.getString("id") }?.sync) } }.getOrNull()
            }.sortedBy { it.name }
        devices = list
        update()
        if (linux && !paused()) for (d in list) scope.launch { loadSync(d.id) }
    }

    /** Paused or resumed: the follower stops or starts. Main thread. */
    fun pauseChanged() = reload()

    /** What syncs with device [id], as its settings say. */
    suspend fun loadSync(
        id: String,
        vararg change: String,
    ) {
        val out = command("--device=${shellQuote(id)} sync " + change.joinToString(" ") { shellQuote(it) }) ?: return
        val json = lastObject(out) ?: return
        devices = devices.map { if (it.id == id) it.copy(sync = json) else it }
    }

    fun setSync(
        id: String,
        what: String,
        on: Boolean,
    ) {
        scope.launch { loadSync(id, what, if (on) "on" else "off") }
    }

    fun setWidget(
        id: String,
        file: String,
        on: Boolean,
    ) {
        scope.launch { loadSync(id, "widget", file, if (on) "on" else "off") }
    }

    fun disconnect(id: String) {
        scope.launch {
            command("disconnect ${shellQuote(id)}")
            reload()
        }
    }

    // ---- connecting -------------------------------------------------------------------------------------------------

    /** A code for another device to scan: its link (drawn as a QR code by the launcher), and the digits it shows. */
    data class Invite(
        val link: String,
        val code: String,
    )

    /** Makes a code; null if it couldn't (no Linux). */
    suspend fun invite(): Invite? {
        // The launcher draws the code: Linux may not have qrencode.
        val out = command("invite") ?: return null
        val json = lastObject(out) ?: return null
        return runCatching { Invite(json.getString("link"), json.getString("code")) }.getOrNull()
    }

    private var waiting: Process? = null

    /** Waits up to two minutes (omalauncher-sync's INVITE_SECONDS) for the device that scanned the code: its name once connected, null if none did. */
    suspend fun await(): String? {
        discovering("wait", true)
        try {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val p = proot.process(env("wait")).start()
                        waiting = p
                        val text = p.inputStream.bufferedReader().readText()
                        if (p.waitFor() == 0) text.lineSequence().firstOrNull { it.startsWith("Connected ") }?.removePrefix("Connected ") else null
                    }.getOrNull()
                }
            reload()
            return result
        } finally {
            waiting = null
            discovering("wait", false)
        }
    }

    /** Stops waiting: the code was closed. */
    fun cancel() {
        waiting?.let { p -> thread(isDaemon = true) { killTree(p) } }
    }

    // ---- the follower --------------------------------------------------------------------------------------------------

    private var follower: Process? = null
    private var supervisor: Thread? = null

    @Volatile
    private var wanted = false

    private fun update() {
        wanted = linux && !paused() && devices.isNotEmpty()
        discovering("follow", wanted)
        if (wanted && supervisor == null) {
            supervisor = thread(name = "device-sync", isDaemon = true) { supervise() }
        } else if (!wanted) {
            follower?.let { p -> thread(isDaemon = true) { killTree(p) } }
        }
    }

    private fun supervise() {
        var backoff = 5_000L
        while (true) {
            if (!wanted) {
                Thread.sleep(1000)
                continue
            }
            val code =
                runCatching {
                    val p = proot.process(env("follow")).start()
                    follower = p
                    p.inputStream.bufferedReader().forEachLine { Log.i(TAG, it) }
                    p.waitFor()
                }.getOrElse { -1 }
            follower = null
            if (!wanted) continue
            Log.w(TAG, "follower exited $code; again in ${backoff / 1000} s")
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    // ---- discovery -------------------------------------------------------------------------------------------------------

    private val found = LinkedHashMap<String, String>()
    private var discovery: NsdManager.DiscoveryListener? = null
    private val discoveryUsers = HashSet<String>()

    /** Android's NSD, for the guest: `host port id` of each device found, in the runtime dir, while [who] needs it. */
    private fun discovering(
        who: String,
        on: Boolean,
    ) {
        scope.launch {
            val nsd = context.getSystemService(NsdManager::class.java) ?: return@launch
            if (on) discoveryUsers += who else discoveryUsers -= who
            if (discoveryUsers.isNotEmpty() && discovery == null) {
                val listener =
                    object : NsdManager.DiscoveryListener {
                        override fun onServiceFound(info: NsdServiceInfo) = resolve(nsd, info)

                        override fun onServiceLost(info: NsdServiceInfo) {
                            scope.launch {
                                found.remove(info.serviceName)
                                writeFound()
                            }
                        }

                        override fun onDiscoveryStarted(type: String) = Unit

                        override fun onDiscoveryStopped(type: String) = Unit

                        override fun onStartDiscoveryFailed(
                            type: String,
                            error: Int,
                        ) {
                            Log.w(TAG, "discovery failed: $error")
                        }

                        override fun onStopDiscoveryFailed(
                            type: String,
                            error: Int,
                        ) = Unit
                    }
                discovery = listener
                runCatching { nsd.discoverServices(ComputerLink.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { Log.w(TAG, "no discovery", it) }
            } else if (discoveryUsers.isEmpty() && discovery != null) {
                discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
                discovery = null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(
        nsd: NsdManager,
        info: NsdServiceInfo,
    ) {
        runCatching {
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(
                        info: NsdServiceInfo,
                        error: Int,
                    ) = Unit

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val id = info.attributes["id"]?.let { String(it) }?.takeIf { it.all(Char::isLetterOrDigit) } ?: return
                        if (id == ownId()) return
                        // IPv4: what ssh is given as it is; a link-local IPv6 address would need its interface.
                        val host = (info.host as? Inet4Address)?.hostAddress ?: return
                        scope.launch {
                            found[info.serviceName] = "$host ${info.port} $id"
                            writeFound()
                        }
                    }
                },
            )
        }
    }

    private fun writeFound() {
        val text = found.values.joinToString("") { "$it\n" }
        runCatching { writeAtomically(File(proot.runtimeDir, "omalauncher-devices"), text.toByteArray()) }
    }

    // ---- guest commands ---------------------------------------------------------------------------------------------

    private fun env(args: String) =
        "OMALAUNCHER_SYNC_HOST=omalauncher OMALAUNCHER_SYNC_NAME=${shellQuote(phoneName())} exec $COMMAND $args"

    /** omalauncher-sync [args] in the guest: what it printed, or null if it failed or Linux isn't up. */
    private suspend fun command(args: String): String? =
        withContext(Dispatchers.IO) {
            if (!linux || paused()) return@withContext null
            runCatching {
                val out = StringBuilder()
                val code = proot.run(env(args)) { out.append(it).append('\n') }
                if (code == 0) out.toString() else null.also { Log.w(TAG, "$args: exit $code: $out") }
            }.getOrNull()
        }

    companion object {
        const val COMMAND = "omalauncher-sync"
        private const val SCRIPT = "$COMMAND.sh"

        /** The last JSON object [out] printed, on one line or several. */
        fun lastObject(out: String): JSONObject? =
            out.lineSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.lastOrNull()
                ?: runCatching { JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1)) }.getOrNull()

        fun phoneName() = Build.MODEL.orEmpty().ifEmpty { "device" }

        /** The widgets synced with a device, as it said last ([JSONObject] from `sync`). */
        fun widgetsOf(sync: JSONObject?): List<String> = sync?.optJSONArray("widgets")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
    }
}
