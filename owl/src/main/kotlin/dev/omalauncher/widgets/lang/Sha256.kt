package dev.omalauncher.widgets.lang

import java.security.MessageDigest

/** Lowercase hex, as sha256sum prints it. */
fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

/** Lowercase hex of what [MessageDigest.digest] returned. */
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
