-keep class com.k2fsa.sherpa.onnx.** { *; }
# sherpa's JNI calls the TTS callback's typed invoke([F)Ljava/lang/Integer; by name.
-keepclassmembers class dev.omalauncher.voice.SherpaVoice$** { java.lang.Integer invoke(float[]); }
