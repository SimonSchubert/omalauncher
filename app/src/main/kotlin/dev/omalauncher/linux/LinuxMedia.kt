package dev.omalauncher.linux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import dev.omalauncher.MainActivity
import dev.omalauncher.R
import dev.omalauncher.registerNotExported
import dev.omalauncher.widgets.lang.Bool
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "linux-media"

/**
 * What a Linux player plays, in Android's media controls. Guest audio already plays as Android media (AudioBridge);
 * `omalauncher media set` adds the rest of what makes it a player to the phone: a media session and its notification, so
 * the lock screen, the notification shade's player, a car's display and steering-wheel buttons over Bluetooth,
 * headset and AirPods buttons and a watch show what plays and steer it. Their presses run the player's `command` in
 * the guest with the action after it: `play`, `pause`, `next`, `previous`, `stop`, or `seek` and the second to go to.
 *
 * A set with a `title` describes a new track and replaces what was set; one without changes only the fields it has,
 * as a player says it paused or went elsewhere in the track: `{"playing": false, "position": 63}`.
 */
class LinuxMedia(
    private val context: Context,
    private val proot: Proot,
    /** Main: [set], [clear] and the session's callbacks run on it. */
    private val scope: CoroutineScope,
) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private var session: MediaSession? = null
    private var receiver: BroadcastReceiver? = null

    /** What was set, with the position as of [setAt] (elapsedRealtime). */
    private var now: Obj? = null
    private var setAt = 0L
    private var art: Bitmap? = null
    private var artSrc: String? = null
    private var artJob: Job? = null

    /** What's set, as `omalauncher media` shows it, the position as of now. */
    fun current(): Value = now?.let { Obj(it.fields + ("position" to Num(position(it)))) } ?: Null

    /** Publishes [v] (merged into what's set unless it names a new track); the error to answer with, or null. */
    fun set(v: Obj): String? {
        val bad = v.fields.keys - FIELDS
        if (bad.isNotEmpty()) return "media set: unknown ${bad.joinToString(", ")}; one of ${FIELDS.joinToString(", ")}"
        val next = if (v.fields.containsKey("title") || now == null) v else Obj(now!!.fields + v.fields)
        if (next.str("title").isNullOrEmpty()) return "media set: a track needs a title"
        now = next
        setAt = SystemClock.elapsedRealtime()
        loadArt(next.str("art"))
        publish()
        return null
    }

    /** Takes the player away from Android's media controls. */
    fun clear() {
        now = null
        artJob?.cancel()
        art = null
        artSrc = null
        nm.cancel(NOTIFICATION)
        session?.run {
            isActive = false
            release()
        }
        session = null
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private fun publish() {
        val v = now ?: return
        val s = session()
        s.setMetadata(metadata(v))
        s.setPlaybackState(playback(v))
        s.isActive = true
        if (nm.areNotificationsEnabled()) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.linux_media_channel), NotificationManager.IMPORTANCE_LOW))
            nm.notify(NOTIFICATION, notification(v, s))
        }
    }

    private fun session(): MediaSession =
        session ?: MediaSession(context, TAG).also { s ->
            s.setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = press("play")

                    override fun onPause() = press("pause")

                    override fun onSkipToNext() = press("next")

                    override fun onSkipToPrevious() = press("previous")

                    override fun onStop() = press("stop")

                    override fun onSeekTo(pos: Long) = press("seek", pos / 1000)
                },
            )
            session = s
            // The notification's buttons, where Android draws them from it rather than from the session (before 13).
            receiver = context.registerNotExported(IntentFilter(ACTION)) { intent -> intent.getStringExtra(EXTRA)?.let { press(it) } }
        }

    /**
     * A press from Android: shown at once (playing or paused, where a seek went), then the player's command runs; the
     * player's next set says how it went.
     */
    private fun press(
        word: String,
        second: Long? = null,
    ) {
        val v = now ?: return
        if (word !in actions(v)) return
        val shown =
            when (word) {
                "play", "pause" -> mapOf("playing" to Bool(word == "play"), "position" to Num(position(v)))
                "seek" -> mapOf("position" to Num((second ?: 0L).toDouble()))
                else -> emptyMap()
            }
        if (shown.isNotEmpty()) {
            now = Obj(v.fields + shown)
            setAt = SystemClock.elapsedRealtime()
            publish()
        }
        val command = v.str("command") ?: return
        val line = command + " " + word + (second?.let { " $it" } ?: "")
        scope.launch(Dispatchers.IO) {
            val code = runCatching { proot.run(line) { Log.i(TAG, "[$word] $it") } }.getOrElse { -1 }
            if (code != 0) Log.w(TAG, "'$line' exited $code")
        }
    }

    /** The position now: as set, run on by the time since while it plays. */
    private fun position(v: Obj): Double {
        val at = v.num("position") ?: 0.0
        if (v.bool("playing") != true) return at
        val ran = at + (SystemClock.elapsedRealtime() - setAt) / 1000.0 * (v.num("speed") ?: 1.0)
        return v.num("duration")?.takeIf { it > 0 }?.let { minOf(ran, it) } ?: ran
    }

    private fun metadata(v: Obj): MediaMetadata =
        MediaMetadata
            .Builder()
            .apply {
                putString(MediaMetadata.METADATA_KEY_TITLE, v.str("title"))
                v.str("artist")?.let { putString(MediaMetadata.METADATA_KEY_ARTIST, it) }
                v.str("album")?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
                v.num("duration")?.takeIf { it > 0 }?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, (it * 1000).toLong()) }
                art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            }.build()

    private fun playback(v: Obj): PlaybackState {
        val playing = v.bool("playing") == true
        val acts = actions(v)
        var mask = 0L
        if ("play" in acts) mask = mask or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE
        if ("pause" in acts) mask = mask or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        if ("next" in acts) mask = mask or PlaybackState.ACTION_SKIP_TO_NEXT
        if ("previous" in acts) mask = mask or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if ("seek" in acts && (v.num("duration") ?: 0.0) > 0) mask = mask or PlaybackState.ACTION_SEEK_TO
        if ("stop" in acts) mask = mask or PlaybackState.ACTION_STOP
        return PlaybackState
            .Builder()
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                ((v.num("position") ?: 0.0) * 1000).toLong(),
                (v.num("speed") ?: 1.0).toFloat(),
                setAt,
            ).setActions(mask)
            .build()
    }

    private fun notification(
        v: Obj,
        s: MediaSession,
    ): Notification {
        val playing = v.bool("playing") == true
        val acts = actions(v)
        val buttons =
            listOfNotNull(
                "previous".takeIf { it in acts }?.let { button(it, android.R.drawable.ic_media_previous) },
                (if (playing) "pause" else "play").takeIf { it in acts }?.let { button(it, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play) },
                "next".takeIf { it in acts }?.let { button(it, android.R.drawable.ic_media_next) },
            )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification
            .Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(v.str("title"))
            .setContentText(listOfNotNull(v.str("artist"), v.str("album")).joinToString(" · "))
            .setLargeIcon(art)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .apply { buttons.forEach { addAction(it) } }
            .setStyle(
                Notification
                    .MediaStyle()
                    .setMediaSession(s.sessionToken)
                    .setShowActionsInCompactView(*buttons.indices.toList().toIntArray()),
            ).build()
    }

    private fun button(
        word: String,
        icon: Int,
    ): Notification.Action {
        val intent = Intent(ACTION).setPackage(context.packageName).putExtra(EXTRA, word)
        val pending = PendingIntent.getBroadcast(context, word.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(Icon.createWithResource(context, icon), word, pending).build()
    }

    /** The cover, from an https URL or a guest file, loaded once per track and shown when it's there. */
    private fun loadArt(src: String?) {
        if (src == artSrc) return
        artSrc = src
        art = null
        artJob?.cancel()
        if (src.isNullOrEmpty()) return
        artJob =
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { runCatching { readArt(src) }.onFailure { Log.w(TAG, "cover $src: ${it.message}") }.getOrNull() }
                if (bitmap != null && src == artSrc) {
                    art = bitmap
                    publish()
                }
            }
    }

    private fun readArt(src: String): Bitmap? {
        val bytes =
            if (src.startsWith("https://")) {
                (URL(src).openConnection() as HttpURLConnection).run {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    try {
                        if (contentLengthLong > MAX_ART_BYTES) return null
                        inputStream.use { it.readBytes() }
                    } finally {
                        disconnect()
                    }
                }
            } else {
                val path = if (src.startsWith("~/")) "/root/" + src.removePrefix("~/") else src
                if (!path.startsWith("/")) return null
                proot.hostFile(path).takeIf { it.length() <= MAX_ART_BYTES }?.readBytes() ?: return null
            }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ART_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        const val CHANNEL = "media"
        const val NOTIFICATION = 2
        const val ACTION = "dev.omalauncher.LINUX_MEDIA"
        const val EXTRA = "action"
        const val ART_PX = 512
        const val MAX_ART_BYTES = 8 shl 20
        val ACTIONS = listOf("play", "pause", "next", "previous", "seek", "stop")
        val DEFAULT_ACTIONS = listOf("play", "pause", "next", "previous", "seek")
        val FIELDS = setOf("title", "artist", "album", "art", "duration", "position", "playing", "speed", "command", "actions")

        /** What the player can do: `actions` as set, the usual ones otherwise; none without a command. */
        fun actions(v: Obj): List<String> {
            if (v.str("command").isNullOrEmpty()) return emptyList()
            val given = (v.fields["actions"] as? Lst)?.items?.mapNotNull { (it as? Str)?.v }
            return given?.filter { it in ACTIONS } ?: DEFAULT_ACTIONS
        }

        fun Obj.str(key: String) = (fields[key] as? Str)?.v

        fun Obj.num(key: String) = (fields[key] as? Num)?.v?.takeIf { it.isFinite() }

        fun Obj.bool(key: String) = (fields[key] as? Bool)?.v
    }
}
