package dev.omalauncher.wayland

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

/** Exercises actual Wayland event generation without opening a socket or loading the JNI bridge. */
class InputCancellationTest {
    private val server = WaylandServer("unused", object : WindowManagerHooks {
        override fun toplevelCreated(toplevel: XdgToplevel) {}
        override fun toplevelDestroyed(toplevel: XdgToplevel) {}
    })
    private val queued = HashMap<Client, MutableList<Event>>()
    private fun client(): Client {
        val events = mutableListOf<Event>()
        return Client(server, -1, eventSink = { events += it }).also { queued[it] = events }
    }
    private fun surface(client: Client, id: Int = 2) = client.register(Surface(client, id, 4))
    private fun touch(client: Client) = client.register(TouchResource(client, 3, 5))
    private fun pointer(client: Client) = client.register(PointerResource(client, 4, 5))

    private data class Message(val objectId: Int, val opcode: Int, val args: List<Int>)

    private fun drain(client: Client): List<Message> {
        val events = queued.getValue(client).toList()
        queued.getValue(client).clear()
        return events.map {
            val bytes = ByteBuffer.wrap(it.bytes, 0, it.size).order(ByteOrder.LITTLE_ENDIAN)
            Message(bytes.int, bytes.int and 0xffff, List((it.size - 8) / 4) { bytes.int })
        }
    }

    @Test fun cancellingTouchEndsTheClientSequenceAndLeavesOtherClientsAlone() {
        val a = client(); val b = client()
        val first = surface(a); val popup = surface(a, 5); val other = surface(b)
        touch(a); touch(b)
        server.input.touchDown(first, 1, 1f, 1f)
        server.input.touchDown(popup, 2, 1f, 1f)
        server.input.touchDown(other, 3, 1f, 1f)
        assertTrue(server.input.wantsTouch(first))
        assertEquals(listOf(0, 3, 0, 3), drain(a).map { it.opcode })
        drain(b)
        server.input.cancelInput { it === first }
        assertEquals(listOf(4), drain(a).map { it.opcode }) // wl_touch.cancel, once per sequence
        assertTrue(drain(b).isEmpty())
        server.input.touchMotion(1, 2f, 2f)
        server.input.touchUp(1)
        server.input.touchUp(2)
        assertTrue(drain(a).isEmpty())
        server.input.touchUp(3)
        assertEquals(listOf(1, 3), drain(b).map { it.opcode }) // other client still receives up/frame
        server.input.cancelInput { it === first }
        assertTrue(drain(a).isEmpty())
    }

    @Test fun cancellationReleasesMouseButtonsStopsBothAxesAndLeavesTheSurface() {
        val c = client(); val s = surface(c)
        pointer(c)
        server.input.pointerMotion(s, 1f, 1f)
        server.input.pointerButton(BTN_LEFT, true)
        server.input.pointerButton(BTN_RIGHT, true)
        assertSame(s, server.input.pointerFocus)
        assertEquals(listOf(BTN_LEFT, BTN_RIGHT), drain(c).filter { it.opcode == 3 }.map { it.args[2] })
        server.input.cancelInput { it === s }
        val messages = drain(c)
        assertEquals(listOf(BTN_LEFT, BTN_RIGHT), messages.filter { it.opcode == 3 }.map { it.args[2] })
        assertTrue(messages.filter { it.opcode == 3 }.all { it.args[3] == 0 })
        assertEquals(listOf(0, 1), messages.filter { it.opcode == 7 }.map { it.args[1] })
        assertEquals(1, messages.count { it.opcode == 1 }) // pointer.leave
        assertNull(server.input.pointerFocus)
        server.input.cancelInput { it === s }
        assertTrue(drain(c).isEmpty())
    }

    @Test fun lateCleanupFromAnOldWindowCannotReleaseTheNewOwnersMouse() {
        val a = client(); val b = client()
        val old = surface(a); val current = surface(b)
        pointer(a); pointer(b)
        server.input.pointerMotion(current, 1f, 1f)
        server.input.pointerButton(BTN_LEFT, true)
        drain(b)
        server.input.cancelInput { it === old }
        assertSame(current, server.input.pointerFocus)
        assertTrue(drain(b).isEmpty())
        server.input.pointerButton(BTN_LEFT, false)
        assertEquals(1, drain(b).count { it.opcode == 3 })
    }
}
