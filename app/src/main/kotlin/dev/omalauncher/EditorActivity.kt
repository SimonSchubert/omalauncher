package dev.omalauncher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.compose.ui.graphics.toArgb
import dev.omalauncher.linux.shellQuote
import dev.omalauncher.ui.EditorHome
import dev.omalauncher.ui.EditorScreen
import dev.omalauncher.ui.Palette

/**
 * The text editor: one Recents card per file (documentLaunchMode), opened from
 * Files, the drawer, or `$EDITOR` in Linux. Opened by `omarchy-edit`, it holds
 * the shim's reply FIFOs and answers once it closes, which is what makes
 * `git commit` wait for the message, as with a terminal editor.
 */
class EditorActivity : InstantActivity() {

    /** Host paths of the `omarchy-edit` FIFOs waiting for this file to close. */
    private val waiting = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.data?.path?.takeIf { it.startsWith('/') }
        intent.getStringExtra(EXTRA_DONE)?.let(waiting::add)
        if (!runtime.bootstrap.wanted) return close()
        val name = path?.substringAfterLast('/') ?: getString(R.string.editor_title)
        setTaskDescription(ActivityManager.TaskDescription(name, null, Palette.background.toArgb()))
        setInstantContent {
            if (path == null) {
                EditorHome(runtime, onExit = ::close)
            } else {
                EditorScreen(runtime, path, onFallback = { fallBack(path) }, onExit = ::close)
            }
        }
    }

    /** The same file again, from another `omarchy-edit`: it waits for this one to close too. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_DONE)?.let(waiting::add)
    }

    override fun onDestroy() {
        // Swiped away from Recents: whoever waits gets the file as it is.
        if (isFinishing) answer("ok")
        super.onDestroy()
    }

    private fun close() {
        answer("ok")
        closeInstantly()
    }

    /** A file the editor can't take: micro gets it, in the terminal that asked or a new one. */
    private fun fallBack(path: String) {
        if (waiting.isNotEmpty()) {
            answer("micro")
        } else {
            runtime.terminal("micro ${shellQuote(path)}", path.substringAfterLast('/'))
        }
        closeInstantly()
    }

    /**
     * Tells each waiting shim [reply]. Non-blocking: a shim that was
     * interrupted (Ctrl+C) left no reader, and the open just fails.
     */
    private fun answer(reply: String) {
        for (fifo in waiting) {
            try {
                val fd = Os.open(fifo, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
                try {
                    Os.write(fd, "$reply\n".toByteArray(), 0, reply.length + 1)
                } finally {
                    Os.close(fd)
                }
            } catch (e: ErrnoException) {
                // Nobody's waiting any more.
            }
        }
        waiting.clear()
    }

    companion object {
        private const val EXTRA_DONE = "done"

        /** Editing [path] (a guest path), or the editor's start page without one; [done] is a reply FIFO on the host. */
        fun intent(
            context: Context,
            path: String?,
            done: String? = null,
        ): Intent =
            Intent(context, EditorActivity::class.java)
                // The data names the document: one task per file.
                .setData(Uri.Builder().scheme("guest").path(path.orEmpty()).build())
                .putExtra(EXTRA_DONE, done)
    }
}
