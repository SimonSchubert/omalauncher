package android.os

class PersistableBundle {
    private val values = mutableMapOf<String, String>()
    fun putString(key: String, value: String) { values[key] = value }
    fun getString(key: String): String? = values[key]
}
