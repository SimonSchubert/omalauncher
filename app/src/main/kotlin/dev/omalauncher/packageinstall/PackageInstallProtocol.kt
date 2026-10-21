package dev.omalauncher.packageinstall

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The request the Linux guest writes before the Android host can inspect it. */
data class PackageInstallRequest(
    val id: String,
    val guestPath: String,
    val reason: String,
)

/**
 * Small, line-based protocol for the guest helper. Keeping this independent of
 * Android makes the hostile-input rules easy to test without a device.
 */
object PackageInstallProtocol {
    const val MAX_REQUEST_BYTES = 4096
    const val MAX_PATH_LENGTH = 2048
    const val MAX_REASON_LENGTH = 240

    private val idPattern = Regex("[A-Za-z0-9._-]{1,96}")

    fun parse(
        id: String,
        bytes: ByteArray,
    ): PackageInstallRequest {
        require(idPattern.matches(id)) { "invalid request id" }
        require(bytes.size <= MAX_REQUEST_BYTES) { "request is too large" }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("request is not valid UTF-8")
        }
        require(!text.contains('\u0000')) { "request contains a NUL" }
        val lines = text.split('\n', limit = 3)
        require(lines.size < 3 || lines[2].removeSuffix("\r").isEmpty()) { "request has trailing data" }
        val path = lines.firstOrNull()?.removeSuffix("\r")?.trim().orEmpty()
        val reason = lines.getOrNull(1)?.removeSuffix("\r")?.trim().orEmpty()
        require(path.startsWith('/')) { "APK path must be absolute" }
        require(path.length <= MAX_PATH_LENGTH) { "APK path is too long" }
        require(path.none { it.isISOControl() }) { "APK path contains control characters" }
        require(reason.length <= MAX_REASON_LENGTH) { "reason is too long" }
        require(reason.none { it == '\r' || it == '\n' || it.isISOControl() }) { "reason contains control characters" }
        return PackageInstallRequest(id, path, reason)
    }

    fun response(message: String): String {
        val clean = message
            .replace('\r', ' ')
            .replace('\n', ' ')
            .filterNot(Char::isISOControl)
            .trim()
            .take(MAX_REASON_LENGTH)
        return if (clean == "ok") "ok" else "error: ${clean.ifEmpty { "installation failed" }}"
    }
}
