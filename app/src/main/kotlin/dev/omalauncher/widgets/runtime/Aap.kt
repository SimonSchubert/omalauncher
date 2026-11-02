package dev.omalauncher.widgets.runtime

import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool

/**
 * The part of Apple's accessory protocol (AAP) that `android.airpods` speaks with AirPods, over L2CAP on [PSM]: the
 * packets that set a link up, what the AirPods report (battery, ear detection, settings, their model) and the control
 * packets that change a setting. Bytes in, [AapState] out, nothing Android: tested on the JVM.
 *
 * A packet from the AirPods is `04 00 04 00`, a 16-bit little-endian opcode, then its data. A control packet (opcode
 * 9) is a setting's id and its value, sent both ways: the AirPods send all their settings as the link is set up, and
 * answer a new listening mode with the same packet, though not a change to the other settings.
 */
internal object Aap {
    const val PSM = 0x1001

    /** The service AirPods (and Beats) list for AAP, by which a paired device is known as one. */
    const val UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a"

    /** First on a new link; the AirPods answer nothing else until they've had it. */
    val HANDSHAKE = hex("00000400010002000000000000000000")

    /** What this host can do, sent after the handshake: without it Conversation Awareness stays off while audio plays. */
    val FEATURES = hex("040004004d00d700000000000000")

    /** Asks for battery, ear detection and setting changes, and for every setting as it is now. */
    val NOTIFICATIONS = hex("040004000f00ffffffffff")

    /** The handshake's answer starts with this; everything after is an AAP packet. */
    private val HANDSHAKE_ACK = hex("01000400")

    const val BATTERY = 0x04
    const val EAR = 0x06
    const val CONTROL = 0x09
    const val METADATA = 0x1D
    const val CONVERSATION_LEVEL = 0x4B

    /** Settings, by their control id. Most take 1 for on and 2 for off. */
    const val MODE = 0x0D
    const val EAR_DETECTION = 0x0A
    const val ONE_BUD = 0x1B
    const val CONVERSATION = 0x28
    const val ADAPTIVE_LEVEL = 0x2E

    /** Battery components, as a battery report names them. */
    const val HEADSET = 0x01
    const val RIGHT = 0x02
    const val LEFT = 0x04
    const val CASE = 0x08

    /** Listening modes by their value for [MODE], in the order AirPods number them. */
    val MODES = listOf("off", "anc", "transparency", "adaptive")

    fun isHandshakeAck(p: ByteArray) = p.size >= HANDSHAKE_ACK.size && HANDSHAKE_ACK.indices.all { p[it] == HANDSHAKE_ACK[it] }

    /** A control packet: [id] set to [value]. */
    fun control(
        id: Int,
        value: Int,
    ) = byteArrayOf(4, 0, 4, 0, CONTROL.toByte(), 0, id.toByte(), value.toByte(), 0, 0, 0)

    /** The opcode of an AAP packet, or null for anything else. */
    fun opcode(p: ByteArray): Int? =
        if (p.size >= 6 && p[0].toInt() == 4 && p[1].toInt() == 0 && p[2].toInt() == 4 && p[3].toInt() == 0) (p[4].u or (p[5].u shl 8)) else null

    internal fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

private val Byte.u get() = toInt() and 0xFF

/** One battery: [level] in percent. */
internal data class AapBattery(
    val level: Int,
    val charging: Boolean,
)

/**
 * What a pair of AirPods has said on its link so far. [batteries] has only what's connected now, by component;
 * [primary] is the bud listed first in the last battery report, which is the one ear detection names first;
 * [ear] the last ear report's two bytes as it sent them (0 in an ear, 1 out, 2 in the case); [controls] every
 * setting it reported, by control id, with the first byte of its value.
 */
internal data class AapState(
    val connected: Boolean = false,
    val name: String? = null,
    val modelNumber: String? = null,
    val batteries: Map<Int, AapBattery> = emptyMap(),
    val primary: Int? = null,
    val ear: Pair<Int, Int>? = null,
    val controls: Map<Int, Int> = emptyMap(),
    val speaking: Boolean = false,
) {
    /** This state after [p], one packet from the AirPods; what it doesn't know leaves it as it was. */
    fun read(p: ByteArray): AapState {
        val data = p.copyOfRange(6.coerceAtMost(p.size), p.size)
        return when (Aap.opcode(p)) {
            Aap.BATTERY -> battery(data)
            Aap.EAR -> if (data.size >= 2) copy(ear = data[0].u to data[1].u) else this
            Aap.CONTROL -> if (data.size >= 2) copy(controls = controls + (data[0].u to data[1].u)) else this
            Aap.METADATA -> metadata(data)
            // 02 00 01 and a level: 1 or 2 while the wearer speaks and the volume is down, 3 when they've stopped, up to 8 or 9 back at normal.
            Aap.CONVERSATION_LEVEL -> if (data.size >= 4) copy(speaking = data[3].u in 1..2) else this
            else -> this
        }
    }

    /**
     * A count, then per component: its id, 01, the level, the status (1 charging, 2 not, 4 disconnected), 01. One cut
     * short is left out whole, rather than read as buds gone.
     */
    private fun battery(data: ByteArray): AapState {
        val count = data.firstOrNull()?.u ?: return this
        if (data.size < 1 + count * 5) return this
        val found = LinkedHashMap<Int, AapBattery>()
        for (i in 0 until count) {
            val at = 1 + i * 5
            val component = data[at].u
            val level = data[at + 2].u
            val status = data[at + 3].u
            if (status == 4 || level > 100) continue
            found[component] = AapBattery(level, status == 1)
        }
        val first = found.keys.firstOrNull { it == Aap.LEFT || it == Aap.RIGHT }
        return copy(batteries = found, primary = first ?: primary)
    }

    /**
     * Strings ending in a zero byte, after a few bytes that vary: the name, the model number, "Apple Inc.", the serial
     * number and more. Found by the model number's shape rather than by place.
     */
    private fun metadata(data: ByteArray): AapState {
        val parts = String(data, Charsets.UTF_8).split('\u0000')
        val at = parts.indexOfFirst { MODEL_NUMBER.matches(it) }
        if (at < 0) return this
        return copy(name = parts.getOrNull(at - 1)?.takeIf { it.isNotBlank() } ?: name, modelNumber = parts[at])
    }

    /** What this model can do: its listening modes, and which settings it has. */
    val model: AirPodsModel? get() = modelNumber?.let { AirPodsModel.of(it) }

    /**
     * The listening modes this pair has, in [Aap.MODES]' order. A model this launcher doesn't know yet has the ones its
     * settings show: any that reported a mode has off, noise cancellation and transparency, and adaptive with a level.
     */
    val modes: List<String>
        get() =
            model?.modes ?: when {
                Aap.MODE !in controls -> emptyList()
                Aap.ADAPTIVE_LEVEL in controls -> Aap.MODES
                else -> Aap.MODES.take(3)
            }

    /** Whether it has a setting: by its model, else by whether it reported one. */
    fun has(control: Int): Boolean = model?.controls?.contains(control) ?: (control in controls)

    /** The value `source a = android.airpods` gives; [available] is whether the launcher may use Bluetooth. */
    fun value(
        available: Boolean,
        name: String?,
    ): Value {
        fun on(control: Int): Value = if (has(control)) controls[control]?.let { bool(it == 1) } ?: Null else Null
        val mode = controls[Aap.MODE]?.let { Aap.MODES.getOrNull(it - 1) }?.takeIf { it in modes }
        return Obj(
            mapOf(
                "available" to bool(available),
                "connected" to bool(connected),
                "name" to ((name ?: this.name)?.let(::Str) ?: Null),
                "model" to (model?.name?.let(::Str) ?: Null),
                "modelNumber" to (modelNumber?.let(::Str) ?: Null),
                "left" to bud(Aap.LEFT),
                "right" to bud(Aap.RIGHT),
                "case" to battery(Aap.CASE),
                "headset" to battery(Aap.HEADSET),
                "mode" to (mode?.let(::Str) ?: Null),
                "modes" to Lst(modes.map(::Str)),
                "adaptiveLevel" to (if (has(Aap.ADAPTIVE_LEVEL)) controls[Aap.ADAPTIVE_LEVEL]?.let { Num(it.toDouble()) } ?: Null else Null),
                "conversation" to on(Aap.CONVERSATION),
                "speaking" to bool(speaking && controls[Aap.CONVERSATION] == 1),
                "oneBud" to on(Aap.ONE_BUD),
                "earDetection" to on(Aap.EAR_DETECTION),
            ),
        )
    }

    private fun battery(component: Int): Value =
        batteries[component]?.let { Obj(mapOf("level" to Num(it.level.toDouble()), "charging" to bool(it.charging))) } ?: Null

    /** A bud's battery, with where it is from the last ear report, which names the primary bud first. */
    private fun bud(component: Int): Value {
        val b = batteries[component] ?: return Null
        val where =
            ear?.let { (first, second) ->
                when (primary) {
                    null -> null
                    component -> first
                    else -> second
                }
            }
        return Obj(
            mapOf(
                "level" to Num(b.level.toDouble()),
                "charging" to bool(b.charging),
                "inEar" to bool(where == 0),
                "inCase" to bool(where == 2),
            ),
        )
    }

    private companion object {
        val MODEL_NUMBER = Regex("A\\d{4}")
    }
}

/**
 * A model of AirPods by the number Apple gives each (support.apple.com/109525), with what it can do as Apple lists it
 * (apple.com/airpods/compare): its listening modes, and the settings among [Aap]'s it has. Both AirPods 4 are named
 * "AirPods 4": the name is the family, the modes say which one it is.
 */
internal class AirPodsModel(
    val name: String,
    val modes: List<String>,
    val controls: Set<Int>,
) {
    companion object {
        private val NONE = AirPodsModel("AirPods", emptyList(), setOf(Aap.EAR_DETECTION))
        private val H1_ANC = Aap.MODES.take(3)
        private val H2 = setOf(Aap.EAR_DETECTION, Aap.ONE_BUD, Aap.CONVERSATION, Aap.ADAPTIVE_LEVEL)

        private val BY_NUMBER: Map<String, AirPodsModel> =
            listOf(
                listOf("A1523", "A1722", "A2031", "A2032") to NONE,
                listOf("A2564", "A2565") to AirPodsModel("AirPods 3", emptyList(), setOf(Aap.EAR_DETECTION)),
                listOf("A3050", "A3053", "A3054") to AirPodsModel("AirPods 4", emptyList(), setOf(Aap.EAR_DETECTION)),
                listOf("A3055", "A3056", "A3057") to AirPodsModel("AirPods 4", Aap.MODES, H2),
                listOf("A2083", "A2084") to AirPodsModel("AirPods Pro", H1_ANC, setOf(Aap.EAR_DETECTION, Aap.ONE_BUD)),
                listOf("A2698", "A2699", "A2931", "A3047", "A3048", "A3049") to AirPodsModel("AirPods Pro 2", Aap.MODES, H2),
                // The Pro 3 has no off.
                listOf("A3063", "A3064", "A3065") to AirPodsModel("AirPods Pro 3", Aap.MODES.drop(1), H2),
                // Headphones: one battery, no case and no second bud.
                listOf("A2096", "A3184") to AirPodsModel("AirPods Max", H1_ANC, setOf(Aap.EAR_DETECTION)),
            ).flatMap { (numbers, model) -> numbers.map { it to model } }.toMap()

        fun of(number: String): AirPodsModel? = BY_NUMBER[number]
    }
}
