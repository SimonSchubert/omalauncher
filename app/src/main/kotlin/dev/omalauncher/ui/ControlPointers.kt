package dev.omalauncher.ui

/** Every finger owns its own release. A second finger cannot release the first one's key. */
internal class ControlPress(val release: () -> Unit, val cancel: () -> Unit)

internal class ControlPointers(private val hold: (Long) -> ControlPress?) {
    private val releases = LinkedHashMap<Long, ControlPress>()
    fun contains(id: Long) = id in releases
    fun down(id: Long): Boolean {
        if (contains(id)) return true
        val release = hold(id) ?: return false
        releases[id] = release
        return true
    }
    fun up(id: Long) { releases.remove(id)?.release?.invoke() }
    fun cancel(id: Long) { releases.remove(id)?.cancel?.invoke() }
    fun cancel() {
        val held = releases.values.toList()
        releases.clear()
        held.forEach { it.cancel() }
    }
}
