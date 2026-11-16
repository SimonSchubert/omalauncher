// Thin JNI layer for the Wayland server: unix sockets with SCM_RIGHTS fd
// passing, shm pool mapping, per-surface pixel stores and compositing them
// into a window's ANativeWindow. All protocol logic lives in Kotlin.
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <errno.h>
#include <fcntl.h>
#include <setjmp.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define TAG "wlbridge"
#define MAX_FDS 28
#define FN(name) Java_dev_omalauncher_wayland_Native_##name

static inline int min_i(int a, int b) { return a < b ? a : b; }
static inline int max_i(int a, int b) { return a > b ? a : b; }

// --- sockets ---------------------------------------------------------------

JNIEXPORT jint JNICALL FN(listen)(JNIEnv *env, jclass cls, jstring jpath) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    size_t len = strlen(path);
    if (len >= sizeof(addr.sun_path)) {
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -ENAMETOOLONG;
    }
    memcpy(addr.sun_path, path, len + 1);
    unlink(path);
    (*env)->ReleaseStringUTFChars(env, jpath, path);

    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -errno;
    if (bind(fd, (struct sockaddr *) &addr, sizeof(addr)) < 0 || listen(fd, 16) < 0) {
        int err = errno;
        close(fd);
        return -err;
    }
    return fd;
}

JNIEXPORT jint JNICALL FN(accept)(JNIEnv *env, jclass cls, jint fd) {
    int c;
    do c = accept4(fd, NULL, NULL, SOCK_CLOEXEC); while (c < 0 && errno == EINTR);
    return c < 0 ? -errno : c;
}

// Reads into the direct buffer at [off, capacity) and received fds into
// fds[1..], fds[0] = fd count. Returns bytes read, 0 on EOF, -errno on failure.
JNIEXPORT jint JNICALL FN(recv)(JNIEnv *env, jclass cls, jint fd, jobject jbuf, jint off, jintArray jfds) {
    uint8_t *buf = (*env)->GetDirectBufferAddress(env, jbuf);
    jlong cap = (*env)->GetDirectBufferCapacity(env, jbuf);
    if (!buf || off >= cap) return -ENOBUFS;
    char cbuf[CMSG_SPACE(sizeof(int) * MAX_FDS)];
    struct iovec iov = {.iov_base = buf + off, .iov_len = (size_t) (cap - off)};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1, .msg_control = cbuf, .msg_controllen = sizeof(cbuf)};
    ssize_t n;
    do n = recvmsg(fd, &msg, MSG_CMSG_CLOEXEC); while (n < 0 && errno == EINTR);
    if (n < 0) return -errno;

    jint fds[MAX_FDS + 1];
    int count = 0;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS) continue;
        int nfd = (int) ((c->cmsg_len - CMSG_LEN(0)) / sizeof(int));
        int *p = (int *) CMSG_DATA(c);
        for (int i = 0; i < nfd; i++) {
            if (count < MAX_FDS) fds[1 + count++] = p[i];
            else close(p[i]);
        }
    }
    fds[0] = count;
    (*env)->SetIntArrayRegion(env, jfds, 0, count + 1, fds);
    return (jint) n;
}

// Sends len bytes of the direct buffer, attaching fds[0..nfds) to the first
// chunk. Returns bytes sent or -errno.
JNIEXPORT jint JNICALL FN(send)(JNIEnv *env, jclass cls, jint fd, jobject jbuf, jint len, jintArray jfds, jint nfds) {
    uint8_t *buf = (*env)->GetDirectBufferAddress(env, jbuf);
    if (!buf) return -EINVAL;
    int fds[MAX_FDS];
    if (nfds > MAX_FDS) return -EINVAL;
    if (nfds > 0) (*env)->GetIntArrayRegion(env, jfds, 0, nfds, fds);
    int sent = 0;
    while (sent < len) {
        struct iovec iov = {.iov_base = buf + sent, .iov_len = (size_t) (len - sent)};
        struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1};
        char cbuf[CMSG_SPACE(sizeof(int) * MAX_FDS)];
        if (nfds > 0 && sent == 0) {
            msg.msg_control = cbuf;
            msg.msg_controllen = CMSG_SPACE(sizeof(int) * nfds);
            struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
            c->cmsg_level = SOL_SOCKET;
            c->cmsg_type = SCM_RIGHTS;
            c->cmsg_len = CMSG_LEN(sizeof(int) * nfds);
            memcpy(CMSG_DATA(c), fds, sizeof(int) * nfds);
        }
        ssize_t n = sendmsg(fd, &msg, MSG_NOSIGNAL);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -errno;
        }
        sent += (int) n;
    }
    return sent;
}

JNIEXPORT void JNICALL FN(close)(JNIEnv *env, jclass cls, jint fd) {
    close(fd);
}

JNIEXPORT void JNICALL FN(shutdown)(JNIEnv *env, jclass cls, jint fd) {
    shutdown(fd, SHUT_RDWR);
}

JNIEXPORT jint JNICALL FN(openReadOnly)(JNIEnv *env, jclass cls, jstring jpath) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return fd < 0 ? -errno : fd;
}

// --- shm -------------------------------------------------------------------

JNIEXPORT jlong JNICALL FN(mmap)(JNIEnv *env, jclass cls, jint fd, jint size) {
    if (size <= 0) return 0;
    void *p = mmap(NULL, (size_t) size, PROT_READ, MAP_SHARED, fd, 0);
    return p == MAP_FAILED ? 0 : (jlong) (intptr_t) p;
}

JNIEXPORT void JNICALL FN(munmap)(JNIEnv *env, jclass cls, jlong ptr, jint size) {
    if (ptr) munmap((void *) (intptr_t) ptr, (size_t) size);
}

// A client may shrink its pool file under us; guard reads against SIGBUS so a
// misbehaving client can't take down the launcher.
static __thread sigjmp_buf *bus_jmp;
static struct sigaction old_bus;

static void on_sigbus(int sig, siginfo_t *info, void *ctx) {
    if (bus_jmp) siglongjmp(*bus_jmp, 1);
    sigaction(SIGBUS, &old_bus, NULL);
    raise(SIGBUS);
}

JNIEXPORT void JNICALL FN(installSigbusGuard)(JNIEnv *env, jclass cls) {
    struct sigaction sa = {.sa_sigaction = on_sigbus, .sa_flags = SA_SIGINFO | SA_ONSTACK};
    sigaction(SIGBUS, &sa, &old_bus);
}

// --- pixel stores ----------------------------------------------------------
//
// A surface's latest content, RGBA with premultiplied alpha, kept by us so a
// commit only copies what the client damaged and the client gets its buffer
// back right away. Callers serialize access per store.

typedef struct {
    int width, height;
    // Some pixel may have alpha below 255. Clients often send ARGB buffers
    // that are fully opaque; those then draw with plain copies, no blending.
    int translucent;
    uint32_t px[];
} Store;

JNIEXPORT jlong JNICALL FN(storeAlloc)(JNIEnv *env, jclass cls, jint width, jint height) {
    if (width <= 0 || height <= 0 || width > 16384 || height > 16384) return 0;
    // Zeroed: a store is transparent until its first copy lands.
    Store *s = calloc(1, sizeof(Store) + (size_t) width * height * 4);
    if (!s) return 0;
    s->width = width;
    s->height = height;
    s->translucent = 1;
    return (jlong) (intptr_t) s;
}

JNIEXPORT void JNICALL FN(storeFree)(JNIEnv *env, jclass cls, jlong ptr) {
    free((void *) (intptr_t) ptr);
}

// wl_shm ARGB8888 / XRGB8888 is 0xAARRGGBB, bytes B,G,R,A in memory; the store
// wants bytes R,G,B,A (0xAABBGGRR). Written so the compiler vectorizes it.
// Returns the AND of all pixels' alpha in the top byte.
static inline uint32_t swizzle_row(uint32_t *restrict d, const uint32_t *restrict s, int n, uint32_t alpha) {
    uint32_t all = 0xff000000u;
    for (int x = 0; x < n; x++) {
        uint32_t p = s[x] | alpha;
        all &= p;
        d[x] = (p & 0xff00ff00u) | ((p & 0xffu) << 16) | ((p >> 16) & 0xffu);
    }
    return all & 0xff000000u;
}

// Copies the given rects (x, y, w, h in buffer pixels) of a shm buffer into
// the store, which has the buffer's size. Returns 0, or -1 on SIGBUS.
JNIEXPORT jint JNICALL FN(storeCopy)(JNIEnv *env, jclass cls, jlong shm, jint offset, jint stride, jint format,
                                     jlong store, jintArray jrects, jint count) {
    Store *st = (Store *) (intptr_t) store;
    jint *rects = (*env)->GetIntArrayElements(env, jrects, NULL);
    const uint8_t *src = (const uint8_t *) (intptr_t) shm + offset;
    uint32_t alpha = format == 1 ? 0xff000000u : 0;
    sigjmp_buf jmp;
    int result = 0;
    if (sigsetjmp(jmp, 1) == 0) {
        bus_jmp = &jmp;
        for (int i = 0; i < count; i++) {
            int x0 = max_i(rects[i * 4], 0), y0 = max_i(rects[i * 4 + 1], 0);
            int x1 = min_i(rects[i * 4] + rects[i * 4 + 2], st->width);
            int y1 = min_i(rects[i * 4 + 1] + rects[i * 4 + 3], st->height);
            uint32_t all = 0xff000000u;
            for (int y = y0; y < y1; y++) {
                const uint32_t *s = (const uint32_t *) (src + (size_t) y * stride) + x0;
                all &= swizzle_row(st->px + (size_t) y * st->width + x0, s, x1 - x0, alpha);
            }
            // Any translucent pixel makes the store blend; only a whole opaque copy makes it stop.
            if (all != 0xff000000u) {
                st->translucent = 1;
            } else if (x0 == 0 && y0 == 0 && x1 == st->width && y1 == st->height) {
                st->translucent = 0;
            }
        }
    } else {
        __android_log_print(ANDROID_LOG_WARN, TAG, "SIGBUS reading client buffer");
        result = -1;
    }
    bus_jmp = NULL;
    (*env)->ReleaseIntArrayElements(env, jrects, rects, JNI_ABORT);
    return result;
}

// --- windows ---------------------------------------------------------------
//
// One Linux window's Android surface. A frame is begin (lock the dirty rect,
// clear it), a blit per visible surface, bottom to top, then end (post).

typedef struct {
    ANativeWindow *window;
    ANativeWindow_Buffer buf;
    ARect dirty;
    int locked;
    // Keeps alpha, premultiplied, for what's behind the view to show through.
    int translucent;
} Window;

JNIEXPORT jlong JNICALL FN(windowCreate)(JNIEnv *env, jclass cls, jobject surface, jint width, jint height, jboolean translucent) {
    ANativeWindow *w = ANativeWindow_fromSurface(env, surface);
    if (!w) return 0;
    // Opaque unless translucent: we fill every pixel, so SurfaceFlinger needn't blend the window.
    if (ANativeWindow_setBuffersGeometry(w, width, height, translucent ? WINDOW_FORMAT_RGBA_8888 : WINDOW_FORMAT_RGBX_8888) != 0) {
        ANativeWindow_release(w);
        return 0;
    }
    Window *win = calloc(1, sizeof(Window));
    win->window = w;
    win->translucent = translucent;
    return (jlong) (intptr_t) win;
}

JNIEXPORT void JNICALL FN(windowRelease)(JNIEnv *env, jclass cls, jlong ptr) {
    Window *win = (Window *) (intptr_t) ptr;
    if (!win) return;
    if (win->locked) ANativeWindow_unlockAndPost(win->window);
    ANativeWindow_release(win->window);
    free(win);
}

// Locks the window for drawing [dirty] (left, top, right, bottom), which
// Android may grow when it can't keep the previous frame's pixels; the rect
// actually locked is written back. Fills it with [background] (0xAARRGGBB),
// whose alpha only a translucent window keeps.
JNIEXPORT jboolean JNICALL FN(windowBegin)(JNIEnv *env, jclass cls, jlong ptr, jintArray jdirty, jint background) {
    Window *win = (Window *) (intptr_t) ptr;
    jint d[4];
    (*env)->GetIntArrayRegion(env, jdirty, 0, 4, d);
    ARect r = {.left = d[0], .top = d[1], .right = d[2], .bottom = d[3]};
    if (ANativeWindow_lock(win->window, &win->buf, &r) != 0) return JNI_FALSE;
    win->locked = 1;
    r.left = max_i(r.left, 0);
    r.top = max_i(r.top, 0);
    r.right = min_i(r.right, win->buf.width);
    r.bottom = min_i(r.bottom, win->buf.height);
    win->dirty = r;
    d[0] = r.left, d[1] = r.top, d[2] = r.right, d[3] = r.bottom;
    (*env)->SetIntArrayRegion(env, jdirty, 0, 4, d);

    uint32_t bg = (uint32_t) background;
    uint32_t fill = 0xff000000u | ((bg & 0xffu) << 16) | (bg & 0xff00u) | ((bg >> 16) & 0xffu);
    if (win->translucent) {
        uint32_t a = bg >> 24;
        uint32_t rb = ((fill & 0x00ff00ffu) * a + 0x00800080u) >> 8 & 0x00ff00ffu;
        uint32_t g = ((fill & 0x0000ff00u) * a + 0x00008000u) >> 8 & 0x0000ff00u;
        fill = (a << 24) | rb | g;
    }
    uint32_t *px = win->buf.bits;
    for (int y = r.top; y < r.bottom; y++) {
        uint32_t *row = px + (size_t) y * win->buf.stride;
        for (int x = r.left; x < r.right; x++) row[x] = fill;
    }
    return JNI_TRUE;
}

// Draws a store at (dx, dy), scaled to dw x dh window pixels (nearest
// neighbour when the client's buffer scale differs from ours), clipped to the
// locked rect. Premultiplied source-over unless the store is opaque.
JNIEXPORT void JNICALL FN(windowBlit)(JNIEnv *env, jclass cls, jlong ptr, jlong store, jint dx, jint dy, jint dw, jint dh,
                                      jboolean opaque) {
    Window *win = (Window *) (intptr_t) ptr;
    Store *st = (Store *) (intptr_t) store;
    if (!win->locked || !st || dw <= 0 || dh <= 0) return;
    int x0 = max_i(dx, win->dirty.left), y0 = max_i(dy, win->dirty.top);
    int x1 = min_i(dx + dw, win->dirty.right), y1 = min_i(dy + dh, win->dirty.bottom);
    if (x0 >= x1 || y0 >= y1) return;
    opaque = opaque || !st->translucent;
    uint32_t *px = win->buf.bits;
    int scaled = dw != st->width || dh != st->height;
    // 16.16 fixed-point steps from window to store pixels.
    uint32_t sx = ((uint32_t) st->width << 16) / (uint32_t) dw;
    uint32_t sy = ((uint32_t) st->height << 16) / (uint32_t) dh;
    for (int y = y0; y < y1; y++) {
        int srcY = scaled ? (int) (((uint32_t) (y - dy) * sy) >> 16) : y - dy;
        const uint32_t *src = st->px + (size_t) srcY * st->width;
        uint32_t *dst = px + (size_t) y * win->buf.stride;
        if (!scaled && opaque) {
            memcpy(dst + x0, src + (x0 - dx), (size_t) (x1 - x0) * 4);
            continue;
        }
        for (int x = x0; x < x1;) {
            if (!scaled) {
                // Runs of opaque pixels (most of a window with shadows or rounded corners) go as one copy.
                int end = x;
                while (end < x1 && src[end - dx] >= 0xff000000u) end++;
                if (end > x) {
                    memcpy(dst + x, src + (x - dx), (size_t) (end - x) * 4);
                    x = end;
                    continue;
                }
            }
            uint32_t s = src[scaled ? (int) (((uint32_t) (x - dx) * sx) >> 16) : x - dx];
            uint32_t a = s >> 24;
            if (opaque || a == 255) {
                dst[x] = s;
            } else if (a != 0 && win->translucent) {
                uint32_t d = dst[x], k = 255 - a;
                uint32_t rb = ((d & 0x00ff00ffu) * k + 0x00800080u) >> 8 & 0x00ff00ffu;
                uint32_t ag = (((d >> 8) & 0x00ff00ffu) * k + 0x00800080u) >> 8 & 0x00ff00ffu;
                dst[x] = s + rb + (ag << 8);
            } else if (a != 0) {
                uint32_t d = dst[x], k = 255 - a;
                uint32_t rb = ((d & 0x00ff00ffu) * k + 0x00800080u) >> 8 & 0x00ff00ffu;
                uint32_t g = ((d & 0x0000ff00u) * k + 0x00008000u) >> 8 & 0x0000ff00u;
                dst[x] = 0xff000000u | ((s & 0x00ffffffu) + rb + g);
            }
            x++;
        }
    }
}

JNIEXPORT void JNICALL FN(windowEnd)(JNIEnv *env, jclass cls, jlong ptr) {
    Window *win = (Window *) (intptr_t) ptr;
    if (!win->locked) return;
    win->locked = 0;
    ANativeWindow_unlockAndPost(win->window);
}
