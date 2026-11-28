// The built-in terminal's pseudo-terminal (terminal/PtyNative.kt): opens one, starts a program on its other end,
// reads and writes it, resizes it, and waits for the program to end. Builds for the host too, for the JVM tests
// (HostNative), so nothing here is Android-only.
// glibc declares posix_openpt and ptsname_r only with this.
#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#ifdef __linux__
#include <sys/syscall.h>
#endif
#ifdef __ANDROID__
#include <android/api-level.h>
#endif

#define FN(name) Java_dev_omalauncher_terminal_PtyNative_##name

// Whether the child may close its handles with close_range. Android 10 to 12 (and 13, some say) don't allow it to apps:
// their seccomp kills the program (SIGSYS) rather than failing the call, before the loop could take over. Bionic has
// it from Android 14 (API 34), so it's allowed there.
#if defined(__linux__) && defined(SYS_close_range)
static int closeRange(void) {
#ifdef __ANDROID__
    return android_get_device_api_level() >= 34;
#else
    return 1;
#endif
}
#endif

static char **strings(JNIEnv *env, jobjectArray arr) {
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = calloc((size_t)n + 1, sizeof(char *));
    if (!out) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, arr, i);
        const char *c = s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
        out[i] = strdup(c ? c : "");
        if (c) (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

static void release(char **list) {
    if (!list) return;
    for (char **p = list; *p; p++) free(*p);
    free(list);
}

// Starts argv[0] (a path) with env, in cwd, on a new pseudo-terminal of rows x cols. With leader, the program leads
// the terminal's session and has it as its controlling terminal; without, it starts in a process group of its own
// and is expected to do that itself (the guest's `setsid -c`: proot, which runs it, ignores the hangup). Returns
// {fd of the terminal's master side, pid}, or null with nothing left open.
JNIEXPORT jintArray JNICALL FN(start)(JNIEnv *env, jclass cls, jobjectArray jargv, jobjectArray jenv, jstring jcwd, jint rows,
                                      jint cols, jboolean leader) {
    (void)cls;
    if ((*env)->GetArrayLength(env, jargv) == 0) return NULL;
    char **argv = strings(env, jargv);
    char **envp = strings(env, jenv);
    const char *c = (*env)->GetStringUTFChars(env, jcwd, NULL);
    char *cwd = strdup(c ? c : "/");
    if (c) (*env)->ReleaseStringUTFChars(env, jcwd, c);

    char name[128];
    int fd = posix_openpt(O_RDWR | O_NOCTTY);
    // Not blocking: write() waits for room together with its wake (below), so a program that doesn't read holds
    // nobody up for good.
    if (fd >= 0 && (fcntl(fd, F_SETFD, FD_CLOEXEC) || fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK) || grantpt(fd) ||
                    unlockpt(fd) || ptsname_r(fd, name, sizeof name))) {
        close(fd);
        fd = -1;
    }
    pid_t pid = -1;
    if (fd >= 0 && argv && envp && cwd) {
        // Text is UTF-8, so a backspace erases a whole character; no XON/XOFF, so Ctrl+S and Ctrl+Q reach programs.
        struct termios tio;
        if (tcgetattr(fd, &tio) == 0) {
#ifdef IUTF8
            tio.c_iflag |= IUTF8;
#endif
            tio.c_iflag &= ~(tcflag_t)(IXON | IXOFF);
            tcsetattr(fd, TCSANOW, &tio);
        }
        // Set from the child, on its side: macOS forgets a size set on the master before the slave is opened.
        struct winsize ws = {.ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols};
        struct rlimit rl;
        int maxfd = getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur < 65536 ? (int)rl.rlim_cur : 65536;
#if defined(__linux__) && defined(SYS_close_range)
        int range = closeRange();
#endif

        // Only async-signal-safe calls between fork and exec: the JVM has threads.
        pid = fork();
        if (pid == 0) {
            sigset_t none;
            sigemptyset(&none);
            sigprocmask(SIG_SETMASK, &none, NULL);
            for (int s = 1; s < NSIG; s++) signal(s, SIG_DFL);
            int pts;
            if (leader) {
                setsid();
                pts = open(name, O_RDWR);
                if (pts < 0) _exit(126);
                ioctl(pts, TIOCSCTTY, 0);
            } else {
                setpgid(0, 0);
                pts = open(name, O_RDWR | O_NOCTTY);
                if (pts < 0) _exit(126);
            }
            ioctl(pts, TIOCSWINSZ, &ws);
            dup2(pts, 0);
            dup2(pts, 1);
            dup2(pts, 2);
#if defined(__linux__) && defined(SYS_close_range)
            if (!range || syscall(SYS_close_range, 3, ~0U, 0) != 0)
#endif
                for (int i = 3; i < maxfd; i++) close(i);
            if (chdir(cwd)) {}
            execve(argv[0], argv, envp);
            _exit(127);
        }
    }
    release(argv);
    release(envp);
    free(cwd);
    if (pid < 0) {
        if (fd >= 0) close(fd);
        return NULL;
    }
    jint out[2] = {fd, (jint)pid};
    jintArray arr = (*env)->NewIntArray(env, 2);
    if (arr) (*env)->SetIntArrayRegion(env, arr, 0, 2, out);
    return arr;
}

// The terminal's size in cells and pixels; the program gets SIGWINCH.
JNIEXPORT void JNICALL FN(resize)(JNIEnv *env, jclass cls, jint fd, jint rows, jint cols, jint width, jint height) {
    (void)env;
    (void)cls;
    struct winsize ws = {.ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols,
                         .ws_xpixel = (unsigned short)width, .ws_ypixel = (unsigned short)height};
    ioctl(fd, TIOCSWINSZ, &ws);
}

// Waits for output: the byte count read into buf, 0 when wake became readable (see wakePipe), or -1 once the
// program's side is closed. A read() blocked in another thread keeps the master open, so hanging up goes through
// wake and the reading thread closes the master itself.
JNIEXPORT jint JNICALL FN(read)(JNIEnv *env, jclass cls, jint fd, jint wake, jbyteArray jbuf) {
    (void)cls;
    struct pollfd p[2] = {{.fd = fd, .events = POLLIN}, {.fd = wake, .events = POLLIN}};
    for (;;) {
        if (poll(p, 2, -1) < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (p[1].revents) return 0;
        if (!p[0].revents) continue;
        jsize cap = (*env)->GetArrayLength(env, jbuf);
        jbyte *buf = (*env)->GetByteArrayElements(env, jbuf, NULL);
        if (!buf) return -1;
        ssize_t n;
        do n = read(fd, buf, (size_t)cap);
        while (n < 0 && errno == EINTR);
        (*env)->ReleaseByteArrayElements(env, jbuf, buf, n > 0 ? 0 : JNI_ABORT);
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        // EIO: every handle on the program's side is closed.
        return n > 0 ? (jint)n : -1;
    }
}

// Writes len bytes of buf from off, waiting while the program doesn't take them. False once the terminal is gone,
// or when wake became readable while it waited (see wakePipe): what was left is dropped.
JNIEXPORT jboolean JNICALL FN(write)(JNIEnv *env, jclass cls, jint fd, jint wake, jbyteArray jbuf, jint off, jint len) {
    (void)cls;
    jbyte *buf = (*env)->GetByteArrayElements(env, jbuf, NULL);
    if (!buf) return JNI_FALSE;
    const char *p = (const char *)buf + off;
    size_t left = (size_t)len;
    jboolean ok = JNI_TRUE;
    while (left > 0) {
        ssize_t w = write(fd, p, left);
        if (w >= 0) {
            p += w;
            left -= (size_t)w;
            continue;
        }
        if (errno == EINTR) continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            struct pollfd q[2] = {{.fd = fd, .events = POLLOUT}, {.fd = wake, .events = POLLIN}};
            if (poll(q, 2, -1) < 0 && errno != EINTR) ok = JNI_FALSE;
            else if (q[1].revents) ok = JNI_FALSE;
            if (ok) continue;
        } else {
            ok = JNI_FALSE;
        }
        break;
    }
    (*env)->ReleaseByteArrayElements(env, jbuf, buf, JNI_ABORT);
    return ok;
}

// A pipe {read end, write end} whose read end read() and write() also wait on: a byte written to it wakes both, and
// stays there, so neither waits again.
JNIEXPORT jintArray JNICALL FN(wakePipe)(JNIEnv *env, jclass cls) {
    (void)cls;
    int p[2];
    if (pipe(p)) return NULL;
    fcntl(p[0], F_SETFD, FD_CLOEXEC);
    fcntl(p[1], F_SETFD, FD_CLOEXEC);
    jint out[2] = {p[0], p[1]};
    jintArray arr = (*env)->NewIntArray(env, 2);
    if (arr) (*env)->SetIntArrayRegion(env, arr, 0, 2, out);
    return arr;
}

JNIEXPORT void JNICALL FN(wake)(JNIEnv *env, jclass cls, jint fd) {
    (void)env;
    (void)cls;
    char b = 1;
    if (write(fd, &b, 1)) {}
}

JNIEXPORT void JNICALL FN(close)(JNIEnv *env, jclass cls, jint fd) {
    (void)env;
    (void)cls;
    close(fd);
}

// Waits for pid to end: its exit code, or 128 + the signal that killed it, as a shell reports it; -1 if it isn't ours.
JNIEXPORT jint JNICALL FN(waitFor)(JNIEnv *env, jclass cls, jint pid) {
    (void)env;
    (void)cls;
    int status;
    while (waitpid(pid, &status, 0) < 0)
        if (errno != EINTR) return -1;
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}

