// The fast lane beside proot. Preloaded into guest programs with LD_PRELOAD, it answers libc's
// stat, statx, access, readlink, read-only open, fstat, close, realpath, getcwd, getxattr and
// listxattr calls itself when the path stays plainly inside the rootfs: it asks proot for the rootfs, the bindings and the fake ids (see
// src/tracee/fastpath.h in the patched proot), resolves the path the way proot does, and makes
// the real syscalls from one fixed address that proot's seccomp filter lets pass untraced.
// Anything it is not sure about is made as an ordinary syscall, which proot traps and handles as
// before.
//
// Freestanding: no libc headers and no libc calls except __errno_location, dlsym to hand calls
// back to libc (open and close are cancellation points, realpath and getcwd are libc's own work)
// and malloc for a realpath without a buffer. So it loads into any glibc program.
// arm64 only.
// Build: clang --target=aarch64-linux-gnu -O2 -fPIC -shared -nostdlib -ffreestanding \
//        -mno-outline-atomics fastpath.c -o libfastpath.so

typedef unsigned long u64;
typedef long i64;
typedef unsigned int u32;
typedef unsigned short u16;

// Must match src/tracee/fastpath.h in the patched proot.
#define FASTPATH_ADDRESS 0x2fc0000000UL
#define PR_PROOT_FASTPATH 0x50524f46
#define FASTPATH_VERSION 2
#define FASTPATH_HOST_OVERLAP 1
struct fastpath_info {
    u32 version, flags, uid, gid, fake_uid, fake_gid, length, reserved;
    char paths[];
};
#define FASTPATH_INFO 0
#define FASTPATH_CWD 1
struct fastpath_state {
    u32 paths;        // counts symlinks made and directories or symlinks renamed, by any process
    u32 config;       // counts changes of some process' fake ids, root or bindings
    u32 cwds;         // counts changes of some process' working directory
    u32 thread_cwds;  // not zero once some thread has a working directory of its own
};

#define SYS_getxattr 8
#define SYS_lgetxattr 9
#define SYS_listxattr 11
#define SYS_llistxattr 12
#define SYS_getcwd 17
#define SYS_faccessat 48
#define SYS_openat 56
#define SYS_close 57
#define SYS_readlinkat 78
#define SYS_newfstatat 79
#define SYS_fstat 80
#define SYS_prctl 167
#define SYS_getpid 172
#define SYS_getuid 174
#define SYS_munmap 215
#define SYS_mmap 222
#define SYS_mprotect 226
#define SYS_statx 291
#define SYS_faccessat2 439

#define AT_FDCWD (-100)
#define AT_SYMLINK_NOFOLLOW 0x100
#define AT_NO_AUTOMOUNT 0x800
#define AT_EMPTY_PATH 0x1000
#define AT_STATX_SYNC_TYPE 0x6000
// Open flags as arm64 has them.
#define O_ACCMODE 03
#define O_NOCTTY 0400
#define O_NONBLOCK 04000
#define O_DIRECTORY 040000
#define O_NOFOLLOW 0100000
#define O_LARGEFILE 0400000
#define O_CLOEXEC 02000000
#define O_PATH 010000000
#define ENOENT 2
#define EACCES 13
#define ENOTDIR 20
#define EINVAL 22
#define ERANGE 34
#define ENOMEM 12
#define S_IFMT 0170000
#define S_IFCHR 0020000
#define S_IFDIR 0040000
#define S_IFREG 0100000
#define S_IFLNK 0120000
#define STATX_UID 0x8
#define STATX_GID 0x10

// Longer guest paths, symlink targets and rootfs paths go the slow way. This bounds the stack a
// call needs to about 4 KB.
#define GUEST_MAX 1024
#define ROOT_MAX 255
// proot reports ENAMETOOLONG for long names earlier than the kernel does.
#define NAME_LIMIT 200
// Symlinks followed for one path before proot is asked; it counts them its own way for ELOOP.
#define LINK_LIMIT 8

// The kernel's struct stat on arm64, which is also glibc's struct stat and struct stat64.
struct kstat {
    u64 st_dev, st_ino;
    u32 st_mode, st_nlink, st_uid, st_gid;
    u64 st_rdev, pad1;
    i64 st_size;
    int st_blksize, pad2;
    i64 st_blocks, atime, atime_ns, mtime, mtime_ns, ctime, ctime_ns;
    u32 unused[2];
};

extern int *__errno_location(void);

// slow(): an ordinary syscall, which proot traps when it handles that syscall.
// fast(), fast5(): the same syscall from the fixed address, which proot's filter lets through untraced.
long slow(long nr, long a, long b, long c, long d, long e, long f) __attribute__((visibility("hidden")));
long fast5(long nr, long a, long b, long c, long d, long e) __attribute__((visibility("hidden")));
#define fast(nr, a, b, c, d) fast5(nr, a, b, c, d, 0)
__asm__(
    ".text\n"
    ".hidden slow\n.type slow,%function\n"
    "slow: mov x8, x0; mov x0, x1; mov x1, x2; mov x2, x3; mov x3, x4; mov x4, x5; mov x5, x6\n"
    "      svc 0; ret\n"
    ".hidden fast5\n.type fast5,%function\n"
    "fast5: mov x8, x0; mov x0, x1; mov x1, x2; mov x2, x3; mov x3, x4; mov x4, x5\n"
    "      ldr x9, =0x2fc0000000; br x9\n"  // "svc 0; ret" lives there and returns to our caller
    ".ltorg\n");

static u32 length_of(const char *s) {
    u32 n = 0;
    while (s[n]) n++;
    return n;
}

static int same(const char *a, const char *b, u32 n) {
    for (u32 i = 0; i < n; i++)
        if (a[i] != b[i]) return 0;
    return 1;
}

// ---- What proot told us --------------------------------------------------------------------

// One answer to the handshake. It changes when this process changes its fake ids, its root or
// its bindings (proot emulates chroot and mount);
// then the next answer goes into the next snapshot, so that a thread in the middle of a call
// keeps reading a consistent one. A process that uses them all up goes the slow way from then on.
#define SNAPSHOTS 6
#define INFO_SIZE 4096
struct snapshot {
    const char *root;   // host path of the rootfs
    u32 root_length;
    const char *binds;  // guest paths, each NUL-terminated, ended by an empty one
    u64 root_dev;       // the device the rootfs is on, 0 when unknown
    u32 flags, real_uid, real_gid, fake_uid, fake_gid;
    u32 info_length;
    char info[INFO_SIZE];
};
static struct snapshot snapshots[SNAPSHOTS];
static char scratch[INFO_SIZE];  // only touched by the thread that holds BUSY

enum { UNSET, BUSY, ON, OFF };
static int state;
static int current;      // index of the snapshot in use
static u32 seen_config;  // shared->config when that snapshot was last confirmed
static const volatile struct fastpath_state *shared;

static const char *after_string(const char *s) { return s + length_of(s) + 1; }

static long handshake(char *buffer) {
    long n = slow(SYS_prctl, PR_PROOT_FASTPATH, (long)buffer, INFO_SIZE, FASTPATH_INFO, 0, 0);
    const struct fastpath_info *info = (const struct fastpath_info *)buffer;
    if (n < (long)sizeof *info || n > INFO_SIZE || info->version != FASTPATH_VERSION) return -1;
    return n;
}

static void parse(struct snapshot *s, long n) {
    const struct fastpath_info *info = (const struct fastpath_info *)s->info;
    s->info_length = n;
    s->root = info->paths;
    s->root_length = length_of(s->root);
    s->binds = after_string(after_string(s->root));
    s->flags = info->flags;
    s->real_uid = info->uid;
    s->real_gid = info->gid;
    s->fake_uid = info->fake_uid;
    s->fake_gid = info->fake_gid;
}

// The device the rootfs is on, for close(). Needs the fast lane's syscall instruction.
static void find_root_dev(struct snapshot *s) {
    struct kstat st;
    s->root_dev = fast(SYS_newfstatat, AT_FDCWD, (long)s->root, (long)&st, 0) ? 0 : st.st_dev;
}

static int set_up(void) {
    struct snapshot *s = &snapshots[0];
    long n = handshake(s->info);
    if (n < 0) return 0;
    parse(s, n);
    if (s->root_length > ROOT_MAX) return 0;

    // The syscall instruction at the fixed address. If something else is mapped there, do without.
    const long MAP_FIXED_NOREPLACE = 0x100000, MAP_SHARED = 1, MAP_PRIVATE = 2, MAP_ANONYMOUS = 0x20;
    long at = slow(SYS_mmap, FASTPATH_ADDRESS, 4096, 1 | 2, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE,
                   -1, 0);
    if (at != (long)FASTPATH_ADDRESS) {
        if (at > 0) slow(SYS_munmap, at, 4096, 0, 0, 0, 0);
        return 0;
    }
    u32 *code = (u32 *)FASTPATH_ADDRESS;
    code[0] = 0xd4000001;  // svc 0
    code[1] = 0xd65f03c0;  // ret
    // The kernel flushes the instruction cache when the page becomes executable.
    if (slow(SYS_mprotect, FASTPATH_ADDRESS, 4096, 1 | 4, 0, 0, 0)) return 0;

    // Untraced, getuid shows the real id; if proot still answers it, the filter has no rule for us.
    if (fast(SYS_getuid, 0, 0, 0, 0) != (long)s->real_uid || s->fake_uid == s->real_uid) return 0;

    // The counters proot keeps for every process on this rootfs. Its path is a host path.
    long fd = fast(SYS_openat, AT_FDCWD, (long)after_string(s->root), O_CLOEXEC, 0);
    if (fd < 0) return 0;
    long map = slow(SYS_mmap, 0, 4096, 1, MAP_SHARED, fd, 0);
    fast(SYS_close, fd, 0, 0, 0);
    if (map < 0 && map > -4096) return 0;
    shared = (const struct fastpath_state *)map;
    // Read after the handshake: a change in between is noticed late at worst by one call, like a
    // call made just before it.
    seen_config = shared->config;
    find_root_dev(s);
    return 1;
}

// The handshake may answer differently now: ask again.
static int refresh(void) {
    u32 config = shared->config;
    long n = handshake(scratch);
    if (n < 0) return 0;
    struct snapshot *s = &snapshots[current];
    if ((u32)n != s->info_length || !same(scratch, s->info, n)) {
        if (current + 1 == SNAPSHOTS) return 0;
        s++;
        for (long i = 0; i < n; i++) s->info[i] = scratch[i];
        parse(s, n);
        if (s->root_length > ROOT_MAX) return 0;
        find_root_dev(s);
        __atomic_store_n(&current, current + 1, __ATOMIC_RELEASE);
    }
    __atomic_store_n(&seen_config, config, __ATOMIC_RELEASE);
    return 1;
}

// The snapshot to work with, or 0 when this call has to go the slow way.
static const struct snapshot *ready(int *index) {
    int s = __atomic_load_n(&state, __ATOMIC_ACQUIRE);
    if (s == ON && shared->config == __atomic_load_n(&seen_config, __ATOMIC_ACQUIRE)) {
        *index = __atomic_load_n(&current, __ATOMIC_ACQUIRE);
        return &snapshots[*index];
    }
    // Off, or another thread is at it: this call goes the slow way.
    if (s != ON && s != UNSET) return 0;
    int was = s;
    if (!__atomic_compare_exchange_n(&state, &s, BUSY, 0, __ATOMIC_ACQUIRE, __ATOMIC_ACQUIRE)) return 0;
    s = (was == UNSET ? set_up() : refresh()) ? ON : OFF;
    __atomic_store_n(&state, s, __ATOMIC_RELEASE);
    if (s != ON) return 0;
    *index = current;
    return &snapshots[*index];
}

// ---- The working directory -----------------------------------------------------------------

// proot emulates chdir: the kernel's working directory never changes, only proot knows the
// guest's. So it is asked, and asked again after shared->cwds says that some process changed
// its own. A child made by vfork shares this memory but has its own working directory, hence
// the pid. `cwd_lock` is odd while a thread writes.
static u32 cwd_lock, cwd_count, cwd_length;
static long cwd_pid;
static char cwd[GUEST_MAX + 1];

// Copies the working directory, a guest path, to out and returns its length, or 0.
static u32 working_directory(char *out) {
    if (shared->thread_cwds) return 0;
    const u32 count = shared->cwds;
    const long pid = fast(SYS_getpid, 0, 0, 0, 0);
    u32 lock = __atomic_load_n(&cwd_lock, __ATOMIC_ACQUIRE);
    if (lock & 1) return 0;
    if (cwd_length && cwd_count == count && cwd_pid == pid) {
        const u32 n = cwd_length;
        if (n > GUEST_MAX) return 0;
        for (u32 i = 0; i < n; i++) out[i] = cwd[i];
        // Nobody wrote meanwhile?
        return __atomic_load_n(&cwd_lock, __ATOMIC_ACQUIRE) == lock ? n : 0;
    }
    if (!__atomic_compare_exchange_n(&cwd_lock, &lock, lock + 1, 0, __ATOMIC_ACQUIRE, __ATOMIC_ACQUIRE)) return 0;
    long n = slow(SYS_prctl, PR_PROOT_FASTPATH, (long)cwd, sizeof cwd, FASTPATH_CWD, 0, 0);
    n = n > 1 && cwd[0] == '/' ? n - 1 : 0;
    cwd_length = n;
    cwd_count = count;
    cwd_pid = pid;
    for (long i = 0; i < n; i++) out[i] = cwd[i];
    __atomic_store_n(&cwd_lock, lock + 2, __ATOMIC_RELEASE);
    return n;
}

// ---- Directories known to be real ----------------------------------------------------------

// Guest directories that are real directories of the rootfs all the way down: no symlink among
// their components, and not in a binding. Only a 64-bit hash of the path is kept, mixed with
// the snapshot and with shared->paths, so everything is forgotten when proot counts a new symlink
// or a renamed directory.
#define SLOTS 4096
static u64 slots[SLOTS];

#define HASH_START 0xcbf29ce484222325UL
static u64 hash_more(u64 h, const char *s, u32 n) {
    for (u32 i = 0; i < n; i++) h = (h ^ (unsigned char)s[i]) * 0x100000001b3UL;
    return h;
}

static u64 key_of(u64 h, u32 paths, int snapshot) {
    h ^= ((u64)paths << 32) ^ ((u64)(snapshot + 1) * 0x9e3779b97f4a7c15UL);
    h ^= h >> 33;
    h *= 0xff51afd7ed558ccdUL;
    h ^= h >> 33;
    h *= 0xc4ceb9fe1a85ec53UL;
    h ^= h >> 33;
    return h | 1;
}

static int known_dir(u64 key) {
    u32 i = (key >> 16) % SLOTS;
    return __atomic_load_n(&slots[i], __ATOMIC_RELAXED) == key ||
           __atomic_load_n(&slots[i ^ 1], __ATOMIC_RELAXED) == key;
}

static void remember_dir(u64 key) {
    u32 i = (key >> 16) % SLOTS;
    if (__atomic_load_n(&slots[i], __ATOMIC_RELAXED) && !(key >> 63)) i ^= 1;
    __atomic_store_n(&slots[i], key, __ATOMIC_RELAXED);
}

// ---- Resolving a path ----------------------------------------------------------------------

enum { OUTSIDE, INSIDE, ABOVE };

// Where a guest path is relative to the bindings: in one, a parent of one (proot makes those up
// when the rootfs lacks them, so a missing one is proot's to answer), or neither. "/" is the
// parent of some.
static int binding_of(const struct snapshot *s, const char *guest, u32 n) {
    if (n == 0) return ABOVE;
    for (const char *b = s->binds; *b; b = after_string(b)) {
        u32 bl = length_of(b);
        if (bl <= n) {
            if (same(guest, b, bl) && (bl == n || guest[bl] == '/')) return INSIDE;
        } else if (same(guest, b, n) && b[n] == '/')
            return ABOVE;
    }
    return OUTSIDE;
}

// Names proot gives a meaning to: the files behind faked hard links and its own bookkeeping.
static int reserved_name(const char *name, u32 n) {
    return (n >= 5 && same(name, ".l2s.", 5)) || (n >= 6 && same(name, ".proot", 6));
}

// proot gives the guest's root user access wherever it walks: a component that isn't the last
// one the caller named gets its owner's missing rw (and x for a directory) for the length of the
// syscall, see override_permissions() in src/extension/fake_id0. When such a file or directory
// is what the call is about, the guest sees the changed mode, so those are proot's to answer.
static int owner_has_access(const struct kstat *st) {
    const u32 need = (st->st_mode & S_IFMT) == S_IFDIR ? 0700 : 0600;
    return (st->st_mode & need) == need;
}

static long lstat_host(const char *host, struct kstat *st) {
    return fast(SYS_newfstatat, AT_FDCWD, (long)host, (long)st, AT_SYMLINK_NOFOLLOW);
}

// "/proc/thread-self/fd/<fd>": the kernel's name for what fd is open on.
static void proc_fd(char *out, int fd) {
    const char *prefix = "/proc/thread-self/fd/";
    u32 n = 0;
    while (prefix[n]) out[n] = prefix[n], n++;
    char digits[12];
    u32 d = 0;
    do digits[d++] = '0' + fd % 10;
    while (fd /= 10);
    while (d) out[n++] = digits[--d];
    out[n] = 0;
}

enum { SLOW, ANSWERED, FOUND };

// What a path leads to.
struct found {
    const struct snapshot *s;
    struct kstat st;                      // lstat of it
    char host[ROOT_MAX + GUEST_MAX + 8];  // its host path
    char link[ROOT_MAX + GUEST_MAX + 8];  // when it is a symlink: where that points
    u32 link_length;
    u32 links;  // symlinks followed on the way
    u32 base_link;  // one of them in the working directory's own path (or dirfd's)
    u32 dir_suffix;  // the path ends in a symlink's target that ends in "/", "/." or "/.."
};

// Returns FOUND with *f filled, ANSWERED with *result set to the -errno every syscall on this
// path ends with, or SLOW to go the slow way. With `follow`, a symlink at the end is followed.
//
// This walks the path like proot's canonicalize() (src/path/canon.c): component by component
// from the guest's root, following symlinks as guest paths, with each directory on the way
// checked to be a real one. A relative path is walked from the root too, behind the working
// directory or the path dirfd is open on, as proot does it. `guest` is the canonical guest path so far
// and sits right behind the rootfs in `host`, so `host` is always what the kernel is asked about.
static int resolve(int dirfd, const char *path, int follow, struct found *f, long *result) {
    if (!path || !path[0]) return SLOW;
    int index;
    const struct snapshot *s = ready(&index);
    if (!s) return SLOW;
    f->s = s;
    f->links = f->base_link = f->dir_suffix = 0;
    const u32 paths = shared->paths;
    struct kstat *const st = &f->st;
    char *const host = f->host, *const link = f->link;
    const u32 link_size = sizeof f->link;

    for (u32 i = 0; i < s->root_length; i++) host[i] = s->root[i];
    char *guest = host + s->root_length;
    u32 n = 0;  // length of guest
    guest[0] = 0;

    // What is left to walk. A symlink's target is put in front of the rest; from `original` on,
    // the text is the caller's.
    char todo[GUEST_MAX + 1];
    u32 end = 0, at = 0, original = 0, links = 0, base = 0;
    if (path[0] != '/') {
        if (dirfd == AT_FDCWD) {
            end = working_directory(todo);
            if (!end) return SLOW;
        } else {
            // The kernel names the directory by its host path.
            if ((s->flags & FASTPATH_HOST_OVERLAP) || dirfd < 0) return SLOW;
            char name[40];
            proc_fd(name, dirfd);
            long r = fast(SYS_readlinkat, AT_FDCWD, (long)name, (long)link, link_size - 1);
            if (r < (long)s->root_length || r >= (long)link_size - 1) return SLOW;
            link[r] = 0;
            if (!same(link, s->root, s->root_length)) return SLOW;
            end = r - s->root_length;
            if (end > GUEST_MAX || (end && link[s->root_length] != '/')) return SLOW;
            const char deleted[] = " (deleted)";
            if (end >= sizeof deleted - 1 && same(link + r - (sizeof deleted - 1), deleted, sizeof deleted - 1)) return SLOW;
            for (u32 i = 0; i < end; i++) todo[i] = link[s->root_length + i];
        }
        if (end == GUEST_MAX) return SLOW;
        todo[end++] = '/';
        base = end;
    }
    for (u32 i = 0; path[i]; i++) {
        if (end == GUEST_MAX) return SLOW;
        todo[end++] = path[i];
    }
    // A trailing slash, or "." or ".." at the end, asks for a directory.
    int want_dir = 0;
    while (end && todo[end - 1] == '/') end--, want_dir = 1;

    u64 hash = HASH_START;

    for (;;) {
        while (at < end && todo[at] == '/') at++;
        if (at == end) break;
        const u32 start = at;
        while (at < end && todo[at] != '/') at++;
        const u32 len = at - start;
        u32 rest = at;
        while (rest < end && todo[rest] == '/') rest++;
        const int last = rest == end, callers = start >= original;

        if (len == 1 && todo[start] == '.') {
            if (last && callers) want_dir = 1;
            continue;
        }
        if (len == 2 && todo[start] == '.' && todo[start + 1] == '.') {
            // The kernel won't walk what is dropped here, so look at it now: a remembered
            // directory may have lost its permissions, and proot wants every component reachable.
            if (n && (lstat_host(host, st) || (st->st_mode & S_IFMT) != S_IFDIR)) return SLOW;
            while (n && guest[--n] != '/') {}
            guest[n] = 0;
            hash = hash_more(HASH_START, guest, n);
            if (last && callers) want_dir = 1;
            continue;
        }
        if (len > NAME_LIMIT || n + 1 + len > GUEST_MAX || reserved_name(todo + start, len)) return SLOW;

        const u32 parent = n;
        const u64 parent_hash = hash;
        guest[n++] = '/';
        for (u32 i = 0; i < len; i++) guest[n++] = todo[start + i];
        guest[n] = 0;
        hash = hash_more(parent_hash, guest + parent, n - parent);
        const u64 key = key_of(hash, paths, index);
        if (!last && known_dir(key)) continue;

        const int where = binding_of(s, guest, n);
        if (where == INSIDE) return SLOW;
        long r = lstat_host(host, st);
        // Below a missing directory or a file there is nothing. Anything else unexpected, such as
        // a directory proot would open up for the guest's root user first, is proot's to answer.
        if (r == -ENOENT && where == OUTSIDE) {
            *result = r;
            return ANSWERED;
        }
        if (r) return SLOW;

        if ((st->st_mode & S_IFMT) == S_IFLNK) {
            long t = fast(SYS_readlinkat, AT_FDCWD, (long)host, (long)link, link_size - 1);
            if (t <= 0 || t >= (long)link_size - 1) return SLOW;
            link[t] = 0;
            // A faked hard link: proot reports the file behind it.
            u32 name = t;
            while (name && link[name - 1] != '/') name--;
            if (reserved_name(link + name, t - name)) return SLOW;
            // The symlink itself, as the caller's last component.
            if (last && callers && !want_dir && !follow) {
                f->link_length = t;
                return FOUND;
            }
            if (!links && start < base) f->base_link = 1;
            if (++links > LINK_LIMIT) return SLOW;
            f->links = links;

            const char *target = link;
            if (link[0] == '/') {
                // proot takes a target below the rootfs' host path for the guest path behind it.
                if ((u32)t >= s->root_length && same(link, s->root, s->root_length) &&
                    (link[s->root_length] == '/' || !link[s->root_length])) {
                    target += s->root_length;
                    t -= s->root_length;
                }
                n = 0;
                hash = HASH_START;
            } else {
                n = parent;
                hash = parent_hash;
            }
            guest[n] = 0;

            // todo = target + "/" + what was left.
            const u32 left = end - rest, moved = left ? t + 1 : t;
            if (moved + left > GUEST_MAX) return SLOW;
            if (!left && (link[t - 1] == '/' || (link[t - 1] == '.' && (t == 1 || link[t - 2] == '/' ||
                                                                         (link[t - 2] == '.' && (t == 2 || link[t - 3] == '/'))))))
                f->dir_suffix = 1;
            if (moved > rest)
                for (u32 i = left; i--;) todo[moved + i] = todo[rest + i];
            else
                for (u32 i = 0; i < left; i++) todo[moved + i] = todo[rest + i];
            for (u32 i = 0; i < (u32)t; i++) todo[i] = target[i];
            if (left) todo[t] = '/';
            original = moved + (original > rest ? original - rest : 0);
            end = moved + left;
            at = 0;
            continue;
        }

        if (last) {
            // Reached through a symlink, or a directory the walk may have passed before.
            if ((!callers || (st->st_mode & S_IFMT) == S_IFDIR) && !owner_has_access(st)) return SLOW;
            if (want_dir && (st->st_mode & S_IFMT) != S_IFDIR) {
                *result = -ENOTDIR;
                return ANSWERED;
            }
            return FOUND;
        }
        if ((st->st_mode & S_IFMT) != S_IFDIR) {
            *result = -ENOTDIR;
            return ANSWERED;
        }
        if (shared->paths == paths) remember_dir(key);
    }

    // The path ended in "." or "..": the answer is the directory guest names now, which may be
    // one whose name was never looked at (the working directory's parent, the rootfs itself).
    u32 name = s->root_length + n;
    while (name && host[name - 1] != '/') name--;
    if (reserved_name(host + name, s->root_length + n - name)) return SLOW;
    // A remembered directory may be a file by now, which nothing counts.
    if (lstat_host(host, st) || (st->st_mode & S_IFMT) != S_IFDIR || !owner_has_access(st)) return SLOW;
    return FOUND;
}

// Except for stat, proot opens up what the path ends on too (see owner_has_access()), so a file
// or directory whose owner lacks access is proot's to answer. A symlink has all permissions.
static int plain(const struct found *f) {
    return (f->st.st_mode & S_IFMT) != S_IFLNK && owner_has_access(&f->st);
}

// ---- The calls -----------------------------------------------------------------------------

#ifdef FASTPATH_STATS
// For scripts/proot-bench/fuzz.c: calls answered here, and calls handed to proot.
__attribute__((visibility("default"))) unsigned long fastpath_stats[2];
#define COUNT(i) __atomic_add_fetch(&fastpath_stats[i], 1, __ATOMIC_RELAXED)
#else
#define COUNT(i) (void)0
#endif

// What libc's functions return: -1 with errno set for a -errno from the kernel.
static long libc_result(long r) {
    if (r < 0 && r > -4096) {
        *__errno_location() = -r;
        return -1;
    }
    return r;
}

// open and close are cancellation points, and what the fast lane leaves alone may block (a fifo,
// a terminal). So the slow way for those two is libc's own function, found behind this library.
// They are looked up when the library is loaded: dlsym is not for signal handlers, close is.
extern void *dlsym(void *handle, const char *name) __attribute__((weak));
static int (*libc_openat)(int, const char *, int, u32);
static int (*libc_close)(int);
static int (*libc_faccessat)(int, const char *, int, int);
static long (*libc_readlink_chk)(const char *, char *, u64, u64);
static long (*libc_readlinkat_chk)(int, const char *, char *, u64, u64);
static char *(*libc_realpath)(const char *, char *);
static char *(*libc_realpath_chk)(const char *, char *, u64);
static char *(*libc_getcwd)(char *, u64);
extern void *malloc(u64 size) __attribute__((weak));
__attribute__((constructor)) static void find_libc_functions(void) {
    void *const next = (void *)-1L;  // RTLD_NEXT
    if (!dlsym) return;
    libc_openat = dlsym(next, "openat64");
    libc_close = dlsym(next, "close");
    libc_faccessat = dlsym(next, "faccessat");
    libc_readlink_chk = dlsym(next, "__readlink_chk");
    libc_readlinkat_chk = dlsym(next, "__readlinkat_chk");
    libc_realpath = dlsym(next, "realpath");
    libc_realpath_chk = dlsym(next, "__realpath_chk");
    libc_getcwd = dlsym(next, "getcwd");
}

// stat shows the guest's ids in place of proot's own (src/extension/fake_id0).
static void fake_ids(const struct snapshot *s, struct kstat *st) {
    if (st->st_uid == s->real_uid) st->st_uid = s->fake_uid;
    if (st->st_gid == s->real_gid) st->st_gid = s->fake_gid;
}

// The kernel's name for what fd is open on, when proot would report the kernel's fstat of it:
// proot reads a link count out of a name that starts with ".l2s." (the file behind a faked hard
// link), and looks at the name again when the file is gone.
static int plain_fd(int fd, char *buffer, u32 size) {
    char name[40];
    proc_fd(name, fd);
    long r = fast(SYS_readlinkat, AT_FDCWD, (long)name, (long)buffer, size - 1);
    if (r <= 0 || r >= (long)size - 1) return 0;
    const char deleted[] = " (deleted)";
    if (r >= (long)sizeof deleted - 1 && same(buffer + r - (sizeof deleted - 1), deleted, sizeof deleted - 1)) return 0;
    u32 base = r;
    while (base && buffer[base - 1] != '/') base--;
    return !reserved_name(buffer + base, r - base);
}

static int fstat_fd(int fd, struct kstat *out) {
    int index;
    const struct snapshot *s = fd >= 0 ? ready(&index) : 0;
    struct kstat st;
    char name[ROOT_MAX + GUEST_MAX + 8];
    // A character device may be the ashmem behind a memfd, which proot dresses up as a file. And
    // proot turns down a descriptor that is open on a symlink itself (O_PATH | O_NOFOLLOW).
    if (s && !fast(SYS_fstat, fd, (long)&st, 0, 0) && (st.st_mode & S_IFMT) != S_IFCHR && (st.st_mode & S_IFMT) != S_IFLNK &&
        plain_fd(fd, name, sizeof name)) {
        COUNT(0);
        fake_ids(s, &st);
        *out = st;
        return 0;
    }
    COUNT(1);
    return libc_result(slow(SYS_fstat, fd, (long)out, 0, 0, 0, 0));
}

static int stat_at(int dirfd, const char *path, struct kstat *out, int flags) {
    struct found f;
    long r;
    int how = flags & ~(AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT) ? SLOW : resolve(dirfd, path, !(flags & AT_SYMLINK_NOFOLLOW), &f, &r);
    if (how == FOUND) {
        fake_ids(f.s, &f.st);
        *out = f.st;
        r = 0;
    }
    if (how != SLOW) COUNT(0);
    else {
        COUNT(1);
        r = slow(SYS_newfstatat, dirfd, (long)path, (long)out, flags, 0, 0);
    }
    return libc_result(r);
}

// struct statx starts with: u32 mask, blksize; u64 attributes; u32 nlink, uid, gid; u16 mode.
struct statx_start {
    u32 mask, blksize;
    u64 attributes;
    u32 nlink, uid, gid;
};

static int statx_at(int dirfd, const char *path, int flags, u32 mask, void *out) {
    struct found f;
    long r;
    // What the kernel turns down for its arguments alone is left to it: it does so before it
    // looks at the path, proot only after the directories on the way.
    int how = (flags & ~(AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT | AT_STATX_SYNC_TYPE)) ||
                      (flags & AT_STATX_SYNC_TYPE) == AT_STATX_SYNC_TYPE || (mask & 0x80000000)
                  ? SLOW
                  : resolve(dirfd, path, !(flags & AT_SYMLINK_NOFOLLOW), &f, &r);
    if (how == FOUND) {
        how = SLOW;
        // proot opens up a file without owner access for statx, and the answer shows that mode.
        if (((f.st.st_mode & S_IFMT) == S_IFLNK || owner_has_access(&f.st)) &&
            !fast5(SYS_statx, AT_FDCWD, (long)f.host, flags, mask, (long)out)) {
            struct statx_start *stx = out;
            if ((stx->mask & STATX_UID) && stx->uid == f.s->real_uid) stx->uid = f.s->fake_uid;
            // proot compares the group with its user id here.
            if ((stx->mask & STATX_GID) && stx->gid == f.s->real_uid) stx->gid = f.s->fake_gid;
            r = 0;
            how = FOUND;
        }
    }
    if (how != SLOW) COUNT(0);
    else {
        COUNT(1);
        r = slow(SYS_statx, dirfd, (long)path, flags, mask, (long)out, 0);
    }
    return libc_result(r);
}

static int access_at(int dirfd, const char *path, int mode, int flags) {
    struct found f;
    long r;
    // proot follows a symlink at the end whatever the flags say, and libc has its own ways for
    // kernels without faccessat2.
    int how = flags || (mode & ~7) ? SLOW : resolve(dirfd, path, 1, &f, &r);
    if (how == FOUND) {
        how = SLOW;
        if (plain(&f)) {
            r = fast(SYS_faccessat, AT_FDCWD, (long)f.host, mode, 0);
            if (!r || r == -EACCES) how = FOUND;
        }
    }
    if (how != SLOW) COUNT(0);
    else {
        COUNT(1);
        if (flags && libc_faccessat) return libc_faccessat(dirfd, path, mode, flags);
        r = slow(flags ? SYS_faccessat2 : SYS_faccessat, dirfd, (long)path, mode, flags, 0, 0);
    }
    return libc_result(r);
}

static long readlink_at(int dirfd, const char *path, char *out, u64 size) {
    struct found f;
    long r;
    // The kernel takes the size for an int.
    int how = (int)size <= 0 ? SLOW : resolve(dirfd, path, 0, &f, &r);
    if (how == FOUND) {
        how = SLOW;
        // The kernel's answer: what fits of the target, or EINVAL for what is no symlink.
        r = fast(SYS_readlinkat, AT_FDCWD, (long)f.host, (long)out, size);
        if (r == -EINVAL) how = FOUND;
        else if (r > 0 && (f.st.st_mode & S_IFMT) == S_IFLNK) {
            how = FOUND;
            // proot shows a target below the rootfs' host path as the guest path behind it.
            const u32 root = f.s->root_length;
            if (f.link_length >= root && same(f.link, f.s->root, root) && (f.link[root] == '/' || !f.link[root])) {
                const char *target = f.link_length > root ? f.link + root : "/";
                const u32 length = f.link_length > root ? f.link_length - root : 1;
                // As proot does it: with the NUL when there is room, which it counts when the
                // room is exactly that.
                r = length + 1 < size ? length : size;
                for (u64 i = 0; i <= length && i < size; i++) out[i] = target[i];
            }
        }
    }
    if (how != SLOW) COUNT(0);
    else {
        COUNT(1);
        r = slow(SYS_readlinkat, dirfd, (long)path, (long)out, size, 0, 0);
    }
    return libc_result(r);
}

static int open_at(int dirfd, const char *path, int flags, u32 mode) {
    struct found f;
    long r;
    // Only what reads: nothing is made, emptied or written. And only files and directories, which
    // open at once (see libc_function()).
    int how = (flags & O_ACCMODE) || (flags & ~(O_CLOEXEC | O_NOCTTY | O_NONBLOCK | O_DIRECTORY | O_NOFOLLOW | O_LARGEFILE | O_PATH))
                  ? SLOW
                  : resolve(dirfd, path, !(flags & O_NOFOLLOW), &f, &r);
    if (how == FOUND) {
        how = SLOW;
        const u32 type = f.st.st_mode & S_IFMT;
        if ((type == S_IFREG || type == S_IFDIR) && plain(&f)) {
            r = fast(SYS_openat, AT_FDCWD, (long)f.host, flags, 0);
            if (r >= 0) how = FOUND;
        }
    }
    if (how != SLOW) COUNT(0);
    else {
        COUNT(1);
        if (libc_openat) return libc_openat(dirfd, path, flags, mode);
        r = slow(SYS_openat, dirfd, (long)path, flags, mode, 0, 0);
    }
    return libc_result(r);
}

#define EXPORT __attribute__((visibility("default")))
EXPORT int stat(const char *path, struct kstat *out) { return stat_at(AT_FDCWD, path, out, 0); }
EXPORT int stat64(const char *path, struct kstat *out) { return stat_at(AT_FDCWD, path, out, 0); }
EXPORT int lstat(const char *path, struct kstat *out) { return stat_at(AT_FDCWD, path, out, AT_SYMLINK_NOFOLLOW); }
EXPORT int lstat64(const char *path, struct kstat *out) { return stat_at(AT_FDCWD, path, out, AT_SYMLINK_NOFOLLOW); }
EXPORT int fstatat(int dirfd, const char *path, struct kstat *out, int flags) { return stat_at(dirfd, path, out, flags); }
EXPORT int fstatat64(int dirfd, const char *path, struct kstat *out, int flags) { return stat_at(dirfd, path, out, flags); }
EXPORT int fstat(int fd, struct kstat *out) { return fstat_fd(fd, out); }
EXPORT int fstat64(int fd, struct kstat *out) { return fstat_fd(fd, out); }
// What programs built against glibc before 2.33 call.
EXPORT int __xstat(int v, const char *path, struct kstat *out) { (void)v; return stat_at(AT_FDCWD, path, out, 0); }
EXPORT int __xstat64(int v, const char *path, struct kstat *out) { (void)v; return stat_at(AT_FDCWD, path, out, 0); }
EXPORT int __lxstat(int v, const char *path, struct kstat *out) { (void)v; return stat_at(AT_FDCWD, path, out, AT_SYMLINK_NOFOLLOW); }
EXPORT int __lxstat64(int v, const char *path, struct kstat *out) { (void)v; return stat_at(AT_FDCWD, path, out, AT_SYMLINK_NOFOLLOW); }
EXPORT int __fxstatat(int v, int dirfd, const char *path, struct kstat *out, int flags) { (void)v; return stat_at(dirfd, path, out, flags); }
EXPORT int __fxstatat64(int v, int dirfd, const char *path, struct kstat *out, int flags) { (void)v; return stat_at(dirfd, path, out, flags); }
EXPORT int __fxstat(int v, int fd, struct kstat *out) { (void)v; return fstat_fd(fd, out); }
EXPORT int __fxstat64(int v, int fd, struct kstat *out) { (void)v; return fstat_fd(fd, out); }

EXPORT int statx(int dirfd, const char *path, int flags, u32 mask, void *out) { return statx_at(dirfd, path, flags, mask, out); }

EXPORT int access(const char *path, int mode) { return access_at(AT_FDCWD, path, mode, 0); }
EXPORT int faccessat(int dirfd, const char *path, int mode, int flags) { return access_at(dirfd, path, mode, flags); }

EXPORT long readlink(const char *path, char *out, u64 size) { return readlink_at(AT_FDCWD, path, out, size); }
EXPORT long readlinkat(int dirfd, const char *path, char *out, u64 size) { return readlink_at(dirfd, path, out, size); }

// What a program built with _FORTIFY_SOURCE calls; libc ends the program when the buffer is too
// small for the size.
EXPORT long __readlink_chk(const char *path, char *out, u64 size, u64 space) {
    return size > space && libc_readlink_chk ? libc_readlink_chk(path, out, size, space) : readlink_at(AT_FDCWD, path, out, size);
}
EXPORT long __readlinkat_chk(int dirfd, const char *path, char *out, u64 size, u64 space) {
    return size > space && libc_readlinkat_chk ? libc_readlinkat_chk(dirfd, path, out, size, space)
                                                : readlink_at(dirfd, path, out, size);
}

// libc declares these with "..." for the mode; on arm64 that argument arrives the same way.
EXPORT int open(const char *path, int flags, u32 mode) { return open_at(AT_FDCWD, path, flags, mode); }
EXPORT int open64(const char *path, int flags, u32 mode) { return open_at(AT_FDCWD, path, flags, mode); }
EXPORT int openat(int dirfd, const char *path, int flags, u32 mode) { return open_at(dirfd, path, flags, mode); }
EXPORT int openat64(int dirfd, const char *path, int flags, u32 mode) { return open_at(dirfd, path, flags, mode); }

// proot wants to see a close for what it keeps track of by descriptor: pipes, sockets and files
// of /proc. A file or directory of the rootfs' filesystem is none of those.
EXPORT int close(int fd) {
    int index;
    const struct snapshot *s = fd >= 0 ? ready(&index) : 0;
    struct kstat st;
    if (s && s->root_dev && !fast(SYS_fstat, fd, (long)&st, 0, 0) && st.st_dev == s->root_dev &&
        ((st.st_mode & S_IFMT) == S_IFREG || (st.st_mode & S_IFMT) == S_IFDIR)) {
        COUNT(0);
        return libc_result(fast(SYS_close, fd, 0, 0, 0));
    }
    COUNT(1);
    if (libc_close) return libc_close(fd);
    return libc_result(slow(SYS_close, fd, 0, 0, 0, 0, 0));
}

// getxattr, listxattr and their forms for a symlink itself: proot only translates the path. As
// for statx, it opens up a file whose owner lacks access first.
static long xattr_at(long nr, int follow, const char *path, long a, long b, long c) {
    struct found f;
    long r;
    int how = resolve(AT_FDCWD, path, follow, &f, &r);
    if (how == FOUND) {
        how = SLOW;
        if ((f.st.st_mode & S_IFMT) == S_IFLNK || owner_has_access(&f.st)) {
            r = fast(nr, (long)f.host, a, b, c);
            how = FOUND;
        }
    }
    if (how != SLOW) COUNT(0);
    else {
        COUNT(1);
        r = slow(nr, (long)path, a, b, c, 0, 0);
    }
    return libc_result(r);
}
EXPORT long getxattr(const char *path, const char *name, void *value, u64 size) { return xattr_at(SYS_getxattr, 1, path, (long)name, (long)value, size); }
EXPORT long lgetxattr(const char *path, const char *name, void *value, u64 size) { return xattr_at(SYS_lgetxattr, 0, path, (long)name, (long)value, size); }
EXPORT long listxattr(const char *path, char *list, u64 size) { return xattr_at(SYS_listxattr, 1, path, (long)list, size, 0); }
EXPORT long llistxattr(const char *path, char *list, u64 size) { return xattr_at(SYS_llistxattr, 0, path, (long)list, size, 0); }

// The working directory as proot knows it, when it still is a directory of the rootfs by that
// name: proot looks before it answers too. libc's getcwd does the rest (no buffer, one in a
// binding, one that is gone).
EXPORT char *getcwd(char *buffer, u64 size) {
    struct found f;
    long r;
    char now[GUEST_MAX + 1];
    if (buffer && size && resolve(AT_FDCWD, ".", 1, &f, &r) == FOUND) {
        const char *guest = f.host + f.s->root_length;
        const u32 n = length_of(guest), c = working_directory(now);
        // Not reached through a symlink that took the place of a directory since.
        if (n && c == n && same(now, guest, n)) {
            COUNT(0);
            if (size < n + 1) {
                *__errno_location() = ERANGE;
                return 0;
            }
            for (u32 i = 0; i <= n; i++) buffer[i] = guest[i];
            return buffer;
        }
    }
    COUNT(1);
    if (libc_getcwd) return libc_getcwd(buffer, size);
    if (!buffer) {
        *__errno_location() = EINVAL;
        return 0;
    }
    return libc_result(slow(SYS_getcwd, (long)buffer, size, 0, 0, 0, 0)) < 0 ? 0 : buffer;
}

// Without libc's realpath (a program whose libc keeps dlsym elsewhere): what the path is open
// on, as proot names it.
__attribute__((noinline)) static char *realpath_by_fd(const char *path, char *resolved) {
    char name[40], target[4096];
    long fd = path ? slow(SYS_openat, AT_FDCWD, (long)path, O_PATH | O_CLOEXEC, 0, 0, 0) : -EINVAL;
    if (fd < 0) return (char *)(libc_result(fd) + 1);
    proc_fd(name, fd);
    long n = slow(SYS_readlinkat, AT_FDCWD, (long)name, (long)target, sizeof target - 1, 0, 0);
    slow(SYS_close, fd, 0, 0, 0, 0, 0);
    if (n <= 0 || target[0] != '/') return (char *)(libc_result(n < 0 ? n : -ENOENT) + 1);
    if (!resolved) resolved = malloc ? malloc(n + 1) : 0;
    if (!resolved) return (char *)(libc_result(-ENOMEM) + 1);
    for (long i = 0; i < n; i++) resolved[i] = target[i];
    resolved[n] = 0;
    return resolved;
}

// The walk above ends on the canonical guest path, which is realpath's answer: libc's own
// realpath asks proot about every component with readlink, from inside libc where no call of
// ours is reached (compilers do this for every file they open). Errors, and what the walk leaves
// to proot, are libc's to answer.
EXPORT char *realpath(const char *path, char *resolved) {
    struct found f;
    long r;
    // libc wants a directory where a symlink's target ends in a slash or a dot, proot's walk
    // doesn't. And libc takes the working directory as getcwd names it, even when a directory in
    // that name has become a symlink since.
    const int how = path ? resolve(AT_FDCWD, path, 1, &f, &r) : SLOW;
    if (how == FOUND && (resolved || malloc) && !f.base_link && (!f.dir_suffix || (f.st.st_mode & S_IFMT) == S_IFDIR)) {
        const char *guest = f.host + f.s->root_length;
        u32 n = length_of(guest);
        if (!n) guest = "/", n = 1;
        if (!resolved) resolved = malloc(n + 1);
        if (resolved) {
            COUNT(0);
            for (u32 i = 0; i <= n; i++) resolved[i] = guest[i];
            return resolved;
        }
    }
    // Nothing there (compilers ask for every place a header might be): libc leaves the name of
    // what is missing in the buffer, which is where the walk stopped. With a working directory
    // that is gone itself, libc names nothing.
    struct found here;
    long gone;
    if (how == ANSWERED && r == -ENOENT && !f.base_link &&
        (path[0] == '/' || resolve(AT_FDCWD, ".", 1, &here, &gone) == FOUND)) {
        COUNT(0);
        if (resolved) {
            const char *guest = f.host + f.s->root_length;
            for (u32 i = 0; i == 0 || guest[i - 1]; i++) resolved[i] = guest[i];
        }
        *__errno_location() = ENOENT;
        return 0;
    }
    COUNT(1);
    return libc_realpath ? libc_realpath(path, resolved) : realpath_by_fd(path, resolved);
}
EXPORT char *canonicalize_file_name(const char *path) { return realpath(path, 0); }
// What a program built with _FORTIFY_SOURCE calls; libc ends the program when the buffer is
// smaller than PATH_MAX.
EXPORT char *__realpath_chk(const char *path, char *resolved, u64 space) {
    return space < 4096 && libc_realpath_chk ? libc_realpath_chk(path, resolved, space) : realpath(path, resolved);
}

// libuv, and so node, calls statx by number. Anything else passes through. In assembly, as libc
// has it: a clone made this way comes back on another stack.
long syscall_statx(long nr, int dirfd, const char *path, int flags, u32 mask, void *out) __attribute__((visibility("hidden")));
long syscall_statx(long nr, int dirfd, const char *path, int flags, u32 mask, void *out) {
    (void)nr;
    return statx_at(dirfd, path, flags, mask, out);
}
long syscall_failed(long r) __attribute__((visibility("hidden")));
long syscall_failed(long r) { return libc_result(r); }
__asm__(
    ".text\n"
    ".global syscall\n.type syscall,%function\n"
    "syscall: cmp x0, #291; b.eq syscall_statx\n"
    "         mov x8, x0; mov x0, x1; mov x1, x2; mov x2, x3; mov x3, x4; mov x4, x5; mov x5, x6\n"
    "         svc 0\n"
    "         cmn x0, #4095; b.cs syscall_failed\n"
    "         ret\n");
