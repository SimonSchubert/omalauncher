package dev.omalauncher.widgets

import android.content.SharedPreferences

/** SharedPreferences in memory, for JVM tests of classes that persist through it. */
class FakePrefs : SharedPreferences {
    val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)

    override fun getString(key: String?, defValue: String?) = values[key] as String? ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = values[key] as MutableSet<String>? ?: defValues

    override fun getInt(key: String?, defValue: Int) = values[key] as Int? ?: defValue

    override fun getLong(key: String?, defValue: Long) = values[key] as Long? ?: defValue

    override fun getFloat(key: String?, defValue: Float) = values[key] as Float? ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as Boolean? ?: defValue

    override fun contains(key: String?) = key in values

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    /** Like Android's: changes keyed by name, so the last put or remove of a key wins; clear() goes first. */
    private inner class Editor : SharedPreferences.Editor {
        private val changes = HashMap<String, Any?>()
        private var clearAll = false

        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            changes[key] = value
            return this
        }

        override fun putString(key: String, value: String?) = put(key, value)

        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)

        override fun putInt(key: String, value: Int) = put(key, value)

        override fun putLong(key: String, value: Long) = put(key, value)

        override fun putFloat(key: String, value: Float) = put(key, value)

        override fun putBoolean(key: String, value: Boolean) = put(key, value)

        // A null value removes, as in Android, where putString(key, null) does too.
        override fun remove(key: String) = put(key, null)

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) values.clear()
            for ((k, v) in changes) if (v == null) values.remove(k) else values[k] = v
        }
    }
}
