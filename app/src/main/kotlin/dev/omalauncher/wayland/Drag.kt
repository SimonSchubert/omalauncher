package dev.omalauncher.wayland

import android.os.SystemClock

/** wl_data_device_manager dnd_action bits. */
private const val ACTION_COPY = 1
private const val ACTION_MOVE = 2
private const val ACTION_ASK = 4

/**
 * One drag and drop (wl_data_device.start_drag), driven by the pointer
 * button or the finger that was down when it started. While it runs, the
 * surface under it gets data_device enter, motion and leave instead of
 * pointer or touch events; letting go drops on it, if it accepted a type
 * and an action, or cancels. Methods run with the server locked.
 */
class Drag(
    private val server: WaylandServer,
    /** The client that started it: with no [source], only its own surfaces are targets (a drag inside the app). */
    private val client: Client,
    val source: DataSource?,
    /** Drawn under the finger, if the client gave one. */
    val icon: Surface?,
    /** The finger driving it; null for the pointer. */
    val touchId: Int?,
    /** The window it started in; a toplevel dragged along (xdg_toplevel_drag) is shown over it. */
    val origin: XdgToplevel?,
) {
    private var focus: Surface? = null

    /** The offer each of [focus]'s client's data devices got, by device. */
    private val offers = HashMap<DataDevice, DataOffer?>()

    /** What the target accepted on its last offer (accept with a type), and the action agreed. */
    var accepted: String? = null
    var action = 0

    private fun now() = SystemClock.uptimeMillis().toInt()

    /** The drag is over [under] (a surface and a point on it), or over nothing. */
    fun motion(under: SurfaceHit?) {
        val s = under?.surface?.takeIf { !it.destroyed && (source != null || it.client === client) }
        if (s !== focus) {
            leave()
            if (s != null && under != null) enter(s, under.x, under.y)
            return
        }
        if (s == null || under == null) return
        val time = now()
        for (device in offers.keys) device.send(device.event(3).uint(time).fixed(under.x).fixed(under.y))
    }

    private fun enter(
        surface: Surface,
        x: Float,
        y: Float,
    ) {
        focus = surface
        accepted = null
        action = 0
        val serial = server.nextSerial()
        for (device in surface.client.all(DataDevice::class.java)) {
            val offer =
                source?.let { src ->
                    val o = surface.client.register(DataOffer(surface.client, surface.client.newServerId(), device.version, src, server.selection, this))
                    device.send(device.event(0).int(o.id))
                    for (m in src.mimes) o.send(o.event(0).string(m))
                    if (o.version >= 3) o.send(o.event(1).uint(src.actions))
                    o
                }
            offers[device] = offer
            device.send(device.event(1).uint(serial).obj(surface.id).fixed(x).fixed(y).obj(offer?.id ?: 0))
        }
    }

    private fun leave() {
        if (focus == null) return
        for ((device, offer) in offers) {
            device.send(device.event(2))
            offer?.dropped = false
        }
        offers.clear()
        focus = null
    }

    /** The finger or button that drove it lifted: a drop where it's welcome, else a cancel. */
    fun drop() {
        val src = source
        val welcome = focus != null && (src == null || (accepted != null && (action != 0 || offers.values.any { (it?.version ?: 3) < 3 })))
        if (!welcome) return cancel()
        for ((device, offer) in offers) {
            offer?.dropped = true
            device.send(device.event(4))
        }
        offers.clear()
        focus = null
        if (src != null && src.version >= 3) src.send(src.event(3)) // dnd_drop_performed
        end()
    }

    /** Ends it without a drop: the target hears it left, the source that it was cancelled. */
    fun cancel() {
        leave()
        source?.cancel()
        end()
    }

    private fun end() {
        server.input.dragEnded(this)
        source?.attached?.let { (top, _) -> top.dragOffset = null }
    }

    /** A target offered [dnd] and [preferred] (offer.set_actions): picks what both sides allow. */
    fun negotiate(
        offer: DataOffer,
        dnd: Int,
        preferred: Int,
    ) {
        val allowed = dnd and (source?.actions ?: ACTION_COPY)
        action =
            when {
                preferred and allowed != 0 -> preferred and allowed
                allowed and ACTION_COPY != 0 -> ACTION_COPY
                allowed and ACTION_MOVE != 0 -> ACTION_MOVE
                allowed and ACTION_ASK != 0 -> ACTION_ASK
                else -> 0
            }
        if (offer.version >= 3) offer.send(offer.event(2).uint(action))
        source?.takeIf { it.version >= 3 }?.let { it.send(it.event(5).uint(action)) }
    }
}

/**
 * xdg_toplevel_drag_manager_v1: lets a drag carry a toplevel along (a toolbar
 * or a tab being torn off), which the compositor moves with the finger.
 * Wayland leaves window placement to the compositor, so without it the app
 * can't move the window it's dragging.
 */
class ToplevelDragManager(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "xdg_toplevel_drag_manager_v1"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1 -> {
                val newId = args.newId()
                val source = client.get<DataSource>(args.obj()) ?: return
                client.register(ToplevelDrag(client, newId, source))
            }
        }
    }
}

class ToplevelDrag(
    client: Client,
    id: Int,
    private val source: DataSource,
) : Resource(client, id, 1) {
    override val iface = "xdg_toplevel_drag_v1"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1 -> { // attach: the toplevel follows the drag, its geometry's corner [x, y] off the pointer
                val top = client.get<XdgToplevel>(args.obj()) ?: return
                val x = args.int()
                val y = args.int()
                source.attached = top to (x to y)
                top.dragOffset = x to y
                top.dragSource = source
            }
        }
    }
}
