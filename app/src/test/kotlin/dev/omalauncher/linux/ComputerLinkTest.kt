package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of a computer link both sides compute: the plugin's bash (sha256sum, openssl, base64) must arrive at the
 * same invite, code, id and proof. The expected values here came from those tools.
 */
class ComputerLinkTest {
    private val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAkTAFAGaGA+nfabLpknriYoIvUIHlq4mn42bSeRM985"
    private val data =
        "eyJuIjoiZnJhbWV3b3JrIiwiayI6InNzaC1lZDI1NTE5IEFBQUFDM056YUMxbFpESTFOVEU1QUFBQUlBa1RBRkFHYUdBK25mYWJMcGtucmlZb0l2VUlIbHE0bW40MmJTZVJNOTg1IiwicyI6IjAxMjM0NTY3ODlhYmNkZWYwMTIzIn0"

    @Test fun readsAnInvite() {
        val invite = ComputerLink.Invite.parse("https://simonschubert.github.io/omarchy-widgets/w#link1.$data")!!
        assertEquals("framework", invite.name)
        assertEquals(key, invite.key)
        assertEquals("0123456789abcdef0123", invite.secret)
        // printf %s "$key" | sha256sum | cut -c1-12
        assertEquals("2bfb5db2e1d6", invite.id)
        // $((16#<first 8 hex digits> % 1000000))
        assertEquals("893 810", invite.code)
    }

    @Test fun refusesWhatIsntOne() {
        assertNull(ComputerLink.Invite.parse("https://simonschubert.github.io/omarchy-widgets/w#owl1.abc"))
        assertNull(ComputerLink.Invite.parse("#link1.!!!"))
        assertTrue(ComputerLink.Invite.isInvite("#link1.!!!"))
        assertFalse(ComputerLink.Invite.isInvite("https://simonschubert.github.io/omarchy-widgets/w#owl1.abc"))
        fun invite(json: String) = ComputerLink.Invite.parse("#link1." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray()))
        // Another kind of key, options smuggled into authorized_keys, or a short secret.
        assertNull(invite("""{"n":"x","k":"ssh-rsa AAAA","s":"0123456789abcdef"}"""))
        assertNull(invite("""{"n":"x","k":"command=\"sh\" $key","s":"0123456789abcdef"}"""))
        assertNull(invite("""{"n":"x","k":"$key\nssh-ed25519 AAAA","s":"0123456789abcdef"}"""))
        assertNull(invite("""{"n":"x","k":"$key","s":"short"}"""))
        // A name is shown, never run: control characters go, and a missing one has a stand-in.
        assertEquals("ab", invite("""{"n":"a\u001b\nb","k":"$key","s":"0123456789abcdef"}""")!!.name)
        assertEquals("device", invite("""{"k":"$key","s":"0123456789abcdef"}""")!!.name)
    }

    @Test fun provesTheHostKeyAsOpensslDoes() {
        val host = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHostKeyHostKeyHostKeyHostKeyHostKeyHost"
        // printf %s "$host" | openssl dgst -sha256 -hmac 0123456789abcdef0123
        assertEquals("c1b3a345c8a914ffa328bb4e195a9b768b7e0205a90f287824942ead729d974b", ComputerLink.proof("0123456789abcdef0123", host))
    }

    @Test fun aCodeIsGoodForTwoMinutes() {
        assertTrue(ComputerLink.inviteOpen(1_000_000, 1_000_000))
        assertTrue(ComputerLink.inviteOpen(1_000_000, 1_120_000))
        assertFalse(ComputerLink.inviteOpen(1_000_000, 1_120_001))
        // A clock set back since: not open for longer than the two minutes.
        assertFalse(ComputerLink.inviteOpen(1_000_000, 999_999))
    }

    @Test fun tellsThisPhonesOwnSyncFromAConnectedDevice() {
        assertEquals("", ComputerLink.followedId("self"))
        assertEquals("9f3c21aa07b4", ComputerLink.followedId("self:9f3c21aa07b4"))
        // A connected device's id, a key's hash, is never taken for it.
        assertNull(ComputerLink.followedId("2bfb5db2e1d6"))
        assertNull(ComputerLink.followedId("selfish"))
        assertNull(ComputerLink.followedId(null))
    }

    @Test fun theSameActivityAgainTakesOneLine() {
        fun a(at: Long, what: String, device: String = "omarchy") = ComputerLink.Activity(at, device, what)
        var list = ComputerLink.noted(emptyList(), a(1, "changed the values of farm.owl"))
        list = ComputerLink.noted(list, a(2, "changed the values of farm.owl"))
        assertEquals(listOf(a(2, "changed the values of farm.owl")), list)
        list = ComputerLink.noted(list, a(3, "changed the values of farm.owl", "pixel"))
        list = ComputerLink.noted(list, a(4, "switched the theme to nord"))
        assertEquals(listOf(4L, 3L, 2L), list.map { it.at })
        repeat(100) { list = ComputerLink.noted(list, a(10L + it, "read w$it.owl")) }
        assertEquals(ComputerLink.MAX_ACTIVITY, list.size)
    }

    @Test fun letsKeysInOnlyForTheLinkCommand() {
        val c = ComputerLink.Computer("2bfb5db2e1d6", "framework", key, 0, null)
        assertEquals("restrict,command=\"/usr/local/bin/omalauncher-link 2bfb5db2e1d6\" $key\n", ComputerLink.authorizedKeys(listOf(c)))
        // A saved file edited by hand doesn't get a line it shouldn't.
        assertEquals("", ComputerLink.authorizedKeys(listOf(c.copy(key = "$key\nssh-ed25519 BBBB"), c.copy(id = "x\" y"))))
    }

    @Test fun sshdLetsTheLinkUserInOnlyFromLocalAddresses() {
        val allow = ComputerLink.SSHD_CONFIG.lines().single { it.startsWith("AllowUsers ") }.removePrefix("AllowUsers ").split(' ')
        // Each entry names an address range: a bare user would be let in from anywhere.
        assertTrue(allow.toString(), allow.all { it.startsWith("omalink@") && it.contains('/') })
        for (range in listOf("192.168.0.0/16", "10.0.0.0/8", "100.64.0.0/10", "fc00::/7", "127.0.0.0/8")) assertTrue(range, "omalink@$range" in allow)
        assertTrue(ComputerLink.SSHD_CONFIG.contains("\nAuthenticationMethods publickey\n"))
    }

    @Test fun linksChangeWithWhatAComputerMayFollow() {
        val a = ComputerLink.Computer("a1", "laptop", "ssh-ed25519 AAAA", 0, null)
        val b = ComputerLink.Computer("b2", "desk", "ssh-ed25519 BBBB", 0, null)
        assertEquals(ComputerLink.links(listOf(a, b)), ComputerLink.links(listOf(b, a)))
        assertTrue(ComputerLink.links(listOf(a, b)) != ComputerLink.links(listOf(a)))
        assertTrue(ComputerLink.links(listOf(a)) != ComputerLink.links(listOf(a.copy(syncTheme = false))))
        assertTrue(ComputerLink.links(listOf(a)) != ComputerLink.links(listOf(a.copy(syncBackground = true))))
        // A widget switched on or off is no reason to ask again: watch's key has the widgets.
        assertEquals(ComputerLink.links(listOf(a)), ComputerLink.links(listOf(a.copy(widgets = listOf("notes.owl")))))
        assertEquals("x\n\n\n\nabc\n", ComputerLink.themeFile("x", "", "", "", "abc"))
    }

    @Test fun writesTheThemeAndOnlyAGithubSourceAndAPlainBackground() {
        assertEquals(
            "redshift\nhttps://github.com/TheFifeDawg/omarchy-redshift-theme\n2-orbit.jpg\n\n\n",
            ComputerLink.themeFile("redshift", "https://github.com/TheFifeDawg/omarchy-redshift-theme", "2-orbit.jpg"),
        )
        assertEquals("tokyo-night\n\n\n\n\n", ComputerLink.themeFile("tokyo-night", ""))
        assertEquals("x\n\n\n\n\n", ComputerLink.themeFile("x", "https://github.com/a/b\"; rm -rf ~"))
        assertEquals("x\n\n\n\n\n", ComputerLink.themeFile("x", "http://github.com/a/b"))
        assertEquals("ab\n\n\n\n\n", ComputerLink.themeFile("a\"b", ""))
        // A video, a path or a name JSON would read differently isn't passed on.
        for (bad in listOf("intro.mp4", "../x.jpg", "a\"b.jpg", "a b.jpg")) assertEquals(bad, "x\n\n\n\n\n", ComputerLink.themeFile("x", "", bad))
        assertTrue(ComputerLink.validImage("image-1795363479032.webp"))
    }

    @Test fun takesOnlyWidgetFileNames() {
        for (ok in listOf("clock.owl", "market-simon-today.owl", "My_Widget.2.owl")) assertTrue(ok, ComputerLink.validName(ok))
        for (bad in listOf("../x.owl", ".stock-drawer.owl", "a/b.owl", "x.txt", "-x.owl", "", "x.owl\n")) assertFalse(bad, ComputerLink.validName(bad))
    }

    @Test fun takesOnlyUtf8() {
        assertTrue(ComputerLink.isText("widget \"ü\" {}".toByteArray()))
        assertFalse(ComputerLink.isText(byteArrayOf(0xff.toByte(), 0xfe.toByte())))
    }
}
