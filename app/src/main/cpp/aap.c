// Lets android.airpods (AirPods.kt) make the socket AirPods speak AAP on: a classic L2CAP one, which Android has no
// public API for, and whose BluetoothSocket constructor an app's reflection is denied from Android 9 on. JNI_OnLoad
// runs on Runtime.nativeLoad's frame, which the hidden API check counts as the platform's own, so from here
// VMRuntime.setHiddenApiExemptions goes through. It opens BluetoothSocket's hidden members to this process, and no others.
#include <jni.h>
#include <android/log.h>

#define TAG "aap"
#define EXEMPT "Landroid/bluetooth/BluetoothSocket;"

// Whether the last call threw; clears it, as JNI may not be called with an exception pending.
static int thrown(JNIEnv *env) {
    if (!(*env)->ExceptionCheck(env)) return 0;
    (*env)->ExceptionClear(env);
    return 1;
}

static int exempt(JNIEnv *env) {
    jclass runtime = (*env)->FindClass(env, "dalvik/system/VMRuntime");
    if (thrown(env) || !runtime) return 0;
    jmethodID get = (*env)->GetStaticMethodID(env, runtime, "getRuntime", "()Ldalvik/system/VMRuntime;");
    if (thrown(env) || !get) return 0;
    jmethodID set = (*env)->GetMethodID(env, runtime, "setHiddenApiExemptions", "([Ljava/lang/String;)V");
    if (thrown(env) || !set) return 0;
    jobject vm = (*env)->CallStaticObjectMethod(env, runtime, get);
    if (thrown(env) || !vm) return 0;
    jclass string = (*env)->FindClass(env, "java/lang/String");
    if (thrown(env) || !string) return 0;
    jstring prefix = (*env)->NewStringUTF(env, EXEMPT);
    if (thrown(env) || !prefix) return 0;
    jobjectArray prefixes = (*env)->NewObjectArray(env, 1, string, prefix);
    if (thrown(env) || !prefixes) return 0;
    (*env)->CallVoidMethod(env, vm, set, prefixes);
    return !thrown(env);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    // Without it the constructor stays hidden, which AirPods.kt finds out for itself.
    if (!exempt(env)) __android_log_print(ANDROID_LOG_WARN, TAG, "no hidden API exemption for " EXEMPT);
    return JNI_VERSION_1_6;
}
