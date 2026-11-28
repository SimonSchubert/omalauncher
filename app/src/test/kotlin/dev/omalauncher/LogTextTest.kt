package dev.omalauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class LogTextTest {
    @Test
    fun `a release names a command without its arguments`() {
        assertEquals("foot", LogText.command("foot --app-id=t1 -e curl -H 'Authorization: secret'", full = false))
        assertEquals("gimp", LogText.command(" /usr/bin/gimp /home/me/private.png", full = false))
        assertEquals("htop", LogText.command("htop", full = false))
    }

    @Test
    fun `a release keeps a URL's scheme and host only`() {
        assertEquals("https://example.com", LogText.url("https://example.com/callback?code=secret#token", full = false))
        assertEquals("http://localhost:8080", LogText.url("http://me:pass@localhost:8080/x", full = false))
        assertEquals("https://example.com", LogText.url("https://example.com", full = false))
        assertEquals("mailto:", LogText.url("mailto:someone@example.com", full = false))
        assertEquals("15 chars", LogText.url("/home/me/secret", full = false))
    }

    @Test
    fun `a debug build shows all of it`() {
        assertEquals("foot -e top", LogText.command("foot -e top", full = true))
        assertEquals("https://example.com/a?b=c", LogText.url("https://example.com/a?b=c", full = true))
    }
}
