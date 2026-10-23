package dev.omalauncher.widgets.runtime

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.omalauncher.sha256Hex
import dev.omalauncher.upgrade.writeAtomically
import dev.omalauncher.widgets.lang.Global
import dev.omalauncher.widgets.lang.Program
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A widget's own fonts by src (owl 10): a TTF or OTF from an asset, a file in this system or the web,
 * loaded off the main thread. A variable font with a weight axis gets a face at each hundred from 100
 * to 900, so `fontWeight` picks a real weight instead of a smeared bold.
 */
internal class WidgetFonts(
    private val program: Program,
    private val services: Services,
    private val report: (String) -> Unit,
) {
    /** Where loads run; set when the widget starts. */
    var scope: CoroutineScope? = null

    /** Loaded fonts by src, null while loading or when it failed; see [font]. */
    private val fonts = mutableStateMapOf<String, FontFamily?>()

    /** Srcs asked for so far; main thread only. */
    private val requests = HashSet<String>()

    /** The font for [src], or null until it's loaded. Safe to call while drawing: it only reads state. */
    fun font(src: String): FontFamily? {
        if (src !in requests) {
            // Not from inside a draw: that would write state while drawing.
            scope?.launch {
                if (src in requests) return@launch
                // A src that changes with data mustn't keep every font it ever named.
                if (requests.size >= MAX_FONTS) {
                    requests.clear()
                    fonts.clear()
                }
                requests.add(src)
                val loaded = withContext(Dispatchers.IO) { runCatching { load(src) } }
                loaded.exceptionOrNull()?.let { report("font '$src' ${it.message ?: it}") }
                fonts[src] = loaded.getOrNull()
            }
        }
        return fonts[src]
    }

    private suspend fun load(src: String): FontFamily? {
        val file =
            when {
                src.startsWith("asset:") -> (program.globals.firstOrNull { it.name == src.removePrefix("asset:") } as? Global.Asset)?.bytes?.let(::kept)
                src.startsWith("https://") -> services.remoteFile(src)
                else -> services.guestFile(src)?.takeIf { it.isFile }
            } ?: throw IllegalStateException(if (src.startsWith("https://")) "couldn't be downloaded" else "couldn't be found")
        if (file.length() > MAX_FILE_BYTES) throw IllegalStateException("is over ${MAX_FILE_BYTES shr 20} MB")
        val bytes = file.readBytes()
        if (bytes.size < 12) throw IllegalStateException("isn't a font")
        when (String(bytes, 0, 4, Charsets.ISO_8859_1)) {
            "\u0000\u0001\u0000\u0000", "OTTO", "true", "ttcf" -> {}
            "wOFF", "wOF2" -> throw IllegalStateException("is a WOFF web font, which Android can't read; use the TTF or OTF")
            else -> throw IllegalStateException("isn't a TTF or OTF font")
        }
        // Parsed here, off the main thread, so a damaged file is reported rather than met while text is laid out.
        android.graphics.fonts.Font.Builder(file).build()
        // A face per weight that Compose picks from by fontWeight; one face alone it makes bold and italic itself.
        val axis = weightAxis(bytes) ?: return FontFamily(Font(file))
        val weights = (100..900 step 100).map { it.coerceIn(axis.first, axis.last) }.distinct()
        return FontFamily(weights.map { w -> Font(file, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) })
    }

    /** An asset's font as a file, which Android loads fonts from: in the cache, by its content, so each is written once. */
    private fun kept(bytes: ByteArray): File {
        val file = File(File(System.getProperty("java.io.tmpdir"), "owl-fonts").apply { mkdirs() }, sha256Hex(bytes))
        if (file.length() != bytes.size.toLong()) writeAtomically(file, bytes)
        return file
    }

    private companion object {
        /** Distinct fonts kept per widget. */
        private const val MAX_FONTS = 8

        /** Font files larger than this aren't read. */
        private const val MAX_FILE_BYTES = 8 shl 20
    }
}

/**
 * The range of a variable font's weight axis, from its fvar table; null for a font without one. In a
 * collection (ttcf) only plain fonts are looked at, so it reads as without.
 */
internal fun weightAxis(bytes: ByteArray): IntRange? {
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    if (b.getInt(0) == 0x74746366) return null
    val tables = b.getShort(4).toInt() and 0xffff
    for (i in 0 until tables) {
        val record = 12 + 16 * i
        if (record + 16 > bytes.size) return null
        if (b.getInt(record) != 0x66766172) continue
        val fvar = b.getInt(record + 8)
        if (fvar < 0 || fvar + 16 > bytes.size) return null
        val axes = fvar + (b.getShort(fvar + 4).toInt() and 0xffff)
        val count = b.getShort(fvar + 8).toInt() and 0xffff
        val size = b.getShort(fvar + 10).toInt() and 0xffff
        for (a in 0 until count) {
            val at = axes + a * size
            if (at + 20 > bytes.size) return null
            if (b.getInt(at) != 0x77676874) continue
            // Fixed 16.16: the minimum, default and maximum.
            val min = b.getInt(at + 4) shr 16
            val max = b.getInt(at + 12) shr 16
            return if (min < max) min..max else null
        }
        return null
    }
    return null
}
