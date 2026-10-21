package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinuxWindowRoutesTest {
    @Test fun routesConcurrentAppLaunchesByWindowIdentity() {
        var time = 100L
        val routes = LinuxWindowRoutes { time }
        routes.request(2, {}, { id, _ -> id == "terminal" })
        routes.request(3, {}, { id, _ -> id == "browser" })

        assertNull(routes.claim("phone-app", "Unrelated"))
        assertEquals(3, routes.claim("browser", "Browser")?.displayId)
        assertEquals(2, routes.claim("terminal", "Terminal")?.displayId)
        assertNull(routes.claim("terminal", "Terminal"))

        routes.request(4, {}, { id, _ -> id == "slow-app" })
        time += 60_001
        assertNull(routes.claim("slow-app", "Slow"))
    }
}
