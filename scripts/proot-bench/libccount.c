// Counts calls a program makes through libc's path functions, to size how much of proot's work a
// preloaded library could take over (calls made inside libc, by ld.so or by raw syscalls never get
// here). Each process appends "name count" lines to $LIBCCOUNT_OUT when it exits or execs.
// Build in the guest: gcc -O2 -shared -fPIC libccount.c -o /usr/local/lib/libccount.so
#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define NAMES X(stat) X(lstat) X(fstat) X(fstatat) X(statx) X(open) X(openat) X(access) \
    X(faccessat) X(readlink) X(readlinkat) X(getcwd) X(fopen) X(opendir) X(realpath) X(close) \
    X(geteuid) X(getuid) X(getegid) X(getgid) X(ioctl) X(chdir) X(execve)
enum {
#define X(n) C_##n,
    NAMES
#undef X
    C_N
};
static const char *names[] = {
#define X(n) #n,
    NAMES
#undef X
};
static long counts[C_N];

static void flush(void) {
    const char *out = getenv("LIBCCOUNT_OUT");
    if (!out) return;
    char buf[2048];
    int n = 0;
    for (int i = 0; i < C_N; i++)
        if (counts[i]) n += snprintf(buf + n, sizeof buf - n, "%s %ld\n", names[i], counts[i]);
    int fd = syscall(SYS_openat, AT_FDCWD, out, O_WRONLY | O_APPEND | O_CREAT, 0666);
    if (fd < 0) return;
    syscall(SYS_write, fd, buf, n);
    syscall(SYS_close, fd);
    memset(counts, 0, sizeof counts);
}
__attribute__((destructor)) static void fini(void) { flush(); }

#define REAL(n) ({ static void *p; if (!p) p = dlsym(RTLD_NEXT, #n); (typeof(&n))p; })
#define HIT(n) __atomic_add_fetch(&counts[C_##n], 1, __ATOMIC_RELAXED)

int stat(const char *p, struct stat *s) { HIT(stat); return REAL(stat)(p, s); }
int stat64(const char *p, struct stat64 *s) { HIT(stat); return REAL(stat64)(p, s); }
int lstat(const char *p, struct stat *s) { HIT(lstat); return REAL(lstat)(p, s); }
int lstat64(const char *p, struct stat64 *s) { HIT(lstat); return REAL(lstat64)(p, s); }
int fstat(int f, struct stat *s) { HIT(fstat); return REAL(fstat)(f, s); }
int fstat64(int f, struct stat64 *s) { HIT(fstat); return REAL(fstat64)(f, s); }
int fstatat(int d, const char *p, struct stat *s, int f) { HIT(fstatat); return REAL(fstatat)(d, p, s, f); }
int fstatat64(int d, const char *p, struct stat64 *s, int f) { HIT(fstatat); return REAL(fstatat64)(d, p, s, f); }
int statx(int d, const char *p, int f, unsigned m, struct statx *s) { HIT(statx); return REAL(statx)(d, p, f, m, s); }
int open(const char *p, int f, ...) { va_list a; va_start(a, f); int m = va_arg(a, int); va_end(a); HIT(open); return REAL(open)(p, f, m); }
int open64(const char *p, int f, ...) { va_list a; va_start(a, f); int m = va_arg(a, int); va_end(a); HIT(open); return REAL(open64)(p, f, m); }
int openat(int d, const char *p, int f, ...) { va_list a; va_start(a, f); int m = va_arg(a, int); va_end(a); HIT(openat); return REAL(openat)(d, p, f, m); }
int openat64(int d, const char *p, int f, ...) { va_list a; va_start(a, f); int m = va_arg(a, int); va_end(a); HIT(openat); return REAL(openat64)(d, p, f, m); }
int access(const char *p, int m) { HIT(access); return REAL(access)(p, m); }
int faccessat(int d, const char *p, int m, int f) { HIT(faccessat); return REAL(faccessat)(d, p, m, f); }
ssize_t readlink(const char *p, char *b, size_t n) { HIT(readlink); return REAL(readlink)(p, b, n); }
ssize_t readlinkat(int d, const char *p, char *b, size_t n) { HIT(readlinkat); return REAL(readlinkat)(d, p, b, n); }
char *getcwd(char *b, size_t n) { HIT(getcwd); return REAL(getcwd)(b, n); }
FILE *fopen(const char *p, const char *m) { HIT(fopen); return REAL(fopen)(p, m); }
FILE *fopen64(const char *p, const char *m) { HIT(fopen); return REAL(fopen64)(p, m); }
#include <dirent.h>
DIR *opendir(const char *p) { HIT(opendir); return REAL(opendir)(p); }
char *realpath(const char *p, char *r) { HIT(realpath); return REAL(realpath)(p, r); }
int close(int f) { HIT(close); return REAL(close)(f); }
uid_t geteuid(void) { HIT(geteuid); return REAL(geteuid)(); }
uid_t getuid(void) { HIT(getuid); return REAL(getuid)(); }
gid_t getegid(void) { HIT(getegid); return REAL(getegid)(); }
gid_t getgid(void) { HIT(getgid); return REAL(getgid)(); }
int ioctl(int f, unsigned long r, ...) { va_list a; va_start(a, r); void *p = va_arg(a, void *); va_end(a); HIT(ioctl); return REAL(ioctl)(f, r, p); }
int chdir(const char *p) { HIT(chdir); return REAL(chdir)(p); }
int execve(const char *p, char *const a[], char *const e[]) { HIT(execve); flush(); return REAL(execve)(p, a, e); }
