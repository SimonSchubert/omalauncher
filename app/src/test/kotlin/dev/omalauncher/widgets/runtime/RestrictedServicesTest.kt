package dev.omalauncher.widgets.runtime

import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class RestrictedServicesTest {
    private val calls = ArrayList<String>()

    private val delegate =
        Proxy.newProxyInstance(Services::class.java.classLoader, arrayOf(Services::class.java)) { _, method, args ->
            calls += method.name
            when (method.name) {
                "android" -> Obj(mapOf("name" to Str(args?.firstOrNull()?.toString().orEmpty())))
                else -> null
            }
        } as Services

    @Test
    fun guestAndSensitiveActionsAreDenied() = runBlocking {
        var denials = 0
        val services = RestrictedServices(delegate) { denials++ }

        assertEquals(-1, services.cmd("cat /data/data/dev.omalauncher/files/widget-state", 30).code)
        services.stream("cat token") {}
        services.run("cat token") {}
        assertNull(services.guestFile("~/token"))
        assertEquals("", services.fileUpdates("~/token").first())
        assertNull(services.terminal(null, null))
        services.act("run", emptyList()) {}
        services.act("url", listOf(Str("https://example.test"))) {}

        assertTrue(denials > 0)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun notificationsAreHiddenButOrdinaryDataAndActionsRemainAvailable() {
        var denials = 0
        val services = RestrictedServices(delegate) { denials++ }

        val notifications = services.android("notifications") as Obj
        assertEquals("false", notifications.fields.getValue("available").toString())
        assertNull(services.androidUpdates("notifications"))
        assertEquals(Str("battery"), (services.android("battery") as Obj).fields.getValue("name"))
        services.act("media", emptyList()) {}

        assertTrue(denials > 0)
        assertTrue(calls.contains("android"))
        assertTrue(calls.contains("act"))
        assertFalse(calls.contains("cmd"))
    }
}
