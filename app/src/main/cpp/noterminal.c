// libterminal when scripts/fetch-ghostty-vt.sh hasn't built libghostty-vt: loading it fails, so the app has no
// built-in terminal (terminal/PtyNative.kt, TerminalLibrary). A library of this name always exists so the targets
// Gradle asks CMake for stay the same when libghostty-vt appears or goes.
#include <jni.h>

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    return JNI_ERR;
}
