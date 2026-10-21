package dev.omalauncher.widgets.runtime

/** A rendered node's identity, retained across page disposal and shared by drag-preview copies. */
internal data class NodePath private constructor(private val parts: List<Any>) {
    fun child(part: Any) = NodePath(parts + part)

    companion object {
        val Root = NodePath(emptyList())
    }
}

/** Distinguishes repeated keys too, without tying an explicit key to its changing list index. */
internal data class LoopItemKey(val value: Any, val occurrence: Int)

internal fun loopItemKeys(values: List<Any>): List<LoopItemKey> {
    val counts = HashMap<Any, Int>()
    return values.map { value ->
        val occurrence = counts.getOrDefault(value, 0)
        counts[value] = occurrence + 1
        LoopItemKey(value, occurrence)
    }
}

/** Sessions belong to a widget instance, rather than a particular composition or view copy. */
internal class NodeSessions<S>(private val close: (S) -> Unit) {
    private data class Entry<S>(val configuration: Any, val session: S)
    private val entries = HashMap<Any, Entry<S>>()

    fun get(key: Any, configuration: Any, create: () -> S): S {
        entries[key]?.let {
            if (it.configuration == configuration) return it.session
            entries.remove(key)
            close(it.session)
        }
        return create().also { entries[key] = Entry(configuration, it) }
    }

    fun owns(session: S?) = session != null && entries.values.any { it.session === session }

    fun close() {
        entries.values.forEach { close(it.session) }
        entries.clear()
    }
}
