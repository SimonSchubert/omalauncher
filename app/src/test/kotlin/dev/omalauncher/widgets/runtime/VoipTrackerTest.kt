package dev.omalauncher.widgets.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoipTrackerTest {
    private val wa = "com.whatsapp"

    @Test fun ringingThatEndsUnansweredIsMissed() {
        val t = VoipTracker(grace = 4_000)
        t.posted(wa, "ring", ringing = true, name = "Jana", number = "", video = false, now = 1_000)
        // The app reposts it while it rings: still the same call.
        t.posted(wa, "ring", ringing = true, name = "Jana", number = "", video = false, now = 3_000)
        t.removed("ring", now = 20_000)
        assertTrue(t.due(23_000).isEmpty())
        val call = t.due(24_000).single()
        assertEquals("missed", call.direction)
        assertEquals("Jana", call.name)
        assertEquals(1_000, call.start)
        assertEquals(0, call.duration)
        assertFalse(t.busy)
    }

    @Test fun answeredInAnotherNotificationIsOneIncomingCall() {
        val t = VoipTracker(grace = 4_000)
        t.posted(wa, "ring", ringing = true, name = "Jana", number = "", video = true, now = 0)
        // WhatsApp takes the ringing one away a moment before the ongoing one comes.
        t.removed("ring", now = 10_000)
        t.posted(wa, "ongoing", ringing = false, name = "", number = "", video = false, now = 11_000)
        assertTrue(t.due(30_000).isEmpty())
        t.removed("ongoing", now = 71_000)
        val call = t.due(80_000).single()
        assertEquals("in", call.direction)
        assertEquals("Jana", call.name)
        assertTrue(call.video)
        assertEquals(0, call.start)
        assertEquals(60, call.duration)
    }

    @Test fun answeredWithTheOngoingOneComingFirst() {
        val t = VoipTracker(grace = 4_000)
        t.posted(wa, "ring", ringing = true, name = "Jana", number = "", video = false, now = 0)
        t.posted(wa, "ongoing", ringing = false, name = "Jana", number = "", video = false, now = 5_000)
        t.removed("ring", now = 5_100)
        assertTrue(t.due(60_000).isEmpty())
        t.removed("ongoing", now = 35_000)
        assertEquals(listOf("in" to 30L), t.due(40_000).map { it.direction to it.duration })
    }

    @Test fun answeredInTheSameNotification() {
        val t = VoipTracker(grace = 4_000)
        t.posted(wa, "call", ringing = true, name = "Jana", number = "", video = false, now = 0)
        t.posted(wa, "call", ringing = false, name = "Jana", number = "", video = false, now = 2_000)
        t.removed("call", now = 12_000)
        assertEquals(listOf("in" to 10L), t.due(20_000).map { it.direction to it.duration })
    }

    @Test fun firstSeenOngoingWentOut() {
        val t = VoipTracker(grace = 4_000)
        t.posted("com.facebook.orca", "out", ringing = false, name = "Petr", number = "+420777123456", video = false, now = 0)
        // Reposted each second with the timer: not a new call, and not a restart.
        for (s in 1..9) t.posted("com.facebook.orca", "out", ringing = false, name = "Petr", number = "", video = false, now = s * 1_000L)
        t.removed("out", now = 45_000)
        val call = t.due(50_000).single()
        assertEquals("out", call.direction)
        assertEquals("+420777123456", call.number)
        assertEquals(45, call.duration)
        assertEquals("com.facebook.orca-0", call.id)
    }

    @Test fun twoAppsAreTwoCalls() {
        val t = VoipTracker(grace = 4_000)
        t.posted(wa, "a", ringing = true, name = "Jana", number = "", video = false, now = 0)
        t.posted("com.facebook.orca", "b", ringing = false, name = "Petr", number = "", video = false, now = 1_000)
        t.removed("a", now = 2_000)
        assertEquals(listOf("missed"), t.due(6_000).map { it.direction })
        assertTrue(t.busy)
        t.removed("b", now = 7_000)
        assertEquals(listOf("out"), t.due(11_000).map { it.direction })
    }

    @Test fun aCallWhoseNotificationHidesBehindItsScreenLastsAsLongAsTheAudio() {
        val t = VoipTracker(grace = 4_000)
        t.posted(wa, "out", ringing = false, name = "George", number = "", video = false, now = 0)
        t.audio(calling = true, now = 1_000)
        // WhatsApp's call screen comes to the front and its notification goes: the call goes on.
        t.removed("out", now = 16_000)
        assertTrue(t.due(30_000).isEmpty())
        t.audio(calling = true, now = 600_000)
        assertTrue(t.due(600_000).isEmpty())
        // Hung up from that screen: the audio leaves the call, and the call ends there.
        t.audio(calling = false, now = 900_000)
        assertTrue(t.due(902_000).isEmpty())
        val call = t.due(904_000).single()
        assertEquals("out", call.direction)
        assertEquals(900, call.duration)
    }

    @Test fun aRingingCallMissedWhileTheAudioIsIdleEndsWithItsNotification() {
        val t = VoipTracker(grace = 4_000)
        t.audio(calling = false, now = 0)
        t.posted(wa, "ring", ringing = true, name = "Jana", number = "", video = false, now = 0)
        t.removed("ring", now = 30_000)
        assertEquals(listOf("missed"), t.due(34_000).map { it.direction })
    }

    @Test fun anUnknownRemovalChangesNothing() {
        val t = VoipTracker(grace = 4_000)
        t.posted(wa, "a", ringing = false, name = "Jana", number = "", video = false, now = 0)
        t.removed("someone else's", now = 1_000)
        assertTrue(t.due(100_000).isEmpty())
        assertEquals(setOf("a"), t.keys())
    }

    @Test fun numbersMatchHoweverTheyAreWritten() {
        val keys = listOf("+420 777 123 456", "777123456", "00420777123456", "(+420) 777-123-456").map(PhoneCalls::numberKey).toSet()
        assertEquals(setOf("777123456"), keys)
    }

    @Test fun aWhatsAppCallIsMatchedByItsNumberElseByAUniqueName() {
        val byDigits = mapOf(PhoneCalls.numberKey("420777123456") to "jana", PhoneCalls.numberKey("420605111222") to "petr")
        val byName = mapOf("jana" to listOf("jana"), "petr" to listOf("petr", "petr 2"))
        assertEquals("jana", PhoneCalls.whatsappMatch("", "Jana", byDigits, byName))
        // A number the app stated is never swapped for a same-named person's.
        assertEquals(null, PhoneCalls.whatsappMatch("+420 999 888 777", "Jana", byDigits, byName))
        assertEquals("petr", PhoneCalls.whatsappMatch("+420605111222", "", byDigits, byName))
        // Two people under one name: nobody, so the key just opens WhatsApp.
        assertEquals(null, PhoneCalls.whatsappMatch("", "Petr", byDigits, byName))
    }

    @Test fun numbersAreDialledAsDigits() {
        assertEquals("+420777123456", PhoneCalls.dialable("+420 777-123 456"))
        assertEquals("+420777123456", PhoneCalls.dialable("00420 777 123 456"))
        assertEquals("777123456", PhoneCalls.dialable("(777) 123.456"))
        assertEquals("*21#", PhoneCalls.dialable("*21#"))
    }
}
