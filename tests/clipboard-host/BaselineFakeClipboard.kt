package dev.omalauncher.wayland

/** Same simulated behavior as FakeClipboard.kt, adapted only to the original Long/Unit interface. */
internal class FakeClipboard : SystemClipboard {
    @Volatile var value: String? = "initial Android text"
    @Volatile var generation = 1L
    @Volatile var writes = 0
    @Volatile var failReads = false
    @Volatile var failStamp = false
    @Volatile var externalDuringWrite: String? = null
    override fun text(): String? {
        if (failReads) throw SecurityException("Android clipboard unavailable")
        return value
    }
    override fun stamp(): Long {
        if (failStamp) throw SecurityException("Android clipboard metadata unavailable")
        return generation
    }
    @Synchronized override fun setText(text: String) {
        value = text
        generation++
        val external = externalDuringWrite
        if (external != null) {
            externalDuringWrite = null
            external(external)
        }
        writes++
    }
    @Synchronized fun external(text: String?) {
        value = text
        generation++
    }
}
