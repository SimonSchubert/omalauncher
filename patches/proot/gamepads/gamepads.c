// Game controllers for Linux programs, without root. Preloaded into guest programs with
// LD_PRELOAD, ahead of the fast lane. /dev/input in the guest is a folder of the launcher's
// (linux/Gamepads.kt) in which each controller Android sees is a unix socket named eventN. Opening
// one connects to it instead: the launcher answers with a description of the controller (struct pad,
// laid out the same in PadLayout.kt), then sends input_event records as the kernel would. read, poll
// and epoll work on the socket as they are; this library answers evdev's ioctls (EVIOCG*) from the
// description, and stat and fstat as for a character device, by which SDL and RetroArch tell
// devices apart. Listing /dev/input and watching it with inotify need nothing: the folder is real.
//
// Freestanding, like the fast lane: no libc headers and no libc calls except __errno_location, and
// dlsym to hand everything that isn't a controller to the next library (the fast lane, or libc).
// arm64 only.
// Build: clang --target=aarch64-linux-gnu -O2 -fPIC -shared -nostdlib -ffreestanding \
//        -mno-outline-atomics gamepads.c -o libgamepads.so

typedef unsigned long u64;
typedef long i64;
typedef unsigned int u32;
typedef int i32;
typedef unsigned short u16;
typedef unsigned char u8;

#define SYS_fcntl 25
#define SYS_ioctl 29
#define SYS_openat 56
#define SYS_close 57
#define SYS_read 63
#define SYS_newfstatat 79
#define SYS_fstat 80
#define SYS_socket 198
#define SYS_connect 203

#define AT_FDCWD (-100)
#define AT_SYMLINK_NOFOLLOW 0x100
#define AF_UNIX 1
#define SOCK_STREAM 1
#define O_NONBLOCK 04000
#define O_CLOEXEC 02000000
#define F_SETFD 2
#define F_GETFL 3
#define F_SETFL 4
#define S_IFMT 0170000
#define S_IFCHR 0020000
#define S_IFSOCK 0140000

#define ENOENT 2
#define EINTR 4
#define ENFILE 23
#define ENODEV 19
#define EINVAL 22
#define ENAMETOOLONG 36

// What the launcher sends first on each connection. Must match PadLayout.kt.
#define PAD_MAGIC 0x50414d4fu  // "OMAP"
#define PAD_VERSION 1
struct absinfo {
    i32 value, minimum, maximum, fuzz, flat, resolution;
};
struct pad {
    u32 magic, version;
    u16 bustype, vendor, product, id_version;  // struct input_id
    char name[128];
    u8 ev[8];     // EVIOCGBIT(0): the event types
    u8 key[96];   // EVIOCGBIT(EV_KEY), KEY_MAX + 1 bits
    u8 abs[8];    // EVIOCGBIT(EV_ABS), ABS_MAX + 1 bits
    struct absinfo absinfo[64];  // EVIOCGABS, values as of the connection
    u8 keys[96];  // EVIOCGKEY, buttons held as of the connection
};
_Static_assert(sizeof(struct pad) == 1888, "struct pad must match PadLayout.kt");

#define EV_KEY 0x01
#define EV_REL 0x02
#define EV_ABS 0x03
#define EV_MSC 0x04
#define EV_SW 0x05
#define EV_LED 0x11
#define EV_SND 0x12
#define EV_FF 0x15

extern int *__errno_location(void);
extern void *dlsym(void *handle, const char *name) __attribute__((weak));

static long sys(long nr, long a, long b, long c) {
    register long x8 __asm__("x8") = nr;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2) : "memory", "cc");
    return x0;
}

static long sys4(long nr, long a, long b, long c, long d) {
    register long x8 __asm__("x8") = nr;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory", "cc");
    return x0;
}

// What libc's functions return: -1 with errno set for a -errno from the kernel.
static long libc_result(long r) {
    if (r < 0 && r > -4096) {
        *__errno_location() = (int)-r;
        return -1;
    }
    return r;
}

static long fail(int error) {
    *__errno_location() = error;
    return -1;
}

static void copy(void *to, const void *from, u64 n) {
    u8 *t = to;
    const u8 *f = from;
    for (u64 i = 0; i < n; i++) t[i] = f[i];
}

// The functions behind this library, looked up when it's loaded (dlsym is not for signal handlers,
// close is). Without one, the plain syscall.
static int (*next_open)(const char *, int, u32);
static int (*next_open64)(const char *, int, u32);
static int (*next_openat)(int, const char *, int, u32);
static int (*next_openat64)(int, const char *, int, u32);
static int (*next_open_2)(const char *, int);
static int (*next_open64_2)(const char *, int);
static int (*next_openat_2)(int, const char *, int);
static int (*next_openat64_2)(int, const char *, int);
static int (*next_ioctl)(int, unsigned long, void *);
static int (*next_close)(int);
static int (*next_fstat)(int, void *);
static int (*next_fstat64)(int, void *);
static int (*next_fxstat)(int, int, void *);
static int (*next_fxstat64)(int, int, void *);
static int (*next_stat)(const char *, void *);
static int (*next_stat64)(const char *, void *);
static int (*next_lstat)(const char *, void *);
static int (*next_lstat64)(const char *, void *);
static int (*next_fstatat)(int, const char *, void *, int);
static int (*next_fstatat64)(int, const char *, void *, int);
static int (*next_xstat)(int, const char *, void *);
static int (*next_xstat64)(int, const char *, void *);
static int (*next_lxstat)(int, const char *, void *);
static int (*next_lxstat64)(int, const char *, void *);
static int (*next_fxstatat)(int, int, const char *, void *, int);
static int (*next_fxstatat64)(int, int, const char *, void *, int);

__attribute__((constructor)) static void find_next_functions(void) {
    void *const next = (void *)-1L;  // RTLD_NEXT
    if (!dlsym) return;
    next_open = dlsym(next, "open");
    next_open64 = dlsym(next, "open64");
    next_openat = dlsym(next, "openat");
    next_openat64 = dlsym(next, "openat64");
    next_open_2 = dlsym(next, "__open_2");
    next_open64_2 = dlsym(next, "__open64_2");
    next_openat_2 = dlsym(next, "__openat_2");
    next_openat64_2 = dlsym(next, "__openat64_2");
    next_ioctl = dlsym(next, "ioctl");
    next_close = dlsym(next, "close");
    next_fstat = dlsym(next, "fstat");
    next_fstat64 = dlsym(next, "fstat64");
    next_fxstat = dlsym(next, "__fxstat");
    next_fxstat64 = dlsym(next, "__fxstat64");
    next_stat = dlsym(next, "stat");
    next_stat64 = dlsym(next, "stat64");
    next_lstat = dlsym(next, "lstat");
    next_lstat64 = dlsym(next, "lstat64");
    next_fstatat = dlsym(next, "fstatat");
    next_fstatat64 = dlsym(next, "fstatat64");
    next_xstat = dlsym(next, "__xstat");
    next_xstat64 = dlsym(next, "__xstat64");
    next_lxstat = dlsym(next, "__lxstat");
    next_lxstat64 = dlsym(next, "__lxstat64");
    next_fxstatat = dlsym(next, "__fxstatat");
    next_fxstatat64 = dlsym(next, "__fxstatat64");
}

// Controllers open in this process, by descriptor. A slot is FREE, FILLING while open() reads its
// description, or READY.
#define SLOTS 16
#define FREE 0
#define FILLING 1
#define READY 2
static struct slot {
    int state;
    int fd;
    u32 minor;  // 64 + N for eventN, as evdev numbers them
    struct pad pad;
} slots[SLOTS];
static int in_use;  // slots not FREE: while 0, nothing below looks at the table

static struct slot *claim(void) {
    for (int i = 0; i < SLOTS; i++) {
        int expected = FREE;
        if (__atomic_compare_exchange_n(&slots[i].state, &expected, FILLING, 0, __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) {
            __atomic_add_fetch(&in_use, 1, __ATOMIC_RELAXED);
            return &slots[i];
        }
    }
    return 0;
}

static void release(struct slot *s) {
    __atomic_store_n(&s->state, FREE, __ATOMIC_RELEASE);
    __atomic_sub_fetch(&in_use, 1, __ATOMIC_RELAXED);
}

static struct slot *find(int fd) {
    if (fd < 0 || !__atomic_load_n(&in_use, __ATOMIC_RELAXED)) return 0;
    for (int i = 0; i < SLOTS; i++)
        if (__atomic_load_n(&slots[i].state, __ATOMIC_ACQUIRE) == READY && slots[i].fd == fd) return &slots[i];
    return 0;
}

// The struct stat of arm64 (asm-generic), as far as it's needed here.
struct kstat {
    u64 st_dev, st_ino;
    u32 st_mode, st_nlink, st_uid, st_gid;
    u64 st_rdev;
    u64 rest[12];
};

// Still the socket it was opened as: a descriptor closed some way this library doesn't see (a
// raw syscall, close_range) and then reused must not be taken for a controller.
static struct slot *find_live(int fd) {
    struct slot *s = find(fd);
    if (!s) return 0;
    struct kstat st;
    if (sys(SYS_fstat, fd, (long)&st, 0) < 0 || (st.st_mode & S_IFMT) != S_IFSOCK) {
        release(s);
        return 0;
    }
    return s;
}

// "/dev/input/event" and digits: the number, or -1 for any other path.
static int event_number(const char *path) {
    static const char prefix[] = "/dev/input/event";
    if (!path) return -1;
    for (int i = 0; prefix[i]; i++)
        if (path[i] != prefix[i]) return -1;
    path += sizeof(prefix) - 1;
    if (!*path) return -1;
    int n = 0;
    for (; *path; path++) {
        if (*path < '0' || *path > '9' || n > 100000) return -1;
        n = n * 10 + (*path - '0');
    }
    return n;
}

static int open_pad(const char *path, int n, int flags) {
    struct {
        u16 family;
        char path[108];
    } address = {AF_UNIX, {0}};
    u64 length = 0;
    while (path[length]) length++;
    if (length >= sizeof(address.path)) return (int)fail(ENAMETOOLONG);
    copy(address.path, path, length + 1);

    struct slot *s = claim();
    if (!s) return (int)fail(ENFILE);
    long fd = sys(SYS_socket, AF_UNIX, SOCK_STREAM | O_CLOEXEC, 0);
    if (fd < 0) {
        release(s);
        return (int)libc_result(fd);
    }
    long r;
    do r = sys(SYS_connect, fd, (long)&address, 2 + length + 1);
    while (r == -EINTR);
    // No one listening: the launcher is gone, and so is the controller.
    if (r < 0) {
        sys(SYS_close, fd, 0, 0);
        release(s);
        return (int)fail(r == -ENOENT ? ENOENT : ENODEV);
    }
    u8 *to = (u8 *)&s->pad;
    u64 got = 0;
    while (got < sizeof(s->pad)) {
        r = sys(SYS_read, fd, (long)(to + got), sizeof(s->pad) - got);
        if (r == -EINTR) continue;
        if (r <= 0) break;
        got += (u64)r;
    }
    if (got < sizeof(s->pad) || s->pad.magic != PAD_MAGIC || s->pad.version != PAD_VERSION) {
        sys(SYS_close, fd, 0, 0);
        release(s);
        return (int)fail(ENODEV);
    }
    s->pad.name[sizeof(s->pad.name) - 1] = 0;
    if (!(flags & O_CLOEXEC)) sys(SYS_fcntl, fd, F_SETFD, 0);
    if (flags & O_NONBLOCK) sys(SYS_fcntl, fd, F_SETFL, sys(SYS_fcntl, fd, F_GETFL, 0) | O_NONBLOCK);
    s->fd = (int)fd;
    s->minor = 64 + (u32)n;
    __atomic_store_n(&s->state, READY, __ATOMIC_RELEASE);
    return (int)fd;
}

#define EXPORT __attribute__((visibility("default")))

// libc declares these with "..." for the mode; on arm64 that argument arrives the same way.
// The controllers' path is absolute, so openat's dirfd doesn't matter for them.
#define OPEN_PAD(path, flags)                        \
    do {                                             \
        int n = event_number(path);                  \
        if (n >= 0) return open_pad(path, n, flags); \
    } while (0)

EXPORT int open(const char *path, int flags, u32 mode) {
    OPEN_PAD(path, flags);
    if (next_open) return next_open(path, flags, mode);
    return (int)libc_result(sys4(SYS_openat, AT_FDCWD, (long)path, flags, mode));
}
EXPORT int open64(const char *path, int flags, u32 mode) {
    OPEN_PAD(path, flags);
    if (next_open64) return next_open64(path, flags, mode);
    return (int)libc_result(sys4(SYS_openat, AT_FDCWD, (long)path, flags, mode));
}
EXPORT int openat(int dirfd, const char *path, int flags, u32 mode) {
    OPEN_PAD(path, flags);
    if (next_openat) return next_openat(dirfd, path, flags, mode);
    return (int)libc_result(sys4(SYS_openat, dirfd, (long)path, flags, mode));
}
EXPORT int openat64(int dirfd, const char *path, int flags, u32 mode) {
    OPEN_PAD(path, flags);
    if (next_openat64) return next_openat64(dirfd, path, flags, mode);
    return (int)libc_result(sys4(SYS_openat, dirfd, (long)path, flags, mode));
}
// What _FORTIFY_SOURCE builds call when the flags aren't known at compile time; never O_CREAT.
EXPORT int __open_2(const char *path, int flags) {
    OPEN_PAD(path, flags);
    if (next_open_2) return next_open_2(path, flags);
    return (int)libc_result(sys4(SYS_openat, AT_FDCWD, (long)path, flags, 0));
}
EXPORT int __open64_2(const char *path, int flags) {
    OPEN_PAD(path, flags);
    if (next_open64_2) return next_open64_2(path, flags);
    return (int)libc_result(sys4(SYS_openat, AT_FDCWD, (long)path, flags, 0));
}
EXPORT int __openat_2(int dirfd, const char *path, int flags) {
    OPEN_PAD(path, flags);
    if (next_openat_2) return next_openat_2(dirfd, path, flags);
    return (int)libc_result(sys4(SYS_openat, dirfd, (long)path, flags, 0));
}
EXPORT int __openat64_2(int dirfd, const char *path, int flags) {
    OPEN_PAD(path, flags);
    if (next_openat64_2) return next_openat64_2(dirfd, path, flags);
    return (int)libc_result(sys4(SYS_openat, dirfd, (long)path, flags, 0));
}

EXPORT int close(int fd) {
    struct slot *s = find(fd);
    if (s) release(s);
    if (next_close) return next_close(fd);
    return (int)libc_result(sys(SYS_close, fd, 0, 0));
}

// Bits and strings go out as the kernel copies them: as much as fits, returning the length copied.
static long copy_out(void *arg, u32 size, const void *from, u32 length) {
    u32 n = size < length ? size : length;
    copy(arg, from, n);
    return n;
}

static const u8 no_bits[16];

#define IOC_NR(r) ((r) & 0xff)
#define IOC_TYPE(r) (((r) >> 8) & 0xff)
#define IOC_SIZE(r) (((r) >> 16) & 0x3fff)
#define IOC_DIR(r) ((r) >> 30)
#define IOC_WRITE 1
#define IOC_READ 2

// evdev's ioctls (linux/input.h), answered from the description.
static long evdev(struct slot *s, unsigned long request, void *arg) {
    const struct pad *p = &s->pad;
    const u32 nr = IOC_NR(request), size = IOC_SIZE(request);
    if (IOC_DIR(request) == IOC_READ) {
        if (nr >= 0x20 && nr < 0x40) {  // EVIOCGBIT(type)
            switch (nr - 0x20) {
            case 0: return copy_out(arg, size, p->ev, sizeof(p->ev));
            case EV_KEY: return copy_out(arg, size, p->key, sizeof(p->key));
            case EV_ABS: return copy_out(arg, size, p->abs, sizeof(p->abs));
            case EV_FF: return copy_out(arg, size, no_bits, 16);
            case EV_REL:
            case EV_MSC:
            case EV_SW:
            case EV_LED:
            case EV_SND: return copy_out(arg, size, no_bits, 8);
            default: return fail(EINVAL);
            }
        }
        if (nr >= 0x40 && nr < 0x80) {  // EVIOCGABS(axis)
            copy_out(arg, size, &p->absinfo[nr - 0x40], sizeof(struct absinfo));
            return 0;
        }
        switch (nr) {
        case 0x01:  // EVIOCGVERSION
            if (size < 4) return fail(EINVAL);
            *(i32 *)arg = 0x010001;
            return 0;
        case 0x02:  // EVIOCGID
            if (size < 8) return fail(EINVAL);
            copy(arg, &p->bustype, 8);
            return 0;
        case 0x06: {  // EVIOCGNAME
            u32 length = 0;
            while (p->name[length]) length++;
            return copy_out(arg, size, p->name, length + 1);
        }
        case 0x07:  // EVIOCGPHYS
        case 0x08:  // EVIOCGUNIQ
            return fail(ENOENT);
        case 0x09:  // EVIOCGPROP
        case 0x19:  // EVIOCGLED
        case 0x1a:  // EVIOCGSND
        case 0x1b:  // EVIOCGSW
            return copy_out(arg, size, no_bits, 8);
        case 0x18:  // EVIOCGKEY
            return copy_out(arg, size, p->keys, sizeof(p->keys));
        case 0x84:  // EVIOCGEFFECTS: no force feedback
            if (size < 4) return fail(EINVAL);
            *(i32 *)arg = 0;
            return 0;
        }
        return fail(EINVAL);
    }
    switch (nr) {
    case 0x90:  // EVIOCGRAB
    case 0x91:  // EVIOCREVOKE
    case 0x92:  // EVIOCGMASK
    case 0x93:  // EVIOCSMASK
        return 0;
    }
    // EVIOCSABS: the launcher owns the ranges, like a driver; taken and not kept.
    if (IOC_DIR(request) == IOC_WRITE && nr >= 0xc0) return 0;
    // EVIOCSCLOCKID (timestamps stay CLOCK_REALTIME, evdev's default), EVIOCSFF, EVIOCRMFF,
    // EVIOCSREP, EVIOCSKEYCODE and the rest.
    return fail(EINVAL);
}

EXPORT int ioctl(int fd, unsigned long request, void *arg) {
    if (IOC_TYPE(request) == 'E') {
        struct slot *s = find_live(fd);
        if (s) return (int)evdev(s, request, arg);
    }
    // Everything else on a controller is the socket's own, like FIONREAD; joystick ioctls fail
    // there with ENOTTY, as on a real evdev node.
    if (next_ioctl) return next_ioctl(fd, request, arg);
    return (int)libc_result(sys(SYS_ioctl, fd, (long)request, (long)arg));
}

// A controller is a character device to whoever looks: SDL and RetroArch keep devices apart by
// st_rdev, and skip one whose number they already have (a socket's, 0, for every controller).
static void char_device(struct kstat *st, u32 minor) {
    st->st_mode = (st->st_mode & ~S_IFMT) | S_IFCHR;
    st->st_rdev = (13u << 8) | (minor & 0xff) | ((u64)(minor & ~0xffu) << 12);  // makedev(13, minor)
}

static int as_device(int fd, struct kstat *st, int r) {
    if (r != 0) return r;
    struct slot *s = find(fd);
    if (s && (st->st_mode & S_IFMT) == S_IFSOCK) char_device(st, s->minor);
    return r;
}

// By path, before anything opens it: /dev/input/eventN, while it's still the launcher's socket.
static int path_as_device(const char *path, struct kstat *st, int r) {
    if (r != 0) return r;
    int n = event_number(path);
    if (n >= 0 && (st->st_mode & S_IFMT) == S_IFSOCK) char_device(st, 64 + (u32)n);
    return r;
}

static int stat_at(int dirfd, const char *path, struct kstat *st, int flags) {
    return (int)libc_result(sys4(SYS_newfstatat, dirfd, (long)path, (long)st, flags));
}

EXPORT int fstat(int fd, struct kstat *st) {
    return as_device(fd, st, next_fstat ? next_fstat(fd, st) : (int)libc_result(sys(SYS_fstat, fd, (long)st, 0)));
}
EXPORT int fstat64(int fd, struct kstat *st) {
    return as_device(fd, st, next_fstat64 ? next_fstat64(fd, st) : (int)libc_result(sys(SYS_fstat, fd, (long)st, 0)));
}
EXPORT int __fxstat(int v, int fd, struct kstat *st) {
    return as_device(fd, st, next_fxstat ? next_fxstat(v, fd, st) : (int)libc_result(sys(SYS_fstat, fd, (long)st, 0)));
}
EXPORT int __fxstat64(int v, int fd, struct kstat *st) {
    return as_device(fd, st, next_fxstat64 ? next_fxstat64(v, fd, st) : (int)libc_result(sys(SYS_fstat, fd, (long)st, 0)));
}

EXPORT int stat(const char *path, struct kstat *st) {
    return path_as_device(path, st, next_stat ? next_stat(path, st) : stat_at(AT_FDCWD, path, st, 0));
}
EXPORT int stat64(const char *path, struct kstat *st) {
    return path_as_device(path, st, next_stat64 ? next_stat64(path, st) : stat_at(AT_FDCWD, path, st, 0));
}
EXPORT int lstat(const char *path, struct kstat *st) {
    return path_as_device(path, st, next_lstat ? next_lstat(path, st) : stat_at(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW));
}
EXPORT int lstat64(const char *path, struct kstat *st) {
    return path_as_device(path, st, next_lstat64 ? next_lstat64(path, st) : stat_at(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW));
}
EXPORT int fstatat(int dirfd, const char *path, struct kstat *st, int flags) {
    return path_as_device(path, st, next_fstatat ? next_fstatat(dirfd, path, st, flags) : stat_at(dirfd, path, st, flags));
}
EXPORT int fstatat64(int dirfd, const char *path, struct kstat *st, int flags) {
    return path_as_device(path, st, next_fstatat64 ? next_fstatat64(dirfd, path, st, flags) : stat_at(dirfd, path, st, flags));
}
// What programs built against glibc before 2.33 call.
EXPORT int __xstat(int v, const char *path, struct kstat *st) {
    return path_as_device(path, st, next_xstat ? next_xstat(v, path, st) : stat_at(AT_FDCWD, path, st, 0));
}
EXPORT int __xstat64(int v, const char *path, struct kstat *st) {
    return path_as_device(path, st, next_xstat64 ? next_xstat64(v, path, st) : stat_at(AT_FDCWD, path, st, 0));
}
EXPORT int __lxstat(int v, const char *path, struct kstat *st) {
    return path_as_device(path, st, next_lxstat ? next_lxstat(v, path, st) : stat_at(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW));
}
EXPORT int __lxstat64(int v, const char *path, struct kstat *st) {
    return path_as_device(path, st, next_lxstat64 ? next_lxstat64(v, path, st) : stat_at(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW));
}
EXPORT int __fxstatat(int v, int dirfd, const char *path, struct kstat *st, int flags) {
    return path_as_device(path, st, next_fxstatat ? next_fxstatat(v, dirfd, path, st, flags) : stat_at(dirfd, path, st, flags));
}
EXPORT int __fxstatat64(int v, int dirfd, const char *path, struct kstat *st, int flags) {
    return path_as_device(path, st, next_fxstatat64 ? next_fxstatat64(v, dirfd, path, st, flags) : stat_at(dirfd, path, st, flags));
}
