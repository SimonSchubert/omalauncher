package dev.omalauncher.desktop

internal const val DESKTOP_PROTOCOL = 1

internal data class DesktopIdentity(val uid: Int, val packageName: String, val certificate: String)

/** Pure policy shared by the Binder boundary and its regression tests. */
internal object DesktopRules {
    fun launchMode(value: String): String {
        require(value == "host" || value == "system") { "invalid launch mode" }
        return value
    }
    fun size(width: Int, height: Int) {
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216) { "invalid desktop size" }
    }

    fun profile(value: String): String {
        require(value.matches(Regex("[a-zA-Z0-9_-]{1,40}"))) { "invalid layout profile" }
        return value
    }

    fun uiScale(value: Float): Float {
        require(value.isFinite() && value in .85f..5f) { "invalid desktop UI scale" }
        return value
    }
}

/** A leaked session Binder is useless to a different caller, and cannot be revived after closing. */
internal class DesktopLease(val identity: DesktopIdentity) {
    var closed = false
        private set

    fun check(caller: DesktopIdentity?, approved: Boolean) {
        check(!closed) { "desktop session is closed" }
        if (caller != identity || !approved) throw SecurityException("desktop approval is required")
    }

    fun close() { closed = true }
}
