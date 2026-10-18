package dev.omalauncher.widgets.runtime

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Notification access, which the user grants in Android's settings. Android
 * requires it before an app may see other apps' media sessions (android.media),
 * and it lets widgets list notifications (android.notifications). Notifications
 * are only read when a widget asks; see [Notifications].
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = this
        changes.update { it + 1 }
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
        changes.update { it + 1 }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        changes.update { it + 1 }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        changes.update { it + 1 }
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    companion object {
        /** The listener Android has bound, while it's bound. */
        @Volatile
        var connected: MediaListener? = null
            private set

        /** Bumped whenever the notifications or the connection change. */
        val changes = MutableStateFlow(0)
    }
}

/** What's playing on the phone, for `source m = android.media`, and play/pause/skip for `media(...)`. */
class Media(
    private val context: Context,
) {
    private val component = ComponentName(context, MediaListener::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    val granted get() = notifications.isNotificationListenerAccessGranted(component)

    /** Asks Android to bind the listener again: access is granted, but it isn't bound yet after a start, or the phone let it go. */
    fun reconnect() {
        runCatching { NotificationListenerService.requestRebind(component) }.onFailure { Log.w("widgets", "rebind: ${it.message}") }
    }

    /**
     * Where [value] runs: its binder calls and the art's parcel stay off the main thread, one at a time,
     * since it keeps the art and labels below.
     */
    private val worker = Dispatchers.Default.limitedParallelism(1)

    /** Album art by the id widgets see in `art`; only the current track's is kept. Read by the image loader. */
    @Volatile
    private var art: Pair<String, Bitmap>? = null

    /** What [art] was taken for: the app, the track and the art's size. */
    private var artTrack: List<Any?>? = null
    private var artSerial = 0

    fun art(src: String): Bitmap? = art?.takeIf { it.first == src }?.second

    /** The app [value] shows, for Android's output switcher to move its sound; null without one. */
    fun playingPackage(): String? = controller()?.packageName

    /** The session widgets were last shown, which media(...) controls while it's still active. */
    @Volatile
    private var shown: MediaSession.Token? = null

    /** The session to show and control: [prefer] while it's active, else the one playing, else the most recent. */
    private fun controller(prefer: MediaSession.Token? = null): MediaController? {
        if (!granted) return null
        val all = runCatching { sessions.getActiveSessions(component) }.getOrNull().orEmpty()
        return prefer?.let { t -> all.firstOrNull { it.sessionToken == t } }
            ?: all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull()
    }

    fun value(): Value {
        val c = controller()
        shown = c?.sessionToken
        if (c == null) return Obj(mapOf("available" to bool(granted), "playing" to bool(false)))
        val m = c.metadata
        val s = c.playbackState
        val playing = s?.state == PlaybackState.STATE_PLAYING
        val position =
            s?.let {
                val drift = if (playing && it.lastPositionUpdateTime > 0) (SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed else 0f
                ((it.position + drift) / 1000.0).coerceAtLeast(0.0)
            }
        val duration = m?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }?.let { it / 1000.0 }
        val bitmap =
            m?.let {
                it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            }
        fun text(key: String) = m?.getString(key)?.takeIf { it.isNotBlank() }?.let(::Str) ?: Null
        // Every c.metadata is a fresh parcel with a fresh Bitmap, and this runs every second while playing:
        // keep the art for as long as the track (and the art's size) stays the same, so its id doesn't change
        // each tick and the widget doesn't swap, and briefly lose, the image.
        val artKey =
            bitmap?.let { b ->
                val track = listOf(MediaMetadata.METADATA_KEY_MEDIA_ID, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM).map { m.getString(it) }
                listOf(c.packageName, track, b.width, b.height)
            }
        if (artKey != artTrack) {
            artTrack = artKey
            art = bitmap?.let { "media:art:${++artSerial}" to it }
        }
        val artSrc = art?.first
        val app =
            labels.getOrPut(c.packageName) {
                runCatching {
                    val pm = context.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
                }.getOrDefault(c.packageName)
            }
        return Obj(
            mapOf(
                "available" to bool(true),
                "playing" to bool(playing),
                "title" to (text(MediaMetadata.METADATA_KEY_TITLE).takeIf { it != Null } ?: text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)),
                "artist" to (text(MediaMetadata.METADATA_KEY_ARTIST).takeIf { it != Null } ?: text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)),
                "album" to text(MediaMetadata.METADATA_KEY_ALBUM),
                "app" to Str(app),
                "package" to Str(c.packageName),
                "position" to (position?.let { Num(Math.floor(it)) } ?: Null),
                "duration" to (duration?.let { Num(Math.floor(it)) } ?: Null),
                "art" to (artSrc?.let(::Str) ?: Null),
            ),
        )
    }

    /**
     * Emits whenever the session, track or playback state changes, and every
     * second while playing so `position` moves. Picks up access granted later.
     */
    fun updates(): Flow<Value> =
        callbackFlow {
            var current: MediaController? = null
            val callback =
                object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        trySend(Unit)
                    }

                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        trySend(Unit)
                    }

                    // The ticker below rebinds to whichever session is current.
                    override fun onSessionDestroyed() {
                        trySend(Unit)
                    }
                }

            fun rebind(next: MediaController?) {
                if (next?.sessionToken == current?.sessionToken) return
                current?.unregisterCallback(callback)
                current = next
                next?.registerCallback(callback, main)
            }
            // On the main thread: rebound on the worker, with the ticker, which uses current too.
            val changed =
                MediaSessionManager.OnActiveSessionsChangedListener {
                    launch {
                        rebind(controller())
                        trySend(Unit)
                    }
                }
            var listening = false

            fun listen() {
                if (listening || !granted) return
                listening = runCatching { sessions.addOnActiveSessionsChangedListener(changed, component, main) }.isSuccess
                rebind(controller())
            }
            listen()
            trySend(Unit)
            val ticker =
                launch {
                    var wasGranted = granted
                    while (true) {
                        // Every second while playing, for the position; otherwise only to notice access being granted
                        // or a session that started playing without the active list changing order.
                        delay(if (current?.playbackState?.state == PlaybackState.STATE_PLAYING) 1000 else 5000)
                        val now = granted
                        if (now != wasGranted) {
                            wasGranted = now
                            listen()
                            trySend(Unit)
                        }
                        // Another session may have started playing without the active list changing order.
                        if (listening) rebind(controller())
                        if (current?.playbackState?.state == PlaybackState.STATE_PLAYING) trySend(Unit)
                    }
                }
            awaitClose {
                ticker.cancel()
                current?.unregisterCallback(callback)
                if (listening) sessions.removeOnActiveSessionsChangedListener(changed)
            }
        }
            // Listing the sessions and asking for their state go through the system, every second while playing: on the worker, not the main thread.
            .flowOn(worker)
            .conflate()
            .map { withContext(worker) { value() } }
            .distinctUntilChanged()

    /** media("toggle"), "play", "pause", "next", "previous"; without access, opens the setting that grants it. */
    fun control(command: String) {
        if (!granted) {
            openAccessSettings()
            return
        }
        // The session the tile shows, even if another one started playing since it last updated.
        val c = controller(shown) ?: return
        val t = c.transportControls
        val s = c.playbackState
        // A session left in an error state, or one that offers no play action, ignores play(): YouTube without
        // Premium keeps its session around with "Required premium account to perform action in background".
        // Open the app instead, where playing works, rather than a button that does nothing.
        val canPlay = s?.state != PlaybackState.STATE_ERROR && (s == null || s.actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE) != 0L)
        when (command) {
            "play" -> if (canPlay) play(c) else open(c)
            "pause" -> t.pause()
            "toggle" -> if (s?.state == PlaybackState.STATE_PLAYING) t.pause() else if (canPlay) play(c) else open(c)
            "next" -> t.skipToNext()
            "previous" -> t.skipToPrevious()
            "open" -> open(c)
        }
    }

    // An app paused for a while gets frozen by Android, and the play command waits until something thaws it:
    // the button seems dead (Spotify after a long pause). If it hasn't started playing or loading shortly
    // after, open the app, which thaws it so the waiting play goes through.
    private fun play(c: MediaController) {
        c.transportControls.play()
        main.postDelayed({
            if (c.playbackState?.state !in starting) open(c)
        }, 2000)
    }

    private val starting = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING, PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM)

    // The player's own screen first: the session's activity, else what tapping its media notification opens.
    // A browser's session belongs to the browser, so its launch intent opens a tab, while these bring up the
    // window that plays, a web app's included. The app's launch screen only when neither is there or works.
    private fun open(c: MediaController) {
        if (send(c.sessionActivity) || send(notificationIntent(c))) return
        context.packageManager.getLaunchIntentForPackage(c.packageName)?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    // send() throws CanceledException, which isn't a RuntimeException: the tap handler wouldn't catch it.
    // A canceled intent means the next one gets a go.
    private fun send(intent: PendingIntent?) = intent != null && runCatching { intent.send(context, 0, null, null, null, null, allowLaunch()) }.isSuccess

    /** The tap action of the media notification [c]'s session posted, matched by its session token. */
    private fun notificationIntent(c: MediaController): PendingIntent? {
        val posted = MediaListener.connected?.let { runCatching { it.activeNotifications }.getOrNull() } ?: return null
        return posted.firstOrNull { sessionOf(it.notification) == c.sessionToken }?.notification?.contentIntent
    }

    // Another app's extras can fail to unparcel; such a notification just doesn't match.
    private fun sessionOf(n: Notification): MediaSession.Token? =
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                n.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
            }
        }.getOrNull()

    // Since Android 14 a PendingIntent only starts an activity from a backgrounded app (the player is cached
    // by now) when the sender lends it its own right to: the launcher, on screen, has to opt in or it's blocked.
    private fun allowLaunch(): Bundle? {
        if (Build.VERSION.SDK_INT < 34) return null
        val mode = if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
    }

    fun openAccessSettings() {
        val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT < 30) return context.startActivity(list)
        // Straight to this app's switch where the phone supports it; some skins only have the list.
        val detail =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(detail) }.onFailure { context.startActivity(list) }
    }

    /** App labels by package: the lookup is a binder call, and value() runs every second while playing. */
    private val labels = HashMap<String, String>()
}
