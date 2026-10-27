package dev.omalauncher.wayland

internal class FakeClipboard : SystemClipboard {
    @Volatile var value: String? = "initial Android text"
    @Volatile var generation = 1L
    @Volatile var writes = 0
    @Volatile var failReads = false
    @Volatile var failStamp = false
    @Volatile var externalDuringWrite: String? = null
    @Volatile private var owner: String? = null
    override fun text(): String? {
        if (failReads) throw SecurityException("Android clipboard unavailable")
        return value
    }
    override fun stamp(): SystemClipboard.Stamp? {
        if (failStamp) throw SecurityException("Android clipboard metadata unavailable")
        return SystemClipboard.Stamp(generation, owner)
    }
    @Synchronized override fun setText(text: String): SystemClipboard.Stamp? {
        value = text
        generation++
        owner = "own-$generation"
        val stamp = stamp()
        val external = externalDuringWrite
        if (external != null) {
            externalDuringWrite = null
            external(external)
        }
        writes++
        return stamp.takeIf { external == null }
    }
    @Synchronized fun external(text: String?) {
        value = text
        generation++
        owner = null
    }
}
