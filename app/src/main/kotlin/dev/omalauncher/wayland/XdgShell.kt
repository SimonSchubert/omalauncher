package dev.omalauncher.wayland

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class Geometry(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

class XdgWmBase(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "xdg_wm_base"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1 -> client.register(XdgPositioner(client, args.newId(), version))
            2 -> {
                val newId = args.newId()
                val surface = client.get<Surface>(args.obj()) ?: return
                client.register(XdgSurface(client, newId, version, surface))
            }
            3 -> Unit // pong
        }
    }
}

class XdgPositioner(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "xdg_positioner"

    var width = 1
    var height = 1
    var anchorRect = Geometry(0, 0, 1, 1)
    var anchor = 0
    var gravity = 0
    var offsetX = 0
    var offsetY = 0

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1 -> {
                width = args.int()
                height = args.int()
            }
            2 -> anchorRect = Geometry(args.int(), args.int(), args.int(), args.int())
            3 -> anchor = args.uint()
            4 -> gravity = args.uint()
            5 -> Unit // constraint adjustment
            6 -> {
                offsetX = args.int()
                offsetY = args.int()
            }
        }
    }

    /** Popup position relative to the parent's window geometry, ignoring constraints. */
    fun position(): Geometry {
        val r = anchorRect
        val ax =
            when (anchor) {
                3, 5, 6 -> r.x
                4, 7, 8 -> r.x + r.width
                else -> r.x + r.width / 2
            }
        val ay =
            when (anchor) {
                1, 5, 7 -> r.y
                2, 6, 8 -> r.y + r.height
                else -> r.y + r.height / 2
            }
        val x =
            when (gravity) {
                3, 5, 6 -> ax - width
                4, 7, 8 -> ax
                else -> ax - width / 2
            }
        val y =
            when (gravity) {
                1, 5, 7 -> ay - height
                2, 6, 8 -> ay
                else -> ay - height / 2
            }
        return Geometry(x + offsetX, y + offsetY, width, height)
    }
}

class XdgSurface(
    client: Client,
    id: Int,
    version: Int,
    val surface: Surface,
) : Resource(client, id, version),
    SurfaceRole {
    override val iface = "xdg_surface"

    var toplevel: XdgToplevel? = null
    var popup: XdgPopup? = null

    /** Window geometry in surface coordinates; defaults to the whole surface. */
    var geometry: Geometry? = null
        private set
    private var pendingGeometry: Geometry? = null

    var initialCommitDone = false
    var mapped = false

    init {
        surface.role = this
    }

    val effectiveGeometry: Geometry
        get() = geometry ?: Geometry(0, 0, surface.width, surface.height)

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1 -> toplevel = client.register(XdgToplevel(client, args.newId(), version, this))
            2 -> {
                val newId = args.newId()
                val parent = client.get<XdgSurface>(args.obj())
                val positioner = client.get<XdgPositioner>(args.obj()) ?: return
                popup = client.register(XdgPopup(client, newId, version, this, parent, positioner.position()))
            }
            3 -> pendingGeometry = Geometry(args.int(), args.int(), args.int(), args.int())
            4 -> Unit // ack_configure
        }
    }

    fun sendConfigure() = send(event(0).uint(client.server.nextSerial()))

    override fun onCommit() {
        pendingGeometry?.let { geometry = it }
        if (!initialCommitDone) {
            initialCommitDone = true
            toplevel?.let { client.server.windowManager.toplevelCreated(it) }
            popup?.configure()
            return
        }
        val hasContent = surface.hasContent
        if (hasContent && !mapped) {
            mapped = true
            popup?.let { client.server.popups += it }
        } else if (!hasContent && mapped) {
            // Unmapped by attaching a null buffer; the client must start over.
            mapped = false
            initialCommitDone = false
            // It waits for a configure before mapping again, even one with the same size.
            toplevel?.forgetConfigure()
            toplevel?.let { client.server.windowManager.toplevelDestroyed(it) }
            popup?.let { client.server.popups -= it }
        }
    }

    override fun onDestroy() {
        if (surface.role === this) surface.role = null
    }
}

class XdgToplevel(
    client: Client,
    id: Int,
    version: Int,
    val xdg: XdgSurface,
) : Resource(client, id, version) {
    override val iface = "xdg_toplevel"

    var title by mutableStateOf("")
        private set
    var appId by mutableStateOf("")
        private set

    private var configuredWidth = -1
    private var configuredHeight = -1
    private var configuredActive = false

    /** Draws this window and its popups while it's on screen; set by its activity. */
    @Volatile
    var renderer: WindowRenderer? = null

    val surface get() = xdg.surface

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            2 -> title = args.string().orEmpty()
            3 -> appId = args.string().orEmpty()
            else -> Unit // move/resize/maximize/etc.: every window fills its Android task
        }
    }

    /** Sends a configure if anything changed; sizes are in logical pixels. */
    fun configure(
        width: Int,
        height: Int,
        active: Boolean,
        force: Boolean = false,
    ) {
        if (!force && width == configuredWidth && height == configuredHeight && active == configuredActive) return
        configuredWidth = width
        configuredHeight = height
        configuredActive = active
        val states =
            buildList {
                if (active) add(4) // activated
                if (version >= 2) addAll(listOf(5, 6, 7, 8)) // tiled on all edges
                else add(1) // maximized: best "don't draw shadows" hint for v1 clients
            }
        send(event(0).int(width).int(height).ints(states.toIntArray()))
        xdg.sendConfigure()
    }

    fun forgetConfigure() {
        configuredWidth = -1
    }

    fun close() = send(event(1))

    override fun onDestroy() {
        client.server.windowManager.toplevelDestroyed(this)
        if (xdg.toplevel === this) xdg.toplevel = null
    }
}

class XdgPopup(
    client: Client,
    id: Int,
    version: Int,
    val xdg: XdgSurface,
    val parent: XdgSurface?,
    initial: Geometry,
) : Resource(client, id, version) {
    override val iface = "xdg_popup"

    /** Position relative to the parent's window geometry. */
    var position = initial
        private set

    val surface get() = xdg.surface

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1 -> Unit // grab: we dismiss popups on outside taps anyway
            2 -> {
                val positioner = client.get<XdgPositioner>(args.obj()) ?: return
                val token = args.uint()
                position = positioner.position()
                send(event(2).uint(token))
                configure()
            }
        }
    }

    fun configure() {
        val p = position
        send(event(0).int(p.x).int(p.y).int(p.width).int(p.height))
        xdg.sendConfigure()
    }

    fun dismiss() = send(event(1))

    override fun onDestroy() {
        // Found through the parent: once this is gone, the surface no longer leads to its window.
        val window = parent?.let { client.server.toplevelOf(it.surface) }
        client.server.popups -= this
        if (xdg.popup === this) xdg.popup = null
        window?.renderer?.requestFrame()
    }
}

class DecorationManager(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "zxdg_decoration_manager_v1"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1 -> {
                val deco = client.register(ToplevelDecoration(client, args.newId()))
                deco.sendMode()
            }
        }
    }
}

/** Always server-side: windows fill their task, so clients draw no decorations. */
class ToplevelDecoration(
    client: Client,
    id: Int,
) : Resource(client, id, 1) {
    override val iface = "zxdg_toplevel_decoration_v1"

    fun sendMode() = send(event(0).uint(2))

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1, 2 -> sendMode()
        }
    }
}

/**
 * KDE's older decoration protocol, the only one GTK3 asks (so wxWidgets apps
 * too): told the server decorates, it leaves out its own title bar and
 * window buttons, which on a phone only take space. Always server-side, as
 * with [DecorationManager]; the server draws none.
 */
class ServerDecorationManager(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "org_kde_kwin_server_decoration_manager"

    init {
        send(event(0).uint(SERVER_MODE)) // default_mode
    }

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        if (opcode == 0) client.register(ServerDecoration(client, args.newId())).sendMode() // create; the surface doesn't matter
    }

    companion object {
        const val SERVER_MODE = 2
    }
}

class ServerDecoration(
    client: Client,
    id: Int,
) : Resource(client, id, 1) {
    override val iface = "org_kde_kwin_server_decoration"

    fun sendMode(mode: Int = ServerDecorationManager.SERVER_MODE) = send(event(0).uint(mode))

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy() // release
            // request_mode: granted, as wlroots does. A header-bar window asks for client-side and, answered
            // server-side, asks again at once, forever; the default already keeps plain windows server-side.
            1 -> sendMode(args.uint())
        }
    }
}
