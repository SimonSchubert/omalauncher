package dev.omalauncher.ui

/** Drops queued input after focus loss; cleanup is ordered before the next owner's input. */
internal class InputLease(
    private val dispatch: (() -> Unit) -> Unit,
    private val onClose: () -> Unit = {},
    private val finish: () -> Unit,
) {
    @Volatile var open = true
        private set
    @Volatile private var valid = true

    fun post(event: () -> Unit) {
        if (open) dispatch { if (valid) event() }
    }

    /** Normal key-up preserves a queued short press; cancellation drops it. */
    fun release() = end(cancel = false)

    /** Called on the UI thread, like post. Never releases input through another window's focus. */
    fun close() = end(cancel = true)

    private fun end(cancel: Boolean) {
        if (cancel) valid = false
        if (!open) return
        open = false
        onClose()
        dispatch(finish)
    }
}
