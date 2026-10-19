package dev.omalauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class OfferedShortcutsTest {
    private data class S(
        val id: String,
        val dynamic: Boolean,
        val rank: Int,
    )

    private fun order(vararg s: S) = offeredOrder(s.toList(), { it.dynamic }, { it.rank }).map { it.id }

    @Test fun manifestOnesComeFirst() {
        // A chat app's recent chats rank 0 too, but its own "New message" stays on top.
        assertEquals(listOf("compose", "search", "alice", "bob"), order(S("alice", true, 0), S("search", false, 1), S("bob", true, 1), S("compose", false, 0)))
    }

    @Test fun atMostFour() {
        assertEquals(listOf("a", "b", "c", "d"), order(S("e", true, 4), S("d", true, 3), S("c", true, 2), S("b", true, 1), S("a", true, 0)))
    }

    @Test fun pinningKeepsThePinnedOnes() {
        // Android drops a pinned shortcut that's left out of the ids it's given.
        assertEquals(listOf("site", "chat"), pinnedIds(listOf("site"), "chat"))
        assertEquals(listOf("site", "chat"), pinnedIds(listOf("site", "chat"), "chat"))
        assertEquals(listOf("chat"), pinnedIds(emptyList(), "chat"))
    }
}
