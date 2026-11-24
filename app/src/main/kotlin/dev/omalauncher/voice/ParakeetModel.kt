package dev.omalauncher.voice

import android.content.Context
import dev.omalauncher.voice.ModelFiles.Part
import java.util.Locale

/**
 * [Parakeet]'s files on the phone ([ModelFiles]): downloaded when asked for in the voice settings, from Hugging Face
 * (the model) and sherpa-onnx's releases (the VAD).
 */
internal object ParakeetModel {
    private const val HF = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"
    const val MODEL = "parakeet-tdt-0.6b-v3-int8"
    const val VAD = "silero_vad.onnx"

    private val PARTS =
        listOf(
            Part("$MODEL/encoder.int8.onnx", "$HF/encoder.int8.onnx", 652_282_294, "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479"),
            Part("$MODEL/decoder.int8.onnx", "$HF/decoder.int8.onnx", 11_845_274, "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5"),
            Part("$MODEL/joiner.int8.onnx", "$HF/joiner.int8.onnx", 6_355_277, "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98"),
            Part("$MODEL/tokens.txt", "$HF/tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
            Part(VAD, "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx", 643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
        )

    val files = ModelFiles("parakeet", "speech", PARTS, pref = "parakeet")

    /** The languages Parakeet TDT 0.6B v3 knows; it tells them apart itself. */
    private val LANGUAGES = setOf("bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it", "lv", "lt", "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "ru", "uk")

    fun speaks(locale: Locale) = locale.language in LANGUAGES

    fun dir(context: Context) = files.dir(context)

    /** Whether speech is recognized with Parakeet, for [locale]: downloaded, picked, and a language it knows. */
    fun used(
        context: Context,
        locale: Locale,
    ) = speaks(locale) && files.used(context)
}
