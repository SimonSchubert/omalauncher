package dev.omalauncher.packageinstall

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A bounded Android action requested by a guest agent. */
data class DeviceActionRequest(
    val id: String,
    val action: String,
    val target: String,
    val reason: String,
)

/** Protocol for the guest helper; the Android host remains the action authority. */
object DeviceActionProtocol {
    const val MAX_REQUEST_BYTES = 4096
    const val MAX_TARGET_LENGTH = 512
    const val MAX_REASON_LENGTH = 240

    const val OPEN_PACKAGE = "open-package"
    const val OPEN_COMPONENT = "open-component"
    const val OPEN_URL = "open-url"
    const val OPEN_SPLIT_PACKAGES = "open-split-packages"

    private val idPattern = Regex("[A-Za-z0-9._-]{1,96}")
    private val packagePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val classPattern = Regex("(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*")

    fun parse(
        id: String,
        bytes: ByteArray,
    ): DeviceActionRequest {
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
        val lines = text.split('\n', limit = 4)
        require(lines.size < 4 || lines[3].removeSuffix("\r").isEmpty()) { "request has trailing data" }
        val action = lines.firstOrNull()?.removeSuffix("\r")?.trim().orEmpty()
        val target = lines.getOrNull(1)?.removeSuffix("\r")?.trim().orEmpty()
        val reason = lines.getOrNull(2)?.removeSuffix("\r")?.trim().orEmpty()
        require(action in setOf(OPEN_PACKAGE, OPEN_COMPONENT, OPEN_URL, OPEN_SPLIT_PACKAGES)) { "unsupported device action" }
        require(target.length in 1..MAX_TARGET_LENGTH) { "device action target is invalid" }
        require(target.none { it.isISOControl() || it.isWhitespace() }) { "device action target contains whitespace" }
        require(reason.length <= MAX_REASON_LENGTH) { "reason is too long" }
        require(reason.none { it == '\r' || it == '\n' || it.isISOControl() }) { "reason contains control characters" }
        when (action) {
            OPEN_PACKAGE -> require(packagePattern.matches(target)) { "package name is invalid" }
            OPEN_COMPONENT -> parseComponent(target)
            OPEN_URL -> parseUrl(target)
            OPEN_SPLIT_PACKAGES -> splitPackages(target)
        }
        return DeviceActionRequest(id, action, target, reason)
    }

    fun response(message: String): String {
        val clean = message
            .replace('\r', ' ')
            .replace('\n', ' ')
            .filterNot(Char::isISOControl)
            .trim()
            .take(MAX_REASON_LENGTH)
        return if (clean == "ok") "ok" else "error: ${clean.ifEmpty { "device action failed" }}"
    }

    private fun parseComponent(target: String) {
        val slash = target.indexOf('/')
        require(slash > 0 && slash == target.lastIndexOf('/')) { "component must be package/class" }
        require(packagePattern.matches(target.substring(0, slash))) { "component package is invalid" }
        val className = target.substring(slash + 1)
        require(className.startsWith('.') || classPattern.matches(className)) { "component class is invalid" }
        require(className.length > 1) { "component class is invalid" }
    }

    private fun parseUrl(target: String) {
        val uri = runCatching { URI(target) }.getOrElse { error("URL is invalid") }
        require(uri.scheme?.lowercase() in setOf("http", "https")) { "URL must use http or https" }
        require(!uri.host.isNullOrEmpty()) { "URL host is missing" }
        require(uri.userInfo == null) { "URL user info is not allowed" }
    }

    fun splitPackages(target: String): Pair<String, String> {
        val packages = target.split(',', limit = 2)
        require(packages.size == 2) { "split action needs two package names" }
        require(packages.all { packagePattern.matches(it) }) { "split package name is invalid" }
        require(packages[0] != packages[1]) { "split action needs two different packages" }
        return packages[0] to packages[1]
    }
}
