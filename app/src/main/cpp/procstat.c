// The CPU time of every process the app can see, for ProcShim's /proc/stat (linux/ProcShim.kt): a port of its
// ownCpuTicks, which stays for where the library isn't there. Once a second while btop runs, so without a Java
// object per process and file.
#include <jni.h>
#include <dirent.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

#define FN(name) Java_dev_omalauncher_linux_ProcStatNative_##name

// Kotlin's toLongOrNull ?: 0 of a field of digits, as /proc writes them.
static int64_t number(const char *s, int n) {
    int i = 0, negative = 0;
    if (n > 0 && (s[0] == '-' || s[0] == '+')) {
        if (n == 1) return 0;
        negative = s[0] == '-';
        i = 1;
    }
    if (i == n || n - i > 18) return 0;
    int64_t v = 0;
    for (; i < n; i++) {
        if (s[i] < '0' || s[i] > '9') return 0;
        v = v * 10 + (s[i] - '0');
    }
    return negative ? -v : v;
}

// {user, system} ticks summed over the processes in proc (/proc), or null when it can't be listed.
JNIEXPORT jlongArray JNICALL FN(ticks)(JNIEnv *env, jclass cls, jstring jproc) {
    char path[4200], buf[4096];
    const char *proc = (*env)->GetStringUTFChars(env, jproc, NULL);
    if (!proc) return NULL;
    size_t root = strlen(proc);
    DIR *dir = root < 4096 ? opendir(proc) : NULL;
    if (dir) memcpy(path, proc, root);
    (*env)->ReleaseStringUTFChars(env, jproc, proc);
    if (!dir) return NULL;
    int64_t user = 0, system = 0;
    struct dirent *e;
    while ((e = readdir(dir))) {
        const char *name = e->d_name;
        int digits = name[0] != 0;
        for (const char *c = name; *c; c++)
            if (*c < '0' || *c > '9') digits = 0;
        if (!digits || strlen(name) > 20) continue;
        path[root] = '/';
        strcpy(path + root + 1, name);
        strcat(path + root, "/stat");
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        ssize_t len = 0, got = 0;
        while (len < (ssize_t)sizeof buf - 1 && (got = read(fd, buf + len, sizeof buf - 1 - len)) > 0) len += got;
        close(fd);
        // Gone, or not ours, while it was read.
        if (got < 0) continue;
        buf[len] = 0;
        // After the last ')', past the name, trimmed: fields split on single spaces, the 12th and 13th utime and stime.
        char *p = strrchr(buf, ')');
        p = p ? p + 1 : buf;
        char *end = buf + len;
        while (p < end && (*p == ' ' || (*p >= '\t' && *p <= '\r'))) p++;
        while (end > p && (end[-1] == ' ' || (end[-1] >= '\t' && end[-1] <= '\r'))) end--;
        int field = 0;
        while (p <= end && field <= 12) {
            char *s = p;
            while (p < end && *p != ' ') p++;
            if (field == 11) user += number(s, (int)(p - s));
            if (field == 12) system += number(s, (int)(p - s));
            field++;
            p++;
        }
    }
    closedir(dir);
    jlongArray r = (*env)->NewLongArray(env, 2);
    if (r) {
        jlong t[2] = {user, system};
        (*env)->SetLongArrayRegion(env, r, 0, 2, t);
    }
    return r;
}
