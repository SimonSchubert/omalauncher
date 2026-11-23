package dev.omalauncher.voice

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.omalauncher.voice.ModelFiles.Part
import java.io.File

/**
 * Supertonic 3's files on the phone ([ModelFiles]), for [SherpaVoice] to read replies aloud with: downloaded when
 * asked for in the voice settings, from Hugging Face. Whether it reads is a setting of its own ([on]), so the phone's
 * text-to-speech can be picked again without downloading it again.
 */
internal object SupertonicModel {
    private const val HF = "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main"
    const val MODEL = "supertonic-3-tts-int8"

    private val PARTS =
        listOf(
            Part("$MODEL/duration_predictor.int8.onnx", "$HF/duration_predictor.int8.onnx", 3_700_147, "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"),
            Part("$MODEL/text_encoder.int8.onnx", "$HF/text_encoder.int8.onnx", 36_416_150, "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"),
            Part("$MODEL/vector_estimator.int8.onnx", "$HF/vector_estimator.int8.onnx", 78_400_833, "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd"),
            Part("$MODEL/vocoder.int8.onnx", "$HF/vocoder.int8.onnx", 25_991_073, "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152"),
            Part("$MODEL/tts.json", "$HF/tts.json", 8_253, "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"),
            Part("$MODEL/unicode_indexer.bin", "$HF/unicode_indexer.bin", 262_144, "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30"),
            Part("$MODEL/voice.bin", "$HF/voice.bin", 517_168, "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8"),
        )

    val files = ModelFiles("supertonic", "tts", PARTS) { setOn(it, true) }

    /** Supertonic 3's languages. */
    val LANGUAGES = setOf("en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi")

    /** Its voices: female 1-5, then male 1-5 (sherpa-onnx merges F1..F5, M1..M5 in name order). */
    const val VOICES = 10
    const val FEMALE = 5

    /** How a voice is saved in the voice settings, beside the phone engine's voice names. */
    private const val PREFIX = "supertonic:"

    fun voiceName(sid: Int) = "$PREFIX$sid"

    /** The voice a saved name stands for; null for none of Supertonic's. */
    fun sid(name: String?): Int? = name?.removePrefix(PREFIX)?.takeIf { name.startsWith(PREFIX) }?.toIntOrNull()?.takeIf { it in 0 until VOICES }

    fun dir(context: Context) = File(files.dir(context), MODEL)

    /** [language] as Supertonic names it: Android still says "in" for Indonesian. */
    fun code(language: String) = if (language == "in") "id" else language

    fun speaks(language: String) = code(language) in LANGUAGES

    /** Whether replies in [language] ("de") are read by Supertonic: downloaded, [on], and a language it knows. */
    fun used(
        context: Context,
        language: String,
    ) = speaks(language) && on(context) && files.downloaded(context)

    private const val PREFS = "speech"
    private const val ON = "supertonic"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var onState: MutableState<Boolean>? = null

    /** The pick as Compose state, made from the saved one by its first reader. */
    private fun onState(context: Context): MutableState<Boolean> =
        onState ?: synchronized(this) { onState ?: mutableStateOf(prefs(context).getBoolean(ON, true)).also { onState = it } }

    /** Whether Supertonic is picked over the phone's text-to-speech, once it's downloaded. */
    fun on(context: Context): Boolean = onState(context).value

    fun setOn(
        context: Context,
        on: Boolean,
    ) {
        onState(context).value = on
        prefs(context).edit().putBoolean(ON, on).apply()
    }
}
