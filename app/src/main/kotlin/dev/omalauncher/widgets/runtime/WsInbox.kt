package dev.omalauncher.widgets.runtime

import kotlinx.coroutines.channels.Channel

/**
 * A ws(...) source's messages on their way from the connection's thread to the main one: at most [max] of
 * them and [maxSize] in all (a message's size is what [put] says), past which the oldest go. A count alone
 * let 256 parsed 1 MB messages pile up while the main thread was busy, and the launcher run out of memory.
 */
internal class WsInbox<T>(
    private val max: Int,
    private val maxSize: Long,
) {
    private val items = ArrayDeque<Pair<T, Int>>()
    private var size = 0L
    private var closed = false

    /** Some message was dropped since [take] last said so. */
    private var dropped = false

    /** Wakes the reader of [take]; closed with [close]. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Adds [item]; the newest one is always kept, even alone over [maxSize]. Any thread. */
    fun put(
        item: T,
        itemSize: Int,
    ) {
        synchronized(this) {
            if (closed) return
            items.addLast(item to itemSize)
            size += itemSize
            while (items.size > max || (size > maxSize && items.size > 1)) {
                size -= items.removeFirst().second
                dropped = true
            }
        }
        wake.trySend(Unit)
    }

    /** No more come; what's in it is still taken. */
    fun close() {
        synchronized(this) { closed = true }
        wake.close()
    }

    /**
     * Hands each message to [each] in the order they came, one at a time so the rest stay under the limits,
     * until [close] and the last one; [onDropped] before the next one whenever some were dropped.
     */
    suspend fun take(
        onDropped: () -> Unit,
        each: suspend (T) -> Unit,
    ) {
        var ending = false
        while (true) {
            val lost: Boolean
            val next =
                synchronized(this) {
                    lost = dropped
                    dropped = false
                    items.removeFirstOrNull()?.also { size -= it.second }
                }
            if (lost) onDropped()
            if (next != null) {
                each(next.first)
            } else if (ending) {
                return
            } else if (wake.receiveCatching().isFailure) {
                // Closed: nothing more is put, so once what's left is taken, that's all.
                ending = true
            }
        }
    }
}
