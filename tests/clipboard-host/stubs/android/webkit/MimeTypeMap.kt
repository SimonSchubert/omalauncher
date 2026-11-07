package android.webkit

class MimeTypeMap {
    fun getExtensionFromMimeType(mime: String): String? = when (mime) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        else -> null
    }
    companion object {
        fun getSingleton() = MimeTypeMap()
    }
}
