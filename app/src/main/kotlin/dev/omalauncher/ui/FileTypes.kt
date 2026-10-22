package dev.omalauncher.ui

import android.content.Context
import android.text.format.DateFormat
import android.webkit.MimeTypeMap
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Copied or cut items waiting to be pasted; process-wide so it survives leaving Files. */
internal object FileClipboard {
    var paths by mutableStateOf<List<String>>(emptyList())
    var cut by mutableStateOf(false)
}

internal val TEXT_EXTENSIONS =
    setOf(
        "txt", "md", "conf", "cfg", "ini", "toml", "yml", "yaml", "json", "jsonc", "xml", "sh", "bash", "zsh", "fish",
        "py", "js", "ts", "kt", "kts", "java", "c", "h", "cpp", "rs", "go", "lua", "rb", "pl", "css", "html", "desktop",
        "log", "csv", "service", "rules", "list", "gitignore", "patch", "diff", "vim", "el", "owl",
    )

internal fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

internal fun mimeOf(name: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(name))

internal fun isImage(name: String) = mimeOf(name)?.startsWith("image/") == true

/** Text by extension, or small extension-less files without NUL bytes (configs, scripts, dotfiles). */
internal fun isText(
    name: String,
    file: File,
): Boolean {
    val ext = extension(name)
    if (ext in TEXT_EXTENSIONS || mimeOf(name)?.startsWith("text/") == true) return true
    if (ext.isNotEmpty() && !name.startsWith('.')) return false
    if (file.length() > 1 shl 20) return false
    return runCatching { file.inputStream().use { s -> ByteArray(4096).let { b -> val n = s.read(b); n <= 0 || (0 until n).none { b[it] == 0.toByte() } } } }
        .getOrDefault(false)
}

internal fun formatDate(
    context: Context,
    millis: Long,
): String {
    val sameYear = DateFormat.format("yyyy", millis) == DateFormat.format("yyyy", System.currentTimeMillis())
    val date = DateFormat.format(if (sameYear) "MMM d" else "MMM d, yyyy", millis)
    return "$date, ${DateFormat.getTimeFormat(context).format(millis)}"
}
