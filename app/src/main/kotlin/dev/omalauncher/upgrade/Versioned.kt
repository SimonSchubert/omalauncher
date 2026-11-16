package dev.omalauncher.upgrade

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * Persisted JSON carries its schema version: `{"v": N, "data": …}`. Readers
 * upgrade older documents step by step through [read]'s `migrate`, so a
 * format change in a new APK never discards what users saved.
 */
object Versioned {
    fun write(
        version: Int,
        data: Any,
    ): String = JSONObject().put("v", version).put("data", data).toString()

    /**
     * The document's data at [current], or null if there is none or it can't
     * be read. `migrate(from, data)` turns version `from` into `from + 1`.
     * A document without the wrapper is version 0, what launchers before
     * versioning wrote. A document from a newer app (after a downgrade) is read as is.
     */
    fun read(
        text: String?,
        current: Int,
        migrate: (from: Int, data: Any) -> Any = { from, _ -> error("no migration from v$from") },
    ): Any? {
        if (text.isNullOrBlank()) return null
        return try {
            val root = JSONTokener(text).nextValue()
            val wrapped = root is JSONObject && root.has("v") && root.has("data")
            var data = if (wrapped) (root as JSONObject).get("data") else root
            var version = if (wrapped) (root as JSONObject).getInt("v") else 0
            while (version < current) data = migrate(version++, data)
            data
        } catch (e: Exception) {
            Log.w("versioned", "unreadable document", e)
            null
        }
    }

    /** [file]'s document, an object, as [read] gives it: null while there's no file. */
    fun readFile(
        file: File,
        current: Int,
    ): JSONObject? = read(file.takeIf { it.exists() }?.readText(), current) as? JSONObject

    fun writeFile(
        file: File,
        version: Int,
        data: Any,
    ) = writeAtomically(file, write(version, data).toByteArray())
}

/** Each object of [array], skipping entries that don't parse, so one bad entry can't take the rest with it. */
fun <T : Any> JSONArray.mapObjects(parse: (JSONObject) -> T?): List<T> =
    (0 until length()).mapNotNull { i -> runCatching { optJSONObject(i)?.let(parse) }.getOrNull() }

fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

/** Each entry of [obj] by key, skipping entries that don't parse. */
fun <T : Any> JSONObject.mapEntries(parse: (String, JSONObject) -> T?): Map<String, T> =
    keys().asSequence().mapNotNull { k -> runCatching { optJSONObject(k)?.let { parse(k, it) } }.getOrNull()?.let { k to it } }.toMap()
