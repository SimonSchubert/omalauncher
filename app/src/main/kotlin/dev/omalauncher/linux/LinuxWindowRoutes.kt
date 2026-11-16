package dev.omalauncher.linux

/** Matches one external app launch to its first new Wayland window, even when other apps open meanwhile. */
internal class LinuxWindowRoutes(private val now: () -> Long) {
    data class Route(val displayId: Int, val onError: (String) -> Unit,
        val matches: (appId: String, title: String) -> Boolean, val until: Long)

    private val pending = ArrayList<Route>()

    fun request(displayId: Int, onError: (String) -> Unit, matches: (String, String) -> Boolean) {
        expire()
        pending += Route(displayId, onError, matches, now() + 60_000)
    }

    fun claim(appId: String, title: String): Route? {
        expire()
        val index = pending.indexOfFirst { it.matches(appId, title) }
        return if (index < 0) null else pending.removeAt(index)
    }

    private fun expire() { pending.removeAll { it.until < now() } }
}
