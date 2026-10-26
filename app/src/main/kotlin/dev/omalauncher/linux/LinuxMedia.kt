package dev.omalauncher.linux

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import dev.omalauncher.widgets.lang.Bool
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A Linux player's Android media session, published by an OWL app. */
class LinuxMedia(
    context: Context,
    private val proot: Proot,
) {
    data class Published(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val positionMs: Long,
        val playing: Boolean,
        val commands: Map<String, String>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = MediaSession(context, "Linux media")
    private val mutable = MutableStateFlow<Published?>(null)
    val state = mutable.asStateFlow()
    val token: MediaSession.Token get() = session.sessionToken

    init {
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() = control("play")
                override fun onPause() = control("pause")
                override fun onStop() = control("stop")
                override fun onSkipToNext() = control("next")
                override fun onSkipToPrevious() = control("previous")
                override fun onSeekTo(pos: Long) = control("seek", pos.coerceAtLeast(0) / 1000.0)
            },
        )
    }

    /** Null removes the session; a map replaces its complete state and allowed controls. */
    fun publish(value: Value) {
        if (value == Null) {
            mutable.value = null
            session.isActive = false
            return
        }
        val fields = (value as? Obj)?.fields ?: return
        fun text(name: String) = (fields[name] as? Str)?.v.orEmpty()
        fun seconds(name: String) = ((fields[name] as? Num)?.v ?: 0.0).coerceAtLeast(0.0)
        val commands = (fields["commands"] as? Obj)?.fields.orEmpty().mapNotNull { (name, command) ->
            (command as? Str)?.v?.takeIf { it.isNotBlank() }?.let { name to it }
        }.toMap()
        val item =
            Published(
                title = text("title"),
                artist = text("artist"),
                album = text("album"),
                durationMs = (seconds("duration") * 1000).toLong(),
                positionMs = (seconds("position") * 1000).toLong(),
                playing = (fields["playing"] as? Bool)?.v == true,
                commands = commands,
            )
        mutable.value = item
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, item.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, item.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, item.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, item.durationMs)
                .build(),
        )
        updatePlayback(item)
        session.isActive = true
    }

    fun control(name: String) = control(name, null)

    private fun control(name: String, argument: Double?) {
        val item = mutable.value ?: return
        val base = item.commands[name] ?: return
        // Seek's number is generated here, never copied from the command, and is therefore one safe shell word.
        val command = if (argument == null) base else "$base ${argument.toString()}"
        val optimistic =
            when (name) {
                "play" -> item.copy(playing = true)
                "pause" -> item.copy(playing = false)
                "stop" -> item.copy(playing = false, positionMs = 0)
                "seek" -> item.copy(positionMs = ((argument ?: 0.0) * 1000).toLong())
                else -> item
            }
        mutable.value = optimistic
        updatePlayback(optimistic)
        scope.launch { runCatching { proot.run(command) } }
    }

    private fun updatePlayback(item: Published) {
        var actions = 0L
        if ("play" in item.commands) actions = actions or PlaybackState.ACTION_PLAY
        if ("pause" in item.commands) actions = actions or PlaybackState.ACTION_PAUSE
        if ("stop" in item.commands) actions = actions or PlaybackState.ACTION_STOP
        if ("next" in item.commands) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if ("previous" in item.commands) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if ("seek" in item.commands) actions = actions or PlaybackState.ACTION_SEEK_TO
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(if (item.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, item.positionMs, if (item.playing) 1f else 0f)
                .build(),
        )
    }

    fun close() {
        mutable.value = null
        session.release()
        scope.cancel()
    }
}
