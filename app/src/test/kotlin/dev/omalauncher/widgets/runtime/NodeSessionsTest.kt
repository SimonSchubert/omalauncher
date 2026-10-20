package dev.omalauncher.widgets.runtime

import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.WNode
import org.junit.Assert.*
import org.junit.Test

class NodeSessionsTest {
    private class Session { var closed = false }
    private fun registry() = NodeSessions<Session> { it.closed = true }

    @Test fun repeatedAstNodeHasIndependentSessionsAndSurvivesReordering() {
        val program = Compiler.compile("""face "test" {
            meta { owl: 13 }
            for app in ["a", "b"] key app { LinuxApp { command: "demo" } }
        }""")
        val loop = program.root.single() as WNode.For
        val element = loop.body.single()
        val registry = registry()
        fun render(keys: List<String>) = loopItemKeys(keys.map(::Str)).map { key ->
            val path = NodePath.Root.child(loop).child(key).child(element)
            registry.get(path, "demo") { Session() }
        }
        val first = render(listOf("a", "b"))
        assertNotSame(first[0], first[1])
        val reordered = render(listOf("b", "a"))
        assertSame(first[1], reordered[0])
        assertSame(first[0], reordered[1])
        assertTrue(first.none { it.closed })
    }

    @Test fun componentCallSitesAndNestedLoopsStaySeparate() {
        val body = Any()
        val outer = loopItemKeys(listOf("a", "b"))
        val inner = loopItemKeys(listOf(1, 2))
        val paths = listOf(Any(), Any()).flatMap { call ->
            outer.flatMap { a -> inner.map { b -> NodePath.Root.child(call).child(a).child(b).child(body) } }
        }
        val registry = registry()
        val sessions = paths.map { registry.get(it, "same-command") { Session() } }
        assertEquals(8, sessions.toSet().size)
    }

    @Test fun aNewCompositionOrDragCopyReusesItsSession() {
        val node = Any()
        val registry = registry()
        val first = registry.get(NodePath.Root.child(node), "demo") { Session() }
        assertSame(first, registry.get(NodePath.Root.child(node), "demo") { error("duplicated process") })
        // Hiding/off-screen disposal does not terminate a session owned by the widget.
        assertFalse(first.closed)
        assertTrue(registry.owns(first))
        registry.close()
        assertTrue(first.closed)
        assertFalse(registry.owns(first))
    }

    @Test fun changingOneCommandOnlyClosesThatSession() {
        val registry = registry()
        val first = registry.get("a", "old command") { Session() }
        val other = registry.get("b", "other command") { Session() }
        val replacement = registry.get("a", "new command") { Session() }
        assertTrue(first.closed)
        assertFalse(other.closed)
        assertFalse(replacement.closed)
        assertFalse(registry.owns(first))
        registry.close()
        assertTrue(other.closed)
        assertTrue(replacement.closed)
    }

    @Test fun duplicateKeysAreDistinctAndUnrelatedKeysKeepTheirIdentity() {
        val keys = loopItemKeys(listOf("a", "b", "a"))
        assertEquals(3, keys.toSet().size)
        assertEquals(keys[1], loopItemKeys(listOf("b", "a"))[0])
        assertNotEquals(loopItemKeys(listOf(Str("1"))).single(), loopItemKeys(listOf(1)).single())
    }
}
