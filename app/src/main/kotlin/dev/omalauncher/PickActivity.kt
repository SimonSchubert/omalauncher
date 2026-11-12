package dev.omalauncher

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred

/**
 * Android's file picker for `omalauncher pick`: any file on the phone, Download's included, which no folder grant
 * reaches. Its own task, out of Recents; shows nothing itself, and hands what was picked (nothing, if backed out of)
 * to [picked].
 */
class PickActivity : ComponentActivity() {
    private val pick =
        registerForActivityResult(
            object : ActivityResultContracts.OpenMultipleDocuments() {
                override fun createIntent(
                    context: Context,
                    input: Array<String>,
                ) = super.createIntent(context, input).showingStorage()
            },
        ) { uris ->
            answer(uris)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Brought back after the process was gone: nothing's waiting for the files.
        if (savedInstanceState != null && waiting == null) return finish()
        if (savedInstanceState == null) {
            runCatching { pick.launch(arrayOf("*/*")) }.onFailure {
                answer(emptyList())
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) answer(emptyList())
    }

    companion object {
        @Volatile private var waiting: CompletableDeferred<List<Uri>>? = null

        /** What the picker gives the request that opened it; a later one takes its place, the earlier gets nothing. */
        fun picked(): CompletableDeferred<List<Uri>> = CompletableDeferred<List<Uri>>().also { waiting?.complete(emptyList()); waiting = it }

        private fun answer(uris: List<Uri>) {
            waiting?.complete(uris)
            waiting = null
        }
    }
}
