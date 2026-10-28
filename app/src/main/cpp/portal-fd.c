#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>

/* Os.fcntlInt is API 30; the NDK fcntl ABI also supports our API-29 minimum. */
JNIEXPORT jint JNICALL
Java_dev_omalauncher_linux_PortalFd_flags(JNIEnv *env, jobject unused, jint fd) {
    int value = fcntl(fd, F_GETFL);
    if (value < 0) {
        int error = errno;
        jclass exception = (*env)->FindClass(env, "java/io/IOException");
        if (exception) (*env)->ThrowNew(env, exception, strerror(error));
    }
    return value;
}
