package dev.omalauncher.wayland

/** zwp_relative_pointer_manager_v1: hands out relative pointers, which get the mouse's movement rather than a position. */
class RelativePointerManager(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "zwp_relative_pointer_manager_v1"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1 -> client.register(RelativePointer(client, args.newId(), version))
        }
    }
}

/** One client's zwp_relative_pointer_v1: how far the mouse moved while it's locked, for looking around in a game. */
class RelativePointer(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "zwp_relative_pointer_v1"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        if (opcode == 0) destroy()
    }

    /** [dx], [dy] as Android reports a captured mouse's movement; there is no acceleration to take out. */
    fun motion(
        timeUs: Long,
        dx: Float,
        dy: Float,
    ) = send(event(0).uint((timeUs ushr 32).toInt()).uint(timeUs.toInt()).fixed(dx).fixed(dy).fixed(dx).fixed(dy))
}

/**
 * zwp_pointer_constraints_v1. A lock is what games ask for to look around with the mouse: it holds while Android
 * captures the mouse for the window (see [Input.pointerCaptured]). Confining is offered so clients find the global,
 * but never takes effect: Android can't keep its own cursor inside a window.
 */
class PointerConstraints(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "zwp_pointer_constraints_v1"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1, 2 -> {
                val newId = args.newId()
                val surface = client.get<Surface>(args.obj()) ?: return
                args.obj() // pointer: there's only the one
                args.obj() // region: the whole surface counts
                val persistent = args.uint() == LIFETIME_PERSISTENT
                if (client.all(LockedPointer::class.java).any { it.surface === surface } ||
                    client.all(ConfinedPointer::class.java).any { it.surface === surface }
                ) {
                    return client.postError(id, ALREADY_CONSTRAINED, "surface ${surface.id} already has a pointer constraint")
                }
                if (opcode == 1) {
                    client.server.input.addLock(client.register(LockedPointer(client, newId, version, surface, persistent)))
                } else {
                    client.register(ConfinedPointer(client, newId, version, surface))
                }
            }
        }
    }

    private companion object {
        const val ALREADY_CONSTRAINED = 1
        const val LIFETIME_PERSISTENT = 2
    }
}

/** zwp_locked_pointer_v1: the pointer stays put on [surface] and only relative motion arrives, while it's active. */
class LockedPointer(
    client: Client,
    id: Int,
    version: Int,
    val surface: Surface,
    /** Whether it can lock again after it was unlocked; a one-shot lock is done with after its first unlock. */
    val persistent: Boolean,
) : Resource(client, id, version) {
    override val iface = "zwp_locked_pointer_v1"

    var active = false
        private set

    /** A one-shot lock that was active once and won't be again. */
    var spent = false
        private set

    /** Where the client would like the cursor to be when the lock ends; a position to enter the surface at. */
    var hintX = 0f
        private set
    var hintY = 0f
        private set

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1 -> {
                hintX = args.int() / 256f
                hintY = args.int() / 256f
            }
            // set_region: the whole surface counts.
        }
    }

    fun lock() {
        if (active || spent) return
        active = true
        send(event(0))
    }

    fun unlock() {
        if (!active) return
        active = false
        if (!persistent) spent = true
        send(event(1))
    }

    override fun onDestroy() = client.server.input.removeLock(this)
}

/** zwp_confined_pointer_v1, which never confines: see [PointerConstraints]. */
class ConfinedPointer(
    client: Client,
    id: Int,
    version: Int,
    val surface: Surface,
) : Resource(client, id, version) {
    override val iface = "zwp_confined_pointer_v1"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        if (opcode == 0) destroy()
    }
}
