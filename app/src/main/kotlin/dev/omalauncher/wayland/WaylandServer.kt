package dev.omalauncher.wayland

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private const val TAG = "wl.server"

/** Deepest chain of subsurface and popup parents we follow. */
internal const val MAX_NESTING = 32

data class OutputInfo(
    val widthPx: Int,
    val heightPx: Int,
    val scale: Int,
    val physicalWidthMm: Int,
    val physicalHeightMm: Int,
)

/** Receives window lifecycle events; called on protocol threads with the server locked. */
interface WindowManagerHooks {
    fun toplevelCreated(toplevel: XdgToplevel)

    fun toplevelDestroyed(toplevel: XdgToplevel)
}

/**
 * A minimal Wayland compositor core. Protocol handling runs on one thread per
 * client, serialized by locking on the server; the UI locks it too when it
 * delivers input or configures windows. Pixels never move under the lock:
 * see [Client.defer] and [WindowRenderer].
 */
class WaylandServer(
    private val socketPath: String,
    val windowManager: WindowManagerHooks,
) {
    val globals =
        listOf(
            Global("wl_compositor", 4, ::CompositorResource),
            Global("wl_subcompositor", 1, ::SubcompositorResource),
            Global("wl_shm", 1, ::ShmResource),
            Global("wl_output", 3, ::OutputResource),
            Global("wl_seat", 5, ::SeatResource),
            Global("wl_data_device_manager", 3, ::DataDeviceManager),
            Global("xdg_wm_base", 3, ::XdgWmBase),
            Global("zxdg_decoration_manager_v1", 1, ::DecorationManager),
            Global("zwp_text_input_manager_v3", 1, ::TextInputManager),
            Global("org_kde_kwin_server_decoration_manager", 1, ::ServerDecorationManager),
        )

    val clients = mutableListOf<Client>()

    /** Mapped popups of all windows, oldest first. */
    val popups = mutableListOf<XdgPopup>()
    val input = Input(this)
    val selection = Selection(this)

    @Volatile
    var output = OutputInfo(1080, 1920, 2, 70, 150)
        private set

    /** Input from the UI thread is applied here, in order, so the UI never waits on the server lock. */
    private val inputThread = Executors.newSingleThreadExecutor { Thread(it, "wl-input") }

    fun postInput(block: Input.() -> Unit) = inputThread.execute { synchronized(this) { input.block() } }

    /** Called from the UI when Android's clipboard changed; see [Selection.systemChanged]. */
    fun clipboardChanged() = inputThread.execute { synchronized(this) { selection.systemChanged() } }

    /** Offers [text] to the focused window's next paste, ahead of the clipboard; see [Selection.insert]. */
    fun insertText(text: String) = inputThread.execute { synchronized(this) { selection.insert(text) } }

    private val serial = AtomicInteger(1)
    private var listenFd = -1

    fun nextSerial() = serial.getAndIncrement()

    fun start() {
        listenFd = Native.listen(socketPath)
        check(listenFd >= 0) { "cannot listen on $socketPath: errno ${-listenFd}" }
        Log.i(TAG, "listening on $socketPath")
        thread(name = "wl-accept", isDaemon = true) {
            while (true) {
                val fd = Native.accept(listenFd)
                if (fd < 0) {
                    // Transient (a client gave up, out of fds): keep serving after a pause.
                    Log.w(TAG, "accept failed: errno ${-fd}")
                    SystemClock.sleep(100)
                    continue
                }
                val client = Client(this, fd)
                synchronized(this) { clients += client }
                thread(name = "wl-client-$fd", isDaemon = true) { client.run() }
            }
        }
    }

    fun outputsOf(client: Client) = client.all(OutputResource::class.java)

    @Synchronized
    fun updateOutput(info: OutputInfo) {
        if (info == output) return
        output = info
        clients.forEach { c -> outputsOf(c).forEach { it.sendState() } }
    }

    /** The toplevel owning [surface], following subsurface and popup parents. */
    fun toplevelOf(surface: Surface): XdgToplevel? {
        var s: Surface? = surface
        // A buggy client can make parents loop (a popup that's its own parent): give up rather than spin under the lock.
        var hops = 0
        while (s != null && hops++ < MAX_NESTING) {
            when (val role = s.role) {
                is XdgSurface -> {
                    role.toplevel?.let { return it }
                    s = role.popup?.parent?.surface
                }
                is Subsurface -> s = role.parent
                else -> return null
            }
        }
        return null
    }

    /** Called with the lock held after [surface] committed: its window redraws on the next vsync. */
    fun surfaceCommitted(surface: Surface) {
        val renderer = toplevelOf(surface)?.renderer
        when {
            renderer != null -> renderer.requestFrame()
            // Not part of a window (a cursor, or a window without its activity yet): nothing to draw,
            // but a client may wait on its frame callbacks, so pace them with vsync.
            surface.wantsFrame && toplevelOf(surface) == null -> orphans.schedule(surface)
        }
    }

    /** Called without the lock once a commit's pixels are in [surface]'s store. */
    fun contentChanged(surface: Surface) {
        synchronized(this) { toplevelOf(surface)?.renderer }?.requestFrame()
    }

    @Synchronized
    fun surfaceDestroyed(surface: Surface) {
        input.surfaceDestroyed(surface)
        orphans.remove(surface)
    }

    private val orphans = OrphanFrames()

    /** Frame callbacks for surfaces no window draws, sent on the main thread's vsync. */
    private inner class OrphanFrames {
        private val main = Handler(Looper.getMainLooper())
        private val choreographer by lazy { Choreographer.getInstance() }
        private val waiting = LinkedHashSet<Surface>()
        private var scheduled = false

        fun schedule(surface: Surface) {
            waiting += surface
            if (scheduled) return
            scheduled = true
            main.post { choreographer.postFrameCallback(::flush) }
        }

        fun remove(surface: Surface) {
            waiting -= surface
        }

        private fun flush(nanos: Long) {
            synchronized(this@WaylandServer) {
                scheduled = false
                for (s in waiting) s.flushFrames((nanos / 1_000_000).toInt())
                waiting.clear()
            }
        }
    }
}
