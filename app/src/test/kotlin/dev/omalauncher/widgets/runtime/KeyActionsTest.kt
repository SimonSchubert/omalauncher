package dev.omalauncher.widgets.runtime

import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class KeyActionsTest {
    private val acted = ArrayList<String>()

    private val services =
        Proxy.newProxyInstance(Services::class.java.classLoader, arrayOf(Services::class.java)) { _, method, args ->
            if (method.name == "act") acted += args[0] as String
            null
        } as Services

    private val errors = ArrayList<String>()

    private val inst = WidgetInstance(Compiler.compile("keybar \"k\" {\n meta { owl: 12 }\n Text { text: \"k\" } }"), services, { errors += it })

    /** A key name built from values, which the compiler can't check, says what's wrong rather than pressing nothing. */
    @Test fun badNamesFromValuesAreReported() {
        inst.act("pressKey", listOf(Str("escape")))
        inst.act("latch", listOf(Str("super")))
        inst.act("latch", listOf(Str("ctrl"), Str("always")))
        assertEquals(emptyList<String>(), acted)
        assertEquals(3, errors.size)
        assertTrue(errors[0], errors[0].startsWith("pressKey(\"escape\") names no key; pressKey takes a key, esc,"))
        assertTrue(errors[1], errors[1].contains("ctrl, alt, shift"))
        assertTrue(errors[2], errors[2].contains("once, locked, off"))

        inst.act("pressKey", listOf(Str("ctrl+c")))
        inst.act("latch", listOf(Str("ctrl"), Str("locked")))
        assertEquals(listOf("pressKey", "latch"), acted)
    }
}
