package dev.omalauncher.wayland

/** zwp_text_input_manager_v3: hands out text inputs, through which clients say where their text cursor is. */
class TextInputManager(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "zwp_text_input_manager_v3"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1 -> client.server.input.addTextInput(client.register(TextInput(client, args.newId(), version)))
        }
    }
}

/**
 * One client's zwp_text_input_v3. Only its cursor rectangle is used, in surface-local logical pixels: foot reports the
 * terminal's cursor cell there. No text is ever sent back; keys still reach the client through wl_keyboard.
 */
class TextInput(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "zwp_text_input_v3"

    private var pendingEnabled: Boolean? = null
    private var pendingCursor: IntArray? = null

    /** Whether the client has text input on, as of its last commit. */
    private var enabled = false

    /** x, y, width, height of the text cursor, as of the last commit while enabled; null when unknown. */
    var cursor: IntArray? = null
        private set

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1 -> {
                pendingEnabled = true
                pendingCursor = null
            }
            2 -> pendingEnabled = false
            6 -> pendingCursor = intArrayOf(args.int(), args.int(), args.int(), args.int())
            7 -> {
                pendingEnabled?.let { enabled = it }
                // Enabling resets the state, the cursor rectangle with it; otherwise one not set again stays.
                cursor =
                    when {
                        !enabled -> null
                        pendingEnabled == true -> pendingCursor
                        else -> pendingCursor ?: cursor
                    }
                pendingEnabled = null
                pendingCursor = null
            }
            // set_surrounding_text, set_text_change_cause, set_content_type: nothing to do with them.
        }
    }

    fun enter(surface: Surface) = send(event(0).obj(surface.id))

    fun leave(surface: Surface) {
        send(event(1).obj(surface.id))
        cursor = null
    }
}
