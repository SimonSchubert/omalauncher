package dev.omalauncher.net

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.BuildConfig
import dev.omalauncher.widgets.Market
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * Messages from the developer, like a contest: announcements.json next to the store's catalog on GitHub Pages, so one
 * is turned on or off by a commit there. Asked when settings opens, at most every few hours; nothing is sent but the
 * request. A message the user closed stays closed, and "don't show news again" stops the asking too.
 *
 * Old launchers read new files: fields they don't know are skipped, as is a button with an action they don't know. A
 * message that needs a newer launcher to make sense says so with minVersionCode.
 */
class Announcements(
    private val context: Context,
) {
    /** One message, its texts already in the phone's language. */
    data class Message(
        val id: String,
        val title: String,
        val body: String,
        val buttons: List<Button>,
        /** What it counts down to, shown big: a contest's deadline. */
        val countdown: Countdown? = null,
    )

    data class Countdown(
        val to: Instant,
        val label: String,
    )

    data class Button(
        val label: String,
        val url: String,
    )

    private val scope = MainScope()
    private val prefs = context.getSharedPreferences("announcements", Context.MODE_PRIVATE)
    private var checkedAt = 0L
    private var checking = false

    /** The file as last loaded; read again for each [pick], in the language and at the time then. */
    private var json: String? = null

    /** The message to show: the first one not closed. */
    var current by mutableStateOf<Message?>(null)
        private set

    /** Loads the file unless it was loaded within [CHECK_EVERY] or messages are off. Never throws. */
    fun check() {
        if (off() || checking) return
        val test = BuildConfig.DEBUG && testFile().isFile
        if (!test && System.currentTimeMillis() - checkedAt < CHECK_EVERY) {
            pick()
            return
        }
        checking = true
        scope.launch {
            try {
                json = withContext(Dispatchers.IO) { if (test) testFile().readText() else String(httpGet(URL), Charsets.UTF_8) }
                checkedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                // Offline, or GitHub Pages down: what was loaded before, if anything, stays; the next settings visit asks again.
                Log.w(TAG, "loading announcements failed", e)
            } finally {
                checking = false
            }
            pick()
        }
    }

    /** Shown once: closed any way, it doesn't come back. */
    fun dismiss(message: Message) {
        prefs.edit().putStringSet(DISMISSED, dismissed() + message.id).apply()
        pick()
    }

    /** "Don't show news again": no message shows from now on, this one included. */
    fun turnOff() {
        prefs.edit().putBoolean(OFF, true).apply()
        current = null
    }

    private fun off() = prefs.getBoolean(OFF, false)

    private fun dismissed() = prefs.getStringSet(DISMISSED, emptySet()).orEmpty()

    private fun pick() {
        val text = json
        if (off() || text == null) {
            current = null
            return
        }
        val closed = dismissed()
        current = parse(text, BuildConfig.VERSION_CODE, BuildConfig.FLAVOR, Instant.now(), Locale.getDefault()).firstOrNull { it.id !in closed }
    }

    /** A debug build reads files/announcements.json instead, each time, when there is one, to try messages out. */
    private fun testFile() = File(context.filesDir, "announcements.json")

    internal companion object {
        const val TAG = "Announcements"
        const val URL = Market.BASE + "announcements.json"
        const val CHECK_EVERY = 3 * 60 * 60_000L
        private const val DISMISSED = "dismissed"
        private const val OFF = "off"

        /**
         * The messages in [json] meant for this install now: active, within their dates, for this version and flavor,
         * their texts in [language] or English. Unknown fields are skipped, and so is a message missing what it needs.
         */
        fun parse(
            json: String,
            versionCode: Int,
            flavor: String,
            now: Instant,
            locale: Locale,
        ): List<Message> {
            val messages = runCatching { JSONObject(json).optJSONArray("messages") }.getOrNull() ?: return emptyList()
            // Indonesian is "id" in the file; Java's older code for it is "in", which Android's Locale.language still gives.
            val language = locale.toLanguageTag().substringBefore('-').let { if (it == "in") "id" else it }
            return (0 until messages.length()).mapNotNull { i ->
                // One message the app can't read (a date without its Z, say) leaves the others standing.
                runCatching { message(messages.optJSONObject(i) ?: return@mapNotNull null, versionCode, flavor, now, language) }
                    .onFailure { Log.w(TAG, "skipping message $i", it) }
                    .getOrNull()
            }
        }

        private fun message(
            m: JSONObject,
            versionCode: Int,
            flavor: String,
            now: Instant,
            language: String,
        ): Message? {
            val id = m.optString("id").takeIf { it.isNotEmpty() } ?: return null
            if (!m.optBoolean("active", true)) return null
            m.optString("start").takeIf { it.isNotEmpty() }?.let { if (now.isBefore(Instant.parse(it))) return null }
            m.optString("end").takeIf { it.isNotEmpty() }?.let { if (now.isAfter(Instant.parse(it))) return null }
            if (versionCode < m.optInt("minVersionCode", 0)) return null
            if (versionCode > m.optInt("maxVersionCode", Int.MAX_VALUE)) return null
            m.optJSONArray("flavors")?.let { f -> if ((0 until f.length()).none { f.optString(it) == flavor }) return null }
            fun text(o: JSONObject?) = o?.let { it.optString(language).ifEmpty { it.optString("en") } }.orEmpty().trim()
            val title = text(m.optJSONObject("title")).ifEmpty { return null }
            val buttons =
                m.optJSONArray("buttons")?.let { b ->
                    (0 until b.length()).mapNotNull { j ->
                        val o = b.optJSONObject(j) ?: return@mapNotNull null
                        val url = o.optString("url").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                        if (o.optString("action", "url") != "url") return@mapNotNull null
                        Button(text(o.optJSONObject("label")).ifEmpty { return@mapNotNull null }, url)
                    }
                }.orEmpty().take(3)
            // "countdown": { "to": "…", "label": { … } }, "to" being the message's end unless it says otherwise.
            val countdown =
                m.optJSONObject("countdown")?.let { c ->
                    val to = c.optString("to").ifEmpty { m.optString("end") }.takeIf { it.isNotEmpty() } ?: return@let null
                    Countdown(Instant.parse(to), text(c.optJSONObject("label")))
                }
            return Message(id, title, text(m.optJSONObject("body")), buttons, countdown)
        }
    }
}
