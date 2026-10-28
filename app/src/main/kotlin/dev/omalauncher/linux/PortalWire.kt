package dev.omalauncher.linux

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/** Versioned, bounded socket packet. Values are base64 so newlines cannot forge fields. */
internal object PortalWire {
    enum class UiTarget { OPEN, PRINT }

    fun uiTarget(operation: String?): UiTarget? = when (operation) {
        "open-uri", "open-file", "email" -> UiTarget.OPEN
        "print" -> UiTarget.PRINT
        else -> null
    }

    const val MAX_BYTES = 64 * 1024
    const val MAX_FDS = 8
    private val key = Regex("[a-z0-9-]{1,64}")
    val schemes = setOf("http", "https", "mailto", "tel", "sms", "geo")

    fun decode(bytes: ByteArray): Map<String, String> {
        require(bytes.size in 1..MAX_BYTES) { "invalid packet size" }
        val text = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val fields = linkedMapOf<String, String>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val at = line.indexOf('=')
            require(at > 0) { "missing field separator" }
            val name = line.substring(0, at)
            require(key.matches(name) && name !in fields) { "invalid or duplicate field" }
            val value = Base64.getDecoder().decode(line.substring(at + 1))
            val decoded = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString()
            require('\u0000' !in decoded) { "NUL in field" }
            fields[name] = decoded
        }
        require(fields["version"] == "1") { "unsupported packet version" }
        require(fields["operation"] in setOf("open-uri", "open-file", "open-directory", "scheme-supported", "email", "inhibit", "notification", "prepare-print", "print")) { "unsupported operation" }
        return fields
    }

    fun emailAddresses(fields: Map<String, String>, key: String): Array<String> =
        generateSequence(0) { it + 1 }.map { fields["$key-$it"] }.takeWhile { it != null }.map { it!! }.toList().toTypedArray()

    /** Email v3 makes addresses additive to the legacy address, not a fallback. */
    fun emailRecipients(fields: Map<String, String>): Array<String> =
        (listOfNotNull(fields["address"]) + emailAddresses(fields, "addresses")).toTypedArray()

    fun directoryToOpen(guestPath: String, containing: Boolean): String =
        if (containing) guestPath.substringBeforeLast('/', "").ifEmpty { "/" } else guestPath

    /** Android matches intent schemes case-sensitively; preserve everything after ':'. */
    fun normalizeUriScheme(value: String): String {
        require(safeUri(value)) { "unsupported URI" }
        val separator = value.indexOf(':')
        return value.substring(0, separator).lowercase(java.util.Locale.ROOT) + value.substring(separator)
    }

    fun safeUri(value: String): Boolean {
        if (value.any { it <= '\u001f' || it == '\u007f' }) return false
        val scheme = value.substringBefore(':', "").lowercase(java.util.Locale.ROOT)
        return scheme in schemes
    }
}
