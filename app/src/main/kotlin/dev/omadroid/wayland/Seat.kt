package dev.omadroid.wayland

class OutputResource(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "wl_output"

    init {
        sendState()
    }

    fun sendState() {
        val o = client.server.output
        send(
            event(0)
                .int(0)
                .int(0)
                .int(o.physicalWidthMm)
                .int(o.physicalHeightMm)
                .int(0)
                .string("Android")
                .string("Display")
                .int(0),
        )
        send(event(1).uint(3).int(o.widthPx).int(o.heightPx).int(60000)) // current | preferred
        if (version >= 2) {
            send(event(3).int(o.scale))
            send(event(2))
        }
    }

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        if (opcode == 0) destroy()
    }
}

class SeatResource(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "wl_seat"

    init {
        send(event(0).uint(1 or 2 or 4)) // pointer | keyboard | touch
        if (version >= 2) send(event(1).string("seat0"))
    }

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> client.register(PointerResource(client, args.newId(), version))
            1 -> client.server.input.addKeyboard(client.register(KeyboardResource(client, args.newId(), version)))
            2 -> client.register(TouchResource(client, args.newId(), version))
            3 -> destroy()
        }
    }
}

class PointerResource(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "wl_pointer"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        if (opcode == 1) destroy() // set_cursor is ignored: Android draws its own
    }

    fun frame() {
        if (version >= 5) send(event(5))
    }
}

class TouchResource(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "wl_touch"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        if (opcode == 0) destroy()
    }
}

class KeyboardResource(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "wl_keyboard"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        if (opcode == 0) destroy()
    }

    override fun onDestroy() = client.server.input.removeKeyboard(this)
}
