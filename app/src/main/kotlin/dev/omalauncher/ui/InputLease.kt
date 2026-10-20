package dev.omalauncher.ui

/** Drops queued input after focus loss; cleanup is ordered before the next owner's input. */
internal class InputLease(
    private val dispatch: (() -> Unit) -> Unit,
    private val finish: () -> Unit,
) {
    @Volatile var open = true
        private set

    fun post(event: () -> Unit) {
        if (open) dispatch { if (open) event() }
    }

    /** Called on the UI thread, like post. Never releases input through another window's focus. */
    fun close() {
        if (!open) return
        open = false
        dispatch(finish)
    }
}
