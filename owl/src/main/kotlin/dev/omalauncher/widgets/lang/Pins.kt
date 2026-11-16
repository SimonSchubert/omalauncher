package dev.omalauncher.widgets.lang

/** `pin:` values: a certificate's SHA-256, which a server with a certificate of its own is trusted by. */
object Pins {
    private val HEX = Regex("[0-9a-f]{64}")

    /** [pin] as 64 lowercase hex digits, without openssl's colons or spaces; null if it isn't a SHA-256. */
    fun normalize(pin: String): String? = pin.lowercase().filter { it != ':' && !it.isWhitespace() }.takeIf { HEX.matches(it) }

    /** The SHA-256 of a certificate's DER bytes, as [normalize] gives a pin. */
    fun of(der: ByteArray): String = sha256Hex(der)
}
