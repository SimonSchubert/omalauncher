package dev.omalauncher.widgets.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WsInboxTest {
    private fun taken(inbox: WsInbox<String>): Pair<List<String>, Int> {
        inbox.close()
        val out = mutableListOf<String>()
        var drops = 0
        runBlocking { inbox.take({ drops++ }) { out += it } }
        return out to drops
    }

    @Test fun keepsAllInOrderUnderTheLimits() {
        val inbox = WsInbox<String>(max = 4, maxSize = 100)
        listOf("a", "b", "c").forEach { inbox.put(it, 10) }
        assertEquals(listOf("a", "b", "c") to 0, taken(inbox))
    }

    @Test fun dropsTheOldestPastTheCount() {
        val inbox = WsInbox<String>(max = 2, maxSize = 100)
        listOf("a", "b", "c").forEach { inbox.put(it, 1) }
        assertEquals(listOf("b", "c") to 1, taken(inbox))
    }

    /** Big messages: few of them fit, whatever the count allows. */
    @Test fun dropsTheOldestPastTheSize() {
        val inbox = WsInbox<String>(max = 256, maxSize = 100)
        listOf("a", "b", "c", "d").forEach { inbox.put(it, 40) }
        assertEquals(listOf("c", "d") to 1, taken(inbox))
    }

    @Test fun keepsOneMessageOverTheSize() {
        val inbox = WsInbox<String>(max = 256, maxSize = 100)
        inbox.put("a", 10)
        inbox.put("huge", 500)
        assertEquals(listOf("huge") to 1, taken(inbox))
    }

    @Test fun nothingAfterClose() {
        val inbox = WsInbox<String>(max = 4, maxSize = 100)
        inbox.put("a", 1)
        inbox.close()
        inbox.put("b", 1)
        val out = mutableListOf<String>()
        runBlocking { inbox.take({}) { out += it } }
        assertEquals(listOf("a"), out)
    }
}
