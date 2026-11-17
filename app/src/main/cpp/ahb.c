// AHardwareBuffers for clients (oma_ahb_v1, see wayland/Ahb.kt): allocating
// them and handing out their dma-bufs, putting them on screen as layers of
// their own (ASurfaceControl) without copying, and, where that can't be done,
// copying them into a window like a pixel store.
#include <android/data_space.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/surface_control.h>
#include <errno.h>
#include <jni.h>
#include <poll.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "wlbridge.h"

#define FN(name) Java_dev_omalauncher_wayland_Native_##name
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, WL_TAG, __VA_ARGS__)

#define MAX_HANDLE_FDS 8
// HAL_PIXEL_FORMAT_BGRA_8888: grallocs have it, the NDK's AHardwareBuffer formats don't name it.
#define FORMAT_B8G8R8A8 5
#define USAGE                                                                                                    \
    (AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |                          \
     AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY | AHARDWAREBUFFER_USAGE_CPU_READ_RARELY)

// --- allocation ------------------------------------------------------------

// The NDK has no call for a buffer's native handle, but it sends one over a
// socket for another process's AHardwareBuffer_recvHandleFromUnixSocket:
// sending it to ourselves gives dups of its fds. Returns how many, or -errno.
static int handle_fds(AHardwareBuffer *ahb, int *fds, int max) {
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sv)) return -errno;
    int r = AHardwareBuffer_sendHandleToUnixSocket(ahb, sv[0]);
    if (r) {
        close(sv[0]);
        close(sv[1]);
        return r < 0 ? r : -EIO;
    }
    char data[4096];
    char cbuf[CMSG_SPACE(sizeof(int) * MAX_HANDLE_FDS)];
    struct iovec iov = {.iov_base = data, .iov_len = sizeof(data)};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1, .msg_control = cbuf, .msg_controllen = sizeof(cbuf)};
    ssize_t n = recvmsg(sv[1], &msg, MSG_CMSG_CLOEXEC);
    int err = errno;
    close(sv[0]);
    close(sv[1]);
    if (n < 0) return -err;
    int count = 0;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS) continue;
        int nfd = (int) ((c->cmsg_len - CMSG_LEN(0)) / sizeof(int));
        for (int i = 0; i < nfd; i++) {
            int fd;
            memcpy(&fd, CMSG_DATA(c) + i * sizeof(int), sizeof(int));
            if (count < max) fds[count++] = fd;
            else close(fd);
        }
    }
    return count;
}

// Whether buffers of an AHardwareBuffer format can be had with our usage: 1 (RGBA) always, 5 (BGRA) not everywhere.
JNIEXPORT jboolean JNICALL FN(ahbSupported)(JNIEnv *env, jclass cls, jint format) {
    AHardwareBuffer_Desc want = {.width = 64, .height = 64, .layers = 1, .format = (uint32_t) format, .usage = USAGE};
    if (!AHardwareBuffer_isSupported(&want)) return JNI_FALSE;
    // isSupported answers from the allocator's mapper, which may not know a format the NDK doesn't list: try one.
    AHardwareBuffer *ahb = NULL;
    if (AHardwareBuffer_allocate(&want, &ahb) || !ahb) return JNI_FALSE;
    AHardwareBuffer_release(ahb);
    return JNI_TRUE;
}

// Allocates a width x height buffer of an AHardwareBuffer format (1 RGBA, 5
// BGRA) the GPU renders into and the display shows. info gets the dma-buf with
// its pixels (an fd the caller owns), the offset and bytes per row. Returns
// the AHardwareBuffer, 0 on failure.
JNIEXPORT jlong JNICALL FN(ahbAllocate)(JNIEnv *env, jclass cls, jint width, jint height, jint format, jintArray jinfo) {
    AHardwareBuffer_Desc want = {
        .width = (uint32_t) width,
        .height = (uint32_t) height,
        .layers = 1,
        .format = (uint32_t) format,
        // CPU read keeps gralloc linear (MediaTek's would pick AFBC, which the guest can't import as such)
        // and lets a window that can't show the buffer as a layer copy it instead.
        .usage = USAGE,
    };
    AHardwareBuffer *ahb = NULL;
    int r = AHardwareBuffer_allocate(&want, &ahb);
    if (r || !ahb) {
        LOGW("AHardwareBuffer_allocate %dx%d failed: %d", width, height, r);
        return 0;
    }
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(ahb, &desc);
    int fds[MAX_HANDLE_FDS];
    int n = handle_fds(ahb, fds, MAX_HANDLE_FDS);
    // The pixels are the biggest fd: QTI puts them first, MediaTek second (after gralloc_extra), metadata is small.
    int pixel = -1;
    off_t best = 0;
    for (int i = 0; i < n; i++) {
        off_t size = lseek(fds[i], 0, SEEK_END);
        if (size > best) best = size, pixel = i;
    }
    for (int i = 0; i < n; i++)
        if (i != pixel) close(fds[i]);
    uint64_t need = (uint64_t) desc.stride * desc.height * 4;
    if (pixel < 0 || (uint64_t) best < need) {
        LOGW("no dma-buf with the pixels of a %ux%u buffer (stride %u, %d fds, biggest %lld bytes)", desc.width,
             desc.height, desc.stride, n, (long long) best);
        if (pixel >= 0) close(fds[pixel]);
        AHardwareBuffer_release(ahb);
        return 0;
    }
    jint info[3] = {fds[pixel], 0, (jint) (desc.stride * 4)};
    (*env)->SetIntArrayRegion(env, jinfo, 0, 3, info);
    return (jlong) (intptr_t) ahb;
}

JNIEXPORT void JNICALL FN(ahbRelease)(JNIEnv *env, jclass cls, jlong ahb) {
    if (ahb) AHardwareBuffer_release((AHardwareBuffer *) (intptr_t) ahb);
}

// Copies a buffer into the locked window, as windowBlit does a store. The
// client finished rendering before it committed, so there's nothing to wait for.
JNIEXPORT void JNICALL FN(windowBlitAhb)(JNIEnv *env, jclass cls, jlong window, jlong ahb, jint dx, jint dy, jint dw,
                                         jint dh, jboolean opaque) {
    AHardwareBuffer *b = (AHardwareBuffer *) (intptr_t) ahb;
    Window *win = (Window *) (intptr_t) window;
    if (!b || !win->locked) return;
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(b, &desc);
    void *px = NULL;
    if (AHardwareBuffer_lock(b, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1, NULL, &px) || !px) return;
    uint32_t *rgba = NULL;
    if (desc.format == FORMAT_B8G8R8A8) {
        // The window and the blit are RGBA: swap red and blue first. Only on this slow path; overlays take BGRA as is.
        size_t n = (size_t) desc.stride * desc.height;
        rgba = malloc(n * 4);
        if (rgba) {
            const uint32_t *s = px;
            for (size_t i = 0; i < n; i++) rgba[i] = (s[i] & 0xff00ff00u) | ((s[i] & 0xffu) << 16) | ((s[i] >> 16) & 0xffu);
        }
    }
    if (desc.format != FORMAT_B8G8R8A8 || rgba)
        wl_blit(win, rgba ? rgba : px, (int) desc.width, (int) desc.height, (int) desc.stride, !opaque, dx, dy, dw, dh, opaque);
    free(rgba);
    AHardwareBuffer_unlock(b, NULL);
}

// --- overlays --------------------------------------------------------------
//
// A surface whose content is an AHardwareBuffer is shown as a child layer of
// its window's SurfaceView: the display composes it (often as a hardware
// overlay), no CPU or GPU copy. Each buffer set on a layer comes with a
// Runnable, run once the display is done with it: the client gets it back then.

static JavaVM *jvm;
static jmethodID run_method;
static pthread_key_t detach_key;
static pthread_once_t detach_once = PTHREAD_ONCE_INIT;

static void detach_thread(void *unused) { (*jvm)->DetachCurrentThread(jvm); }

static void make_detach_key(void) { pthread_key_create(&detach_key, detach_thread); }

// The callbacks come on binder threads, which may not be attached to the VM.
static JNIEnv *thread_env(void) {
    JNIEnv *env = NULL;
    if ((*jvm)->GetEnv(jvm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*jvm)->AttachCurrentThreadAsDaemon(jvm, &env, NULL) != JNI_OK) return NULL;
    pthread_once(&detach_once, make_detach_key);
    pthread_setspecific(detach_key, env);
    return env;
}

typedef struct {
    ASurfaceControl *sc;
    jobject shown; // the Runnable of the buffer on it, or NULL
} Overlay;

// A buffer the display may still read: [done] runs once the transaction that
// took it off [sc] completed and the release fence for it signaled.
typedef struct {
    ASurfaceControl *sc;
    jobject done;
} Retired;

typedef struct {
    ASurfaceTransaction *tx;
    Retired *retired;
    int nretired, cap;
    ASurfaceControl **dropped; // released after apply: the transaction holds its own references till then
    int ndropped, dcap;
} Tx;

static void retire(Tx *t, ASurfaceControl *sc, jobject done) {
    if (!done) return;
    if (t->nretired == t->cap) {
        t->cap = t->cap ? t->cap * 2 : 4;
        t->retired = realloc(t->retired, sizeof(Retired) * (size_t) t->cap);
    }
    t->retired[t->nretired++] = (Retired) {sc, done};
}

static void wait_fence(int fd) {
    struct pollfd pfd = {.fd = fd, .events = POLLIN};
    int r;
    do r = poll(&pfd, 1, 1000);
    while (r < 0 && errno == EINTR);
    if (r == 0) LOGW("a release fence did not signal in 1 s");
}

static void on_complete(void *ctx, ASurfaceTransactionStats *stats) {
    Tx *t = ctx;
    ASurfaceControl **scs = NULL;
    size_t nsc = 0;
    ASurfaceTransactionStats_getASurfaceControls(stats, &scs, &nsc);
    JNIEnv *env = thread_env();
    for (int i = 0; i < t->nretired; i++) {
        Retired *r = &t->retired[i];
        // Asking for a surface the stats don't have aborts the process: look first.
        for (size_t j = 0; j < nsc; j++) {
            if (scs[j] != r->sc) continue;
            int fence = ASurfaceTransactionStats_getPreviousReleaseFenceFd(stats, r->sc);
            if (fence >= 0) {
                wait_fence(fence);
                close(fence);
            }
            break;
        }
        if (!env) continue;
        (*env)->CallVoidMethod(env, r->done, run_method);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, r->done);
    }
    if (scs) ASurfaceTransactionStats_releaseASurfaceControls(scs);
    free(t->retired);
    free(t);
}

// A child layer of the window's surface, above its own pixels; 0 on failure.
JNIEXPORT jlong JNICALL FN(overlayCreate)(JNIEnv *env, jclass cls, jlong window) {
    if (!jvm) {
        (*env)->GetJavaVM(env, &jvm);
        jclass runnable = (*env)->FindClass(env, "java/lang/Runnable");
        run_method = (*env)->GetMethodID(env, runnable, "run", "()V");
    }
    Window *win = (Window *) (intptr_t) window;
    if (!win) return 0;
    ASurfaceControl *sc = ASurfaceControl_createFromWindow(win->window, "omalauncher-ahb");
    if (!sc) return 0;
    Overlay *ov = calloc(1, sizeof(Overlay));
    ov->sc = sc;
    return (jlong) (intptr_t) ov;
}

JNIEXPORT jlong JNICALL FN(txBegin)(JNIEnv *env, jclass cls) {
    Tx *t = calloc(1, sizeof(Tx));
    t->tx = ASurfaceTransaction_create();
    return (jlong) (intptr_t) t;
}

// Shows [ahb] on the layer from this transaction on; [done] runs when it's off screen again.
JNIEXPORT void JNICALL FN(txSetBuffer)(JNIEnv *env, jclass cls, jlong tx, jlong overlay, jlong ahb, jobject done,
                                       jboolean opaque) {
    Tx *t = (Tx *) (intptr_t) tx;
    Overlay *ov = (Overlay *) (intptr_t) overlay;
    retire(t, ov->sc, ov->shown);
    ov->shown = (*env)->NewGlobalRef(env, done);
    ASurfaceTransaction_setBuffer(t->tx, ov->sc, (AHardwareBuffer *) (intptr_t) ahb, -1);
    ASurfaceTransaction_setBufferTransparency(t->tx, ov->sc,
                                              opaque ? ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE
                                                     : ASURFACE_TRANSACTION_TRANSPARENCY_TRANSLUCENT);
    ASurfaceTransaction_setBufferDataSpace(t->tx, ov->sc, ADATASPACE_SRGB);
}

// Shows the whole src_w x src_h buffer at (x, y), scaled to w x h window
// pixels, [z] above the window's own pixels (1 and up).
JNIEXPORT void JNICALL FN(txPlace)(JNIEnv *env, jclass cls, jlong tx, jlong overlay, jint src_w, jint src_h, jint x,
                                   jint y, jint w, jint h, jint z) {
    Tx *t = (Tx *) (intptr_t) tx;
    Overlay *ov = (Overlay *) (intptr_t) overlay;
    if (src_w <= 0 || src_h <= 0 || w <= 0 || h <= 0) return;
    ARect src = {0, 0, src_w, src_h};
    ARect dst = {x, y, x + w, y + h};
    // Deprecated from API 31 for setCrop/setPosition/setScale, but those don't exist on 29 and 30.
    ASurfaceTransaction_setGeometry(t->tx, ov->sc, &src, &dst, 0);
    ASurfaceTransaction_setZOrder(t->tx, ov->sc, z);
    ASurfaceTransaction_setVisibility(t->tx, ov->sc, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
}

// Takes the layer off screen and frees it; its buffer's Runnable runs once that's done.
JNIEXPORT void JNICALL FN(txRemove)(JNIEnv *env, jclass cls, jlong tx, jlong overlay) {
    Tx *t = (Tx *) (intptr_t) tx;
    Overlay *ov = (Overlay *) (intptr_t) overlay;
    ASurfaceTransaction_reparent(t->tx, ov->sc, NULL);
    retire(t, ov->sc, ov->shown);
    if (t->ndropped == t->dcap) {
        t->dcap = t->dcap ? t->dcap * 2 : 4;
        t->dropped = realloc(t->dropped, sizeof(ASurfaceControl *) * (size_t) t->dcap);
    }
    t->dropped[t->ndropped++] = ov->sc;
    free(ov);
}

JNIEXPORT void JNICALL FN(txApply)(JNIEnv *env, jclass cls, jlong tx) {
    Tx *t = (Tx *) (intptr_t) tx;
    // The callback frees t, so nothing below may touch what it does.
    ASurfaceControl **dropped = t->dropped;
    int ndropped = t->ndropped;
    ASurfaceTransaction *txn = t->tx;
    ASurfaceTransaction_setOnComplete(txn, t, on_complete);
    ASurfaceTransaction_apply(txn);
    ASurfaceTransaction_delete(txn);
    for (int i = 0; i < ndropped; i++) ASurfaceControl_release(dropped[i]);
    free(dropped);
}
