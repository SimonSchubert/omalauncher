package dev.omalauncher.wayland

/**
 * The primary selection (zwp_primary_selection_v1): what's selected in one
 * app, pasted in another with a middle click, as on a Linux desktop. It's
 * offered to the client with keyboard focus, like the clipboard, but stays
 * inside Linux: Android has nothing like it. All methods run with the server
 * locked.
 */
class PrimarySelection(
    private val server: WaylandServer,
) {
    private var source: PrimarySource? = null

    fun set(source: PrimarySource?) {
        if (source === this.source) return
        this.source?.cancel()
        this.source = source
        server.input.keyboardFocus?.let { offer(it.client) }
    }

    fun sourceDestroyed(source: PrimarySource) {
        if (this.source !== source) return
        this.source = null
        server.input.keyboardFocus?.let { offer(it.client) }
    }

    /** Sends the selection to [client], which has (or is getting) keyboard focus. */
    fun offer(client: Client) {
        val src = source
        for (d in client.all(PrimaryDevice::class.java)) {
            if (src == null) {
                d.send(d.event(1).obj(0))
                continue
            }
            val offer = client.register(PrimaryOffer(client, client.newServerId(), d.version, src))
            d.send(d.event(0).int(offer.id))
            for (m in src.mimes) offer.send(offer.event(0).string(m))
            d.send(d.event(1).obj(offer.id))
        }
    }
}

class PrimarySelectionManager(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "zwp_primary_selection_device_manager_v1"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> client.register(PrimarySource(client, args.newId(), version))
            1 -> {
                client.register(PrimaryDevice(client, args.newId(), version))
                if (client.server.input.keyboardFocus?.client === client) client.server.primary.offer(client)
            }
            2 -> destroy()
        }
    }
}

class PrimaryDevice(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "zwp_primary_selection_device_v1"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> { // set_selection: source or none, serial
                val source = args.obj().takeIf { it != 0 }?.let { client.get<PrimarySource>(it) ?: return }
                client.server.primary.set(source)
            }
            1 -> destroy()
        }
    }
}

class PrimarySource(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "zwp_primary_selection_source_v1"

    val mimes = LinkedHashSet<String>()

    var cancelled = false
        private set

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> args.string()?.let { mimes += it }
            1 -> destroy()
        }
    }

    fun cancel() {
        if (cancelled) return
        cancelled = true
        send(event(1))
    }

    override fun onDestroy() {
        cancelled = true
        client.server.primary.sourceDestroyed(this)
    }
}

class PrimaryOffer(
    client: Client,
    id: Int,
    version: Int,
    private val source: PrimarySource,
) : Resource(client, id, version) {
    override val iface = "zwp_primary_selection_offer_v1"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> { // receive: the source writes the type asked for into the fd
                val mime = args.string()
                val fd = args.fd()
                if (fd < 0) return
                if (source.cancelled) Native.close(fd) else source.send(source.event(0).string(mime).fd(fd, close = true))
            }
            1 -> destroy()
        }
    }
}
