package dev.omalauncher.linux

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Settings live outside the rootfs, so a failed install can always be repaired from the UI. */
class AndroidIntegration(context: Context) {
    private val prefs = context.getSharedPreferences("linux-android", Context.MODE_PRIVATE)
    var andoEnabled by mutableStateOf(prefs.getBoolean("ando", false))
        private set
    var binds by mutableStateOf<List<StorageBind>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        try {
            val saved = prefs.getString("binds", null)
            if (saved != null) binds = decode(saved)
        } catch (e: Exception) {
            error = "Cannot read storage binds; remove the invalid configuration in settings › linux: ${e.message}"
        }
    }

    fun setAndo(enabled: Boolean) {
        check(prefs.edit().putBoolean("ando", enabled).commit()) { "Cannot save Android command setting" }
        andoEnabled = enabled
    }

    fun updateBinds(value: List<StorageBind>) {
        StorageBinds.validate(value)
        check(prefs.edit().putString("binds", encode(value)).commit()) { "Cannot save storage binds" }
        binds = value.toList()
        error = null
    }

    fun checkedBinds(): List<StorageBind> {
        check(error == null) { error.orEmpty() }
        return binds
    }

    companion object {
        fun encode(binds: List<StorageBind>): String = JSONObject().put("v", 1).put(
            "data", JSONArray().apply {
                binds.forEach { put(JSONObject().put("host", it.hostPath).put("guest", it.guestPath)) }
            },
        ).toString()

        fun decode(text: String): List<StorageBind> {
            val doc = JSONObject(text)
            require(doc.getInt("v") == 1) { "Unsupported storage bind format" }
            val entries = doc.getJSONArray("data")
            return (0 until entries.length()).map { i ->
                val entry = entries.getJSONObject(i)
                StorageBind(entry.getString("host"), entry.getString("guest"))
            }.also(StorageBinds::validate)
        }
    }
}
