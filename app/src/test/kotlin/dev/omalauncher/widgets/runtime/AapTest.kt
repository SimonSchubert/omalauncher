package dev.omalauncher.widgets.runtime

import dev.omalauncher.widgets.lang.FALSE
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.TRUE
import dev.omalauncher.widgets.lang.Value
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AapTest {
    private fun read(vararg packets: String) = packets.fold(AapState(connected = true)) { s, p -> s.read(Aap.hex(p.replace(" ", ""))) }

    private fun Value.at(vararg path: String): Value = path.fold(this) { v, k -> (v as Obj).fields[k] ?: error("no $k in $v") }

    /** AirPods 4 with noise cancellation, as one says itself: its name and model, then its settings. */
    private val airpods4 =
        arrayOf(
            // 1d00, a few bytes, then "AirPods", "A3055", "Apple Inc.", a serial, the firmware, ...
            "040004001d00 02d5000400" + "AirPods".hexOf() + "00" + "A3055".hexOf() + "00" + "Apple Inc.".hexOf() + "00" + "XYZ123".hexOf() + "00" +
                "7A305".hexOf() + "00" + "7A305".hexOf() + "00",
            "04 00 04 00 09 00 0D 02 00 00 00",
            "04 00 04 00 09 00 28 01 00 00 00",
            "04 00 04 00 09 00 2E 32 00 00 00",
            "04 00 04 00 09 00 1B 02 00 00 00",
            "04 00 04 00 09 00 0A 01 00 00 00",
        )

    @Test fun batteriesByComponentWithTheFirstBudPrimary() {
        // Right 100 % not charging, left 99 % charging, case 17 % not charging: right listed first.
        val s = read("04 00 04 00 04 00 03 02 01 64 02 01 04 01 63 01 01 08 01 11 02 01")
        assertEquals(mapOf(Aap.RIGHT to AapBattery(100, false), Aap.LEFT to AapBattery(99, true), Aap.CASE to AapBattery(17, false)), s.batteries)
        assertEquals(Aap.RIGHT, s.primary)
        val v = s.value(true, null)
        assertEquals(Num(100.0), v.at("right", "level"))
        assertEquals(TRUE, v.at("left", "charging"))
        assertEquals(Obj(mapOf("level" to Num(17.0), "charging" to FALSE)), v.at("case"))
        assertEquals(Null, v.at("headset"))
    }

    @Test fun aDisconnectedComponentIsLeftOut() {
        // The case has been away a while (status 4): it's gone rather than at its last level.
        val s = read("04 00 04 00 04 00 03 04 01 50 02 01 02 01 52 02 01 08 01 00 04 01")
        assertEquals(setOf(Aap.LEFT, Aap.RIGHT), s.batteries.keys)
        assertEquals(Aap.LEFT, s.primary)
        assertEquals(Null, s.value(true, null).at("case"))
    }

    @Test fun earDetectionNamesThePrimaryBudFirst() {
        // Left is primary: the ear report's first byte is the left bud's.
        val s = read("04 00 04 00 04 00 02 04 01 50 02 01 02 01 52 02 01", "04 00 04 00 06 00 00 02")
        val v = s.value(true, null)
        assertEquals(TRUE, v.at("left", "inEar"))
        assertEquals(FALSE, v.at("left", "inCase"))
        assertEquals(FALSE, v.at("right", "inEar"))
        assertEquals(TRUE, v.at("right", "inCase"))
        // Right becomes primary when it's listed first: the same report now reads the other way round.
        val w = s.read(Aap.hex("0400040004000202015202010401500201")).value(true, null)
        assertEquals(TRUE, w.at("right", "inEar"))
        assertEquals(TRUE, w.at("left", "inCase"))
    }

    @Test fun modelSettingsAndModes() {
        val v = read(*airpods4).value(true, "Pavel's AirPods")
        assertEquals(Str("Pavel's AirPods"), v.at("name"))
        assertEquals(Str("AirPods 4"), v.at("model"))
        assertEquals(Str("A3055"), v.at("modelNumber"))
        assertEquals(Str("anc"), v.at("mode"))
        assertEquals(Lst(listOf(Str("off"), Str("anc"), Str("transparency"), Str("adaptive"))), v.at("modes"))
        assertEquals(Num(50.0), v.at("adaptiveLevel"))
        assertEquals(TRUE, v.at("conversation"))
        assertEquals(FALSE, v.at("oneBud"))
        assertEquals(TRUE, v.at("earDetection"))
        assertEquals(TRUE, v.at("connected"))
        assertEquals(TRUE, v.at("available"))
        // Without a name from Android, the one the AirPods gave.
        assertEquals(Str("AirPods"), read(*airpods4).value(true, null).at("name"))
    }

    @Test fun whatAModelLacksIsNull() {
        // AirPods Pro 3 have no off; AirPods 3 no listening modes at all, and none of those settings even if reported.
        val pro3 = read("040004001d00" + "AirPods Pro".hexOf() + "00" + "A3064".hexOf() + "00" + "Apple Inc.".hexOf() + "00", "04 00 04 00 09 00 0D 03 00 00 00")
        assertEquals(listOf("anc", "transparency", "adaptive"), pro3.modes)
        assertEquals(Str("transparency"), pro3.value(true, null).at("mode"))
        val gen3 = read("040004001d00" + "AirPods".hexOf() + "00" + "A2565".hexOf() + "00" + "Apple Inc.".hexOf() + "00", "04 00 04 00 09 00 28 01 00 00 00")
        val v = gen3.value(true, null)
        assertEquals(Lst(emptyList()), v.at("modes"))
        assertEquals(Null, v.at("mode"))
        assertEquals(Null, v.at("conversation"))
        assertEquals(Null, v.at("adaptiveLevel"))
    }

    @Test fun anUnknownModelGoesByWhatItReports() {
        val s = read("04 00 04 00 09 00 0D 01 00 00 00", "04 00 04 00 09 00 2E 64 00 00 00", "04 00 04 00 09 00 28 02 00 00 00")
        assertNull(s.model)
        assertEquals(Aap.MODES, s.modes)
        val v = s.value(true, null)
        assertEquals(Str("off"), v.at("mode"))
        assertEquals(FALSE, v.at("conversation"))
        assertEquals(Null, v.at("oneBud"))
        assertEquals(Null, v.at("model"))
        // Nothing reported: no modes, nothing to set.
        assertEquals(emptyList<String>(), read().modes)
    }

    @Test fun speakingOnlyWhileConversationAwarenessIsOn() {
        val on = read(*airpods4, "04 00 04 00 4B 00 02 00 01 01")
        assertEquals(TRUE, on.value(true, null).at("speaking"))
        assertEquals(FALSE, on.read(Aap.hex("040004004b0002000108")).value(true, null).at("speaking"))
        val off = read(*airpods4, "04 00 04 00 09 00 28 02 00 00 00", "04 00 04 00 4B 00 02 00 01 02")
        assertEquals(FALSE, off.value(true, null).at("speaking"))
    }

    @Test fun beforeAnythingIsHeard() {
        val v = AapState().value(false, null)
        assertEquals(FALSE, v.at("available"))
        assertEquals(FALSE, v.at("connected"))
        for (k in listOf("name", "model", "left", "right", "case", "headset", "mode", "adaptiveLevel", "conversation", "oneBud", "earDetection")) assertEquals(k, Null, v.at(k))
        assertEquals(Lst(emptyList()), v.at("modes"))
    }

    /** What a pair of AirPods 4 (A3055) sent a Pixel, one bud in an ear and the other in its shut case, then both in the open case. */
    @Test fun aRealSession() {
        val settings =
            arrayOf(
                "0400040009000d01000000",
                "0400040009002802000000",
                "0400040009002e64000000",
                "0400040009001b02000000",
                "0400040009001800000000",
                "0400040009003e02000000",
            )
        val oneOut = read(*airpods4, *settings, "0400040004000302015d020104010004010801000401", "0400040006000003")
        var v = oneOut.value(true, null)
        assertEquals(Obj(mapOf("level" to Num(93.0), "charging" to FALSE, "inEar" to TRUE, "inCase" to FALSE)), v.at("right"))
        assertEquals(Null, v.at("left"))
        assertEquals(Null, v.at("case"))
        assertEquals(Str("off"), v.at("mode"))
        assertEquals(FALSE, v.at("conversation"))
        assertEquals(Num(100.0), v.at("adaptiveLevel"))
        assertEquals(FALSE, v.at("oneBud"))
        val inCase = oneOut.read(Aap.hex("0400040004000302015a0101040164010108014e0201")).read(Aap.hex("0400040006000202"))
        v = inCase.value(true, null)
        assertEquals(Obj(mapOf("level" to Num(90.0), "charging" to TRUE, "inEar" to FALSE, "inCase" to TRUE)), v.at("right"))
        assertEquals(Obj(mapOf("level" to Num(100.0), "charging" to TRUE, "inEar" to FALSE, "inCase" to TRUE)), v.at("left"))
        assertEquals(Obj(mapOf("level" to Num(78.0), "charging" to FALSE)), v.at("case"))
    }

    @Test fun aSettingSentReadsAsTheAnswerItGetsNone() {
        // They answer a new listening mode, not the other settings: AirPods.kt reads what it sent as their answer.
        val s = read(*airpods4)
        assertEquals(FALSE, s.read(Aap.control(Aap.CONVERSATION, 2)).value(true, null).at("conversation"))
        assertEquals(TRUE, s.read(Aap.control(Aap.ONE_BUD, 1)).value(true, null).at("oneBud"))
        assertEquals(Num(75.0), s.read(Aap.control(Aap.ADAPTIVE_LEVEL, 75)).value(true, null).at("adaptiveLevel"))
    }

    @Test fun packetsOutAndOddOnesIn() {
        assertArrayEquals(Aap.hex("04000400090028020000 00".replace(" ", "")), Aap.control(Aap.CONVERSATION, 2))
        assertArrayEquals(Aap.hex("0400040009000d040000 00".replace(" ", "")), Aap.control(Aap.MODE, 4))
        assertTrue(Aap.isHandshakeAck(Aap.hex("01000400000000")))
        assertFalse(Aap.isHandshakeAck(Aap.hex("040004000400")))
        // Short, cut off or unknown packets change nothing.
        val s = read(*airpods4)
        for (p in listOf("", "0400", "040004000400", "04000400040002020164", "040004000600", "0400040009000d", "040004004b00020001", "040004001d00ffff", "12340000")) assertEquals(p, s, s.read(Aap.hex(p)))
        // A battery report cut short keeps the batteries there were.
        val b = read("04 00 04 00 04 00 02 04 01 50 02 01 02 01 52 02 01")
        assertEquals(b, b.read(Aap.hex("04000400040002040150020102")))
    }

    private fun String.hexOf() = toByteArray().joinToString("") { "%02x".format(it) }
}
