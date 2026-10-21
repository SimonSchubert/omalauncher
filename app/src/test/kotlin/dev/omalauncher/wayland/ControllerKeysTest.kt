package dev.omalauncher.wayland

import dev.omalauncher.ui.ControlPointers
import dev.omalauncher.ui.ControlPress
import dev.omalauncher.ui.InputLease
import dev.omalauncher.widgets.render.LongPressClaim
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

/** Real wl_keyboard messages, without a socket/JNI or an Android emulator. */
class ControllerKeysTest {
    private val server = WaylandServer("unused", object : WindowManagerHooks {
        override fun toplevelCreated(toplevel: XdgToplevel) {}
        override fun toplevelDestroyed(toplevel: XdgToplevel) {}
    })
    private val input = server.input
    private val events = HashMap<Client, MutableList<Event>>()
    private fun surface(): Surface {
        val messages = mutableListOf<Event>()
        val client = Client(server, -1, eventSink = { messages += it })
        events[client] = messages
        input.addKeyboard(client.register(KeyboardResource(client, 3, 5)))
        return client.register(Surface(client, 2, 4))
    }
    private data class Message(val opcode: Int, val args: List<Int>)
    private fun drain(surface: Surface): List<Message> {
        val list = events.getValue(surface.client)
        val messages = list.map {
            val bytes = ByteBuffer.wrap(it.bytes, 0, it.size).order(ByteOrder.LITTLE_ENDIAN)
            bytes.int
            Message(bytes.int and 0xffff, List((it.size - 8) / 4) { bytes.int })
        }
        list.clear()
        return messages
    }
    private fun keys(surface: Surface) = drain(surface).filter { it.opcode == 3 }.map { it.args[2] to it.args[3] }
    private fun focus(surface: Surface) { input.setKeyboardFocus(surface); drain(surface) }

    @Test fun directionAndActionRemainHeldIndependently() {
        val s = surface(); focus(s)
        val direction = Any(); val action = Any()
        input.holdKeys(s, direction, listOf(105))
        input.holdKeys(s, action, listOf(44))
        assertEquals(listOf(105 to 1, 44 to 1), keys(s))
        input.releaseKeys(direction)
        assertEquals(listOf(105 to 0), keys(s))
        input.releaseKeys(action)
        assertEquals(listOf(44 to 0), keys(s))
        input.releaseKeys(action)
        assertTrue(keys(s).isEmpty())
    }

    @Test fun overlappingControlsAndPhysicalKeyboardReleaseOnlyTheirOwnHold() {
        val s = surface(); focus(s)
        val a = Any(); val b = Any()
        input.key(44, true)
        input.holdKeys(s, a, listOf(44))
        input.holdKeys(s, b, listOf(44))
        assertEquals(listOf(44 to 1), keys(s))
        input.key(44, false)
        input.releaseKeys(a)
        assertTrue(keys(s).isEmpty())
        input.releaseKeys(b)
        assertEquals(listOf(44 to 0), keys(s))
    }

    @Test fun chordsSendModifiersAndReleaseInReverseOrder() {
        val s = surface(); focus(s)
        val owner = Any()
        input.holdKeys(s, owner, listOf(29, 31))
        val down = drain(s)
        assertEquals(listOf(29, 31), down.filter { it.opcode == 3 }.map { it.args[2] })
        assertEquals(4, down.single { it.opcode == 4 }.args[1]) // Ctrl modifier
        input.releaseKeys(owner)
        val up = drain(s)
        assertEquals(listOf(31, 29), up.filter { it.opcode == 3 }.map { it.args[2] })
        assertEquals(0, up.single { it.opcode == 4 }.args[1])
    }

    @Test fun focusLossReleasesKeysBeforeLeaveAndOldCleanupCannotAffectTheNewClient() {
        val a = surface(); val b = surface(); focus(a)
        val old = Any(); val next = Any()
        input.holdKeys(a, old, listOf(105, 44))
        drain(a); drain(b)
        input.setKeyboardFocus(b)
        val messages = drain(a)
        assertEquals(listOf(105 to 0, 44 to 0), messages.filter { it.opcode == 3 }.map { it.args[2] to it.args[3] })
        assertEquals(2, messages.last().opcode) // wl_keyboard.leave
        drain(b)
        input.holdKeys(b, next, listOf(44))
        input.releaseKeys(old)
        input.holdKeys(a, old, listOf(44)) // Wrong target cannot inject into b.
        assertEquals(listOf(44 to 1), keys(b))
        input.releaseKeys(next)
        assertEquals(listOf(44 to 0), keys(b))
    }

    @Test fun queuedPressCannotReappearAfterFocusLeavesAndReturnsToTheSameWindow() {
        val s = surface(); focus(s)
        val epoch = input.keyboardEpoch
        input.setKeyboardFocus(null)
        focus(s)
        input.holdKeys(s, Any(), listOf(44), epoch)
        assertTrue(keys(s).isEmpty())
    }

    @Test fun shortTapIsDeliveredButCancelledQueuedPressIsDropped() {
        val s = surface(); focus(s)
        val queue = ArrayDeque<() -> Unit>()
        fun press(): InputLease {
            val owner = Any()
            return InputLease(dispatch = { queue.addLast(it) }, finish = { input.releaseKeys(owner) }).also { lease ->
                lease.post { input.holdKeys(s, owner, listOf(44)) }
            }
        }
        press().release()
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(listOf(44 to 1, 44 to 0), keys(s))
        press().close()
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertTrue(keys(s).isEmpty())
    }

    @Test fun severalFingersOnOneControlAndCancelAreBalanced() {
        val s = surface(); focus(s)
        val claims = LongPressClaim()
        val pointers = ControlPointers { id ->
            val owner = Any()
            input.holdKeys(s, owner, listOf(44))
            claims.hold(id)
            ControlPress({ input.releaseKeys(owner); claims.release(id) }, { input.releaseKeys(owner); claims.release(id) })
        }
        assertTrue(pointers.down(1)); assertTrue(pointers.down(2)); assertTrue(pointers.down(1))
        assertTrue(claims.holding(1)); assertTrue(claims.holding(2))
        assertEquals(listOf(44 to 1), keys(s))
        pointers.up(1)
        assertFalse(claims.holding(1)); assertTrue(claims.holding(2))
        assertTrue(keys(s).isEmpty())
        pointers.cancel()
        pointers.cancel(); pointers.up(2)
        assertEquals(listOf(44 to 0), keys(s))
        assertFalse(claims.holding(1)); assertFalse(claims.holding(2))
    }

    @Test fun destroyingTheTargetDropsOwnershipBeforeANewWindowUsesTheSameKey() {
        val a = surface(); val b = surface(); focus(a)
        val old = Any()
        input.holdKeys(a, old, listOf(44))
        input.surfaceDestroyed(a)
        focus(b)
        input.key(44, true)
        input.releaseKeys(old)
        assertEquals(listOf(44 to 1), keys(b))
        input.key(44, false)
        assertEquals(listOf(44 to 0), keys(b))
    }
}
