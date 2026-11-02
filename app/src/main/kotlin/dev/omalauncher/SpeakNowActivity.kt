package dev.omalauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.omalauncher.ui.Speech

/**
 * Opens the phone's own "speak now" screen for [Speech], when its speech app has no recognizer other apps can use
 * (FUTO Voice Input), and hands what it heard back. Its own task, out of Recents, shows nothing itself.
 */
class SpeakNowActivity : ComponentActivity() {
    private val speakNow =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val heard =
                result.data
                    ?.takeIf { result.resultCode == Activity.RESULT_OK }
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
            Speech.spoken(heard)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Brought back after the process was gone: nothing's waiting for the words.
        if (savedInstanceState != null) return
        val ask =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .apply { intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) } }
        runCatching { speakNow.launch(ask) }.onFailure {
            Speech.spoken(null)
            finish()
        }
    }
}
