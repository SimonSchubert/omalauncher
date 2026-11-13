package dev.omalauncher

import java.io.InputStream

fun InputStream.readUpTo(limit: Int): ByteArray = readNBytes(limit)
