package dev.omalauncher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.compose.runtime.LaunchedEffect
import dev.omalauncher.ui.FilePick
import dev.omalauncher.ui.FilesScreen
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.applySystemBars
import dev.omalauncher.linux.guestReplyFile

/**
 * A Linux app's file dialog, as the Files app in pick mode: asked for by the
 * file chooser portal through the open bridge. It answers the portal's reply
 * FIFO once, with the picked guest paths, one per line, or an empty line
 * when cancelled (back, close, or swiped away).
 */
class FilePickActivity : InstantActivity() {
    private var reply: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reply = intent.getStringExtra(EXTRA_REPLY)
        val pick = intent.getStringArrayListExtra(EXTRA_REQUEST)?.let(FilePick::parse)
        if (pick == null || !runtime.bootstrap.wanted) {
            answer(emptyList())
            finish()
            return
        }
        applySystemBars(this, Palette.isLight)
        setInstantContent {
            LaunchedEffect(Palette.isLight) { applySystemBars(this@FilePickActivity, Palette.isLight) }
            FilesScreen(runtime, pick.second, onExit = { finishWith(emptyList()) }, pick = pick.first, onPicked = ::finishWith)
        }
    }

    override fun onDestroy() {
        if (isFinishing) answer(emptyList())
        super.onDestroy()
    }

    private fun finishWith(paths: List<String>) {
        answer(paths)
        closeInstantly()
    }

    /** Non-blocking, and only once: an app that gave up on the dialog left no reader, and the open just fails. */
    private fun answer(paths: List<String>) {
        val guestPath = reply ?: return
        reply = null

        val fifo = guestReplyFile(runtime.proot.guestFs, guestPath) ?: return
        val text = (paths.joinToString("\n") + "\n").toByteArray()
        try {
            val fd = Os.open(fifo.path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW, 0)
            try {
                // A guest-controlled regular file is not a reply channel, even if its path is inside the guest.
                if (!OsConstants.S_ISFIFO(Os.fstat(fd).st_mode)) return
                Os.write(fd, text, 0, text.size)
            } finally {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            // Nobody's waiting any more.
        }
    }

    companion object {
        private const val EXTRA_REPLY = "reply"
        private const val EXTRA_REQUEST = "request"

        /** [request] is [FilePick.parse]'s fields; [reply] the reply FIFO's guest path. */
        fun intent(
            context: Context,
            request: List<String>,
            reply: String,
        ): Intent =
            Intent(context, FilePickActivity::class.java)
                .putStringArrayListExtra(EXTRA_REQUEST, ArrayList(request))
                .putExtra(EXTRA_REPLY, reply)
    }
}
