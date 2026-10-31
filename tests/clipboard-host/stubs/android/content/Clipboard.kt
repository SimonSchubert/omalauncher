package android.content

import android.os.PersistableBundle
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.InputStream

open class Context {
    val clipboard = ClipboardManager()
    val contentResolver = ContentResolver()
    fun <T> getSystemService(type: Class<T>): T = type.cast(clipboard)
}

class ClipDescription(var timestamp: Long) {
    var extras: PersistableBundle? = null
}

class ClipData(private val item: Item) {
    val description = ClipDescription(1)
    val itemCount = 1
    fun getItemAt(index: Int) = item
    class Item(private val value: CharSequence? = null, val uri: Uri? = null) {
        fun coerceToText(context: Context): CharSequence? = value ?: uri?.toString()
    }
    companion object {
        fun newPlainText(label: String, value: CharSequence) = ClipData(Item(value))
    }
}

class ClipboardManager {
    var clip: ClipData? = null
    var afterWrite: (() -> Unit)? = null
    val primaryClip: ClipData? get() = clip
    val primaryClipDescription: ClipDescription? get() = clip?.description
    private val listeners = mutableListOf<() -> Unit>()
    fun setPrimaryClip(clip: ClipData) {
        this.clip = clip
        afterWrite?.invoke()
        listeners.forEach { it() }
    }
    fun addPrimaryClipChangedListener(listener: () -> Unit) { listeners += listener }
}

class ContentResolver {
    val bytes = mutableMapOf<Uri, ByteArray>()
    val types = mutableMapOf<Uri, String>()
    var afterTypeRead: (() -> Unit)? = null
    var opens = 0
    fun getType(uri: Uri): String? {
        val type = types[uri]
        afterTypeRead?.invoke()
        return type
    }
    fun openInputStream(uri: Uri): InputStream? {
        opens++
        return bytes[uri]?.let(::ByteArrayInputStream)
    }
}
