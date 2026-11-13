// Differential fuzzer for the fast lane (patches/proot/fastpath). Runs in the guest with the
// library preloaded and asks every question twice: through libc (fstatat, statx, access, readlink,
// open and close, fstat), which the library answers, and as a bare syscall, which only proot answers. Any difference is a bug in the library.
// Between questions it changes the tree the way that could fool the library: directories become
// symlinks and back, subtrees move, hard links come and go, in this process or in a child.
//
// Build in the guest: gcc -O2 -pthread fuzz.c -o /usr/local/bin/fuzz
//   fuzz run <seed> <questions> [host path of the rootfs]   one tree, questions and changes mixed
//   fuzz ids <seed> <questions>      the same questions after changing the fake ids, in a chroot, below a mount
//   fuzz threads <seed> <questions>  four threads ask about /usr while the main thread changes a tree
//   fuzz poll <path> <seconds>       asks about one path over and over, for changes made elsewhere
// Prints "DIFF ..." lines and a summary; the exit code is 1 when there was a difference.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <linux/stat.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <time.h>
#include <unistd.h>

#define TREE "/root/fz"
#define MAXP 4000
// The library's counters when it is built with -DFASTPATH_STATS: answered, handed to proot.
extern unsigned long fastpath_stats[2] __attribute__((weak));

static __thread uint64_t rng;
static uint32_t rnd(uint32_t n) {
    rng ^= rng << 13, rng ^= rng >> 7, rng ^= rng << 17;
    return n ? (uint32_t)(rng >> 33) % n : 0;
}

// A syscall instruction of our own: libc's syscall() is the library's too.
static long raw(long nr, long a, long b, long c, long d, long e) {
    register long x8 __asm__("x8") = nr;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4) : "memory");
    return x0;
}

// The kinds of question. `flags` and `extra` mean: stat: AT_ flags; statx: AT_ flags, mask;
// access: mode, how to call; readlink: buffer size; open: O_ flags; fstat: nothing, dirfd is asked.
// realpath: extra says whether libc allocates; xattr: flags pick the call, extra the size;
// getcwd: extra is the size.
enum { STAT, STATX, ACCESS, READLINK, OPEN, FSTAT, REALPATH, XATTR, GETCWD, KINDS };
static const char *kinds[] = {"fstatat", "statx", "access", "readlink", "open", "fstat", "realpath", "xattr", "getcwd"};
// libc's own realpath and getcwd, behind the library's. (Its getcwd is more than the syscall: when
// proot says ENAMETOOLONG it walks up from "." itself.)
static char *(*libc_realpath)(const char *, char *);
static char *(*libc_getcwd)(char *, size_t);

struct answer {
    long rc;
    long more[4];
    char data[640];
};
#define LIBC(call) (errno = 0, (r = (call)) < 0 ? -errno : r)
static void ask(int lib, int kind, int dirfd, const char *path, int flags, long extra, struct answer *a) {
    long r;
    memset(a, 0, sizeof *a);
    switch (kind) {
    case STAT:
        a->rc = lib ? LIBC(fstatat(dirfd, path, (struct stat *)a->data, flags)) : raw(SYS_newfstatat, dirfd, (long)path, (long)a->data, flags, 0);
        break;
    case STATX:
        // node calls it by number through libc's syscall().
        a->rc = !lib          ? raw(SYS_statx, dirfd, (long)path, flags, extra, (long)a->data)
                : extra & 0x10000 ? LIBC(syscall(SYS_statx, dirfd, path, flags, (unsigned)extra, a->data))
                                  : LIBC(statx(dirfd, path, flags, extra, (struct statx *)a->data));
        break;
    case ACCESS:
        a->rc = !lib                              ? raw(SYS_faccessat, dirfd, (long)path, flags, 0, 0)
                : extra && dirfd == AT_FDCWD ? LIBC(access(path, flags))
                                             : LIBC(faccessat(dirfd, path, flags, 0));
        break;
    case READLINK:
        memset(a->data, 0xaa, sizeof a->data);
        a->rc = !lib ? raw(SYS_readlinkat, dirfd, (long)path, (long)a->data, extra, 0)
                : dirfd == AT_FDCWD && (extra & 1) ? LIBC(readlink(path, a->data, extra))
                                                   : LIBC(readlinkat(dirfd, path, a->data, extra));
        break;
    case OPEN: {
        int fd = a->rc = lib ? LIBC(openat(dirfd, path, flags)) : raw(SYS_openat, dirfd, (long)path, flags, 0, 0);
        if (fd < 0) break;
        // The same file, opened the same way? And closed for good?
        a->rc = 0;
        // The library's fstat is not one proot sees: permissions proot forced for the open must be
        // back by now.
        a->more[0] = lib ? LIBC(fstat(fd, (struct stat *)a->data)) : raw(SYS_fstat, fd, (long)a->data, 0, 0, 0);
        a->more[1] = raw(SYS_fcntl, fd, F_GETFL, 0, 0, 0);
        a->more[2] = lib ? LIBC(close(fd)) : raw(SYS_close, fd, 0, 0, 0, 0);
        a->more[3] = raw(SYS_fcntl, fd, F_GETFD, 0, 0, 0);
        break;
    }
    case FSTAT:
        a->rc = lib ? LIBC(fstat(dirfd, (struct stat *)a->data)) : raw(SYS_fstat, dirfd, (long)a->data, 0, 0, 0);
        break;
    case REALPATH: {
        char buffer[PATH_MAX] = "";
        errno = 0;
        char *p = (lib ? realpath : libc_realpath)(path, extra ? NULL : buffer);
        a->rc = p ? (long)strlen(p) : -errno;
        if (p) strncpy(a->data, p, sizeof a->data - 1);
        // What is missing is named in the buffer.
        else if (a->rc == -ENOENT && !extra) strncpy(a->data, buffer, sizeof a->data - 1);
        if (p && extra) free(p);
        break;
    }
    case XATTR: {
        const char *name = flags & 4 ? "security.selinux" : "user.fz";
        static const int numbers[] = {SYS_getxattr, SYS_lgetxattr, SYS_listxattr, SYS_llistxattr};
        if (!lib) a->rc = flags & 2 ? raw(numbers[flags & 3], (long)path, (long)a->data, extra, 0, 0) : raw(numbers[flags & 3], (long)path, (long)name, (long)a->data, extra, 0);
        else if ((flags & 3) == 0) a->rc = LIBC(getxattr(path, name, a->data, extra));
        else if ((flags & 3) == 1) a->rc = LIBC(lgetxattr(path, name, a->data, extra));
        else if ((flags & 3) == 2) a->rc = LIBC(listxattr(path, a->data, extra));
        else a->rc = LIBC(llistxattr(path, a->data, extra));
        break;
    }
    case GETCWD:
        errno = 0;
        a->rc = (lib ? getcwd : libc_getcwd)(a->data, extra) ? (long)strlen(a->data) + 1 : -errno;
        break;
    }
    if (a->rc < 0 && kind != REALPATH) memset(a->data, 0, sizeof a->data);
}
static int equal(const struct answer *a, const struct answer *b) { return !memcmp(a, b, sizeof *a); }
static void show(const char *who, int kind, const struct answer *a) {
    const struct stat *st = (const struct stat *)a->data;
    const struct statx *stx = (const struct statx *)a->data;
    if (a->rc < 0) printf("  %s: errno %ld\n", who, -a->rc);
    else if (kind == STATX)
        printf("  %s: mask %#x mode %o uid %u gid %u nlink %u ino %llu size %llu ctime %lld.%09u attributes %#llx\n", who, stx->stx_mask,
               stx->stx_mode, stx->stx_uid, stx->stx_gid, stx->stx_nlink, (unsigned long long)stx->stx_ino,
               (unsigned long long)stx->stx_size, (long long)stx->stx_ctime.tv_sec, stx->stx_ctime.tv_nsec,
               (unsigned long long)stx->stx_attributes);
    else if (kind == ACCESS) printf("  %s: ok\n", who);
    else if (kind == REALPATH || kind == XATTR || kind == GETCWD) printf("  %s: %ld \"%.60s\"\n", who, a->rc, a->data);
    else if (kind == READLINK) {
        printf("  %s: %ld \"%.*s\", then bytes %02x %02x\n", who, a->rc, (int)a->rc, a->data, (unsigned char)a->data[a->rc],
               (unsigned char)a->data[a->rc + 1]);
    } else
        printf("  %s: mode %o uid %u gid %u nlink %u ino %lu dev %lu size %ld mtime %ld ctime %ld.%09ld; fstat %ld flags %#lo close %ld then %ld\n",
               who, st->st_mode, st->st_uid, st->st_gid, (unsigned)st->st_nlink, st->st_ino, st->st_dev, st->st_size, st->st_mtime,
               st->st_ctim.tv_sec, st->st_ctim.tv_nsec, a->more[0], a->more[1], a->more[2], a->more[3]);
}

static long questions, diffs, unstable, classes[4], asked[KINDS];
static char cwd[MAXP] = "/";
// Asks both ways, twice each: a difference counts when each side agrees with itself. (proot opens
// up a directory without permissions while it walks through it, which changes its ctime.)
static void question(int kind, int dirfd, const char *dirname, const char *path, int flags, long extra) {
    struct answer l1, r1, l2, r2;
    ask(1, kind, dirfd, path, flags, extra, &l1);
    ask(0, kind, dirfd, path, flags, extra, &r1);
    __atomic_add_fetch(&questions, 1, __ATOMIC_RELAXED);
    __atomic_add_fetch(&asked[kind], 1, __ATOMIC_RELAXED);
    __atomic_add_fetch(&classes[l1.rc >= 0 ? 0 : l1.rc == -ENOENT ? 1 : l1.rc == -ENOTDIR ? 2 : 3], 1, __ATOMIC_RELAXED);
    if (equal(&l1, &r1)) return;
    ask(0, kind, dirfd, path, flags, extra, &r2);
    ask(1, kind, dirfd, path, flags, extra, &l2);
    if (!equal(&l1, &l2) || !equal(&r1, &r2)) {
        __atomic_add_fetch(&unstable, 1, __ATOMIC_RELAXED);
        return;
    }
    if (__atomic_add_fetch(&diffs, 1, __ATOMIC_RELAXED) > 40) return;
    printf("DIFF question %ld: %s(%d \"%s\", \"%s\", %#x, %#lx) cwd \"%s\"\n", questions, kinds[kind], dirfd, dirname,
           path ? path : "(null)", flags, extra, cwd);
    show("library", kind, &l1);
    show("proot  ", kind, &r1);
    // The working directory as proot has it (patch 0003's prctl), which is what the library uses.
    char proot_cwd[MAXP] = "?";
    if (raw(SYS_prctl, 0x50524f46, (long)proot_cwd, sizeof proot_cwd, 1, 0) < 0) strcpy(proot_cwd, "(no answer)");
    printf("  proot's working directory: \"%s\"\n", proot_cwd);
    fflush(stdout);
}

// A question of a random kind about a path. `stat_flags` are the AT_ flags a stat would get.
static void question_about(int dirfd, const char *dirname, const char *path, int stat_flags) {
    static const int sizes[] = {0, 1, 2, 7, 16, 64, 300, 639, -1};
    static const unsigned masks[] = {0x7ff, 0xfff, 0, 0x8, 0x10, 0x18, 0x4, 0x800007ff, 0x3fff};
    static const int opens[] = {O_RDONLY, O_RDONLY | O_CLOEXEC, O_RDONLY | O_DIRECTORY, O_RDONLY | O_NOFOLLOW, O_PATH,
                                O_PATH | O_NOFOLLOW, O_RDONLY | O_NONBLOCK | O_CLOEXEC, O_RDONLY | O_NOCTTY | O_DIRECTORY | O_CLOEXEC,
                                O_WRONLY, O_RDWR | O_NONBLOCK, O_RDONLY | O_NOATIME, O_RDONLY | O_NONBLOCK | O_APPEND};
    switch (rnd(16)) {
    case 0:
    case 1:
    case 2: {
        int flags = stat_flags & ~AT_EMPTY_PATH;
        if (!rnd(6)) flags |= rnd(2) ? 0x2000 : 0x4000;  // AT_STATX_FORCE_SYNC, AT_STATX_DONT_SYNC
        if (!rnd(100)) flags |= AT_EMPTY_PATH;
        question(STATX, dirfd, dirname, path, flags, masks[rnd(9)] | (rnd(2) ? 0x10000 : 0));
        break;
    }
    case 3:
    case 4: question(ACCESS, dirfd, dirname, path, rnd(20) ? rnd(8) : 8 + rnd(8), rnd(2)); break;
    case 5:
    case 6: question(READLINK, dirfd, dirname, path, 0, sizes[rnd(9)]); break;
    // Nothing that blocks: a fifo opens for reading only once somebody writes.
    case 7:
    case 8: question(OPEN, dirfd, dirname, path, opens[rnd(12)] | (strstr(path, "fifo") ? O_NONBLOCK : 0), 0); break;
    case 9: question(FSTAT, dirfd, dirname, "", 0, 0); break;
    case 12:
    case 13:
        if (dirfd == AT_FDCWD) {
            question(REALPATH, dirfd, dirname, path, 0, rnd(2));
            break;
        }
        // Fall through.
    case 14:
        if (dirfd == AT_FDCWD) {
            question(XATTR, dirfd, dirname, path, rnd(8), sizes[rnd(8)]);
            break;
        }
        // Fall through.
    case 15: question(GETCWD, dirfd, dirname, "", 0, sizes[rnd(8)]); break;
    default: question(STAT, dirfd, dirname, path, stat_flags, 0);
    }
}

// ---- The tree ------------------------------------------------------------------------------

#define MAXN 600
static char *all[MAXN];  // guest paths ever made; they may be gone or something else by now
static char kind[MAXN];  // d f s
static int count;
static const char *hostroot;
static const char *outside[] = {"/usr/lib", "/usr/lib/libc.so.6", "/bin", "/bin/sh", "/etc/passwd", "/proc",
                                "/proc/self", "/proc/self/cwd", "/dev/null", "/dev", "/sys", "/", "/tmp",
                                "/nonexistent/x", "/usr/share/zoneinfo/UTC", "/usr/lib/..", "/etc/mtab"};
#define OUTSIDE (sizeof outside / sizeof *outside)

static const char *add(const char *path, char k) {
    if (count == MAXN) return path;
    kind[count] = k;
    return all[count++] = strdup(path);
}
static const char *any(void) { return all[rnd(count)]; }
static const char *any_of(char k) {
    for (int i = 0; i < 50; i++) {
        int j = rnd(count);
        if (kind[j] == k) return all[j];
    }
    return all[0];
}
static const char *base_of(const char *path) {
    const char *slash = strrchr(path, '/');
    return slash ? slash + 1 : path;
}
static void name_into(char *out, const char *stem, int i) {
    static const int lengths[] = {199, 200, 201, 250};  // proot can't remove a name of 255 again
    switch (rnd(24)) {
    case 0: sprintf(out, "%s%d with space", stem, i); break;
    case 1: sprintf(out, ".%s%d", stem, i); break;
    case 2: sprintf(out, ".l2s.%s%d", stem, i); break;
    case 3: sprintf(out, ".proot-%s%d", stem, i); break;
    case 4: sprintf(out, "%s%d\xc3\xbc...", stem, i); break;
    case 5: {
        int n = lengths[rnd(4)];
        memset(out, 'a' + rnd(26), n);
        out[n] = 0;
        memcpy(out, stem, 1);
        break;
    }
    default: sprintf(out, "%s%d", stem, i);
    }
}
// What a symlink may point to.
static void target_into(char *out, const char *dir) {
    const char *p = any();
    switch (rnd(16)) {
    case 0: strcpy(out, base_of(p)); break;
    case 1: {  // relative, up from the link's directory and down again
        int ups = 0;
        for (const char *c = dir + strlen(TREE); *c; c++) ups += *c == '/';
        out[0] = 0;
        while (ups--) strcat(out, "../");
        strcat(out, strlen(p) > strlen(TREE) ? p + strlen(TREE) + 1 : ".");
        break;
    }
    case 2:
    case 3: strcpy(out, p); break;
    case 4:
    case 5: strcpy(out, outside[rnd(OUTSIDE)]); break;
    case 6: {
        static const char *odd[] = {".", "..", "../..", "./", "x/", "../../../../../../../..", "/..", "//"};
        strcpy(out, odd[rnd(8)]);
        break;
    }
    case 7: sprintf(out, "%s/", p); break;
    case 8: sprintf(out, "%s/.", p); break;
    case 9: sprintf(out, "s%d", rnd(80)); break;  // chains and loops
    case 10: sprintf(out, "s%d/%s", rnd(80), base_of(p)); break;
    case 11: sprintf(out, "%s%s", hostroot ? hostroot : "/data/local/tmp", rnd(2) ? p : ""); break;
    case 12: sprintf(out, "%s//.//%s", TREE, strlen(p) > strlen(TREE) ? p + strlen(TREE) + 1 : ""); break;
    case 13:
        memset(out, 'x', 1500);
        out[1500] = 0;
        break;
    case 14: sprintf(out, ".l2s.%s", base_of(p)); break;
    default: sprintf(out, "%s/../%s", any_of('d'), base_of(p));
    }
}

static void build(void) {
    char path[MAXP], name[300], target[MAXP];
    // fuzz.sh removes the old tree from outside: rm in the guest gives up on some of it.
    if (mkdir(TREE, 0755)) exit((perror(TREE), 2));
    add(TREE, 'd');
    for (int i = 0; i < 60; i++) {
        name_into(name, "d", i);
        snprintf(path, sizeof path, "%s/%s", any_of('d'), name);
        if (strlen(path) < 900 && !mkdir(path, 0755)) add(path, 'd');
    }
    for (int i = 0; i < 90; i++) {
        name_into(name, "f", i);
        snprintf(path, sizeof path, "%s/%s", any_of('d'), name);
        int fd = open(path, O_CREAT | O_WRONLY, rnd(8) ? 0644 : 0);
        if (fd < 0) continue;
        if (write(fd, path, rnd(64)) < 0) exit(2);
        close(fd);
        add(path, 'f');
    }
    for (int i = 0; i < 90; i++) {
        const char *dir = any_of('d');
        snprintf(path, sizeof path, "%s/s%d", dir, i);
        target_into(target, dir);
        if (!symlink(target, path)) add(path, 's');
    }
    for (int i = 0; i < 20; i++) {
        snprintf(path, sizeof path, "%s/h%d", any_of('d'), i);
        if (!link(any_of('f'), path)) add(path, 'f');
    }
    snprintf(path, sizeof path, "%s/fifo", any_of('d'));
    if (!mkfifo(path, 0644)) add(path, 'f');
    for (int i = 0; i < 4; i++) chmod(any_of('d'), i < 2 ? 0 : 0500);
}

// Is the name @path in a directory of the tree, whatever symlinks lead there? Every change is
// fenced by this: the tree's symlinks point all over the rootfs, and a change must not follow.
static int inside(const char *path) {
    char parent[MAXP], real[MAXP];
    snprintf(parent, sizeof parent, "%s", path);
    char *slash = strrchr(parent, '/');
    if (!slash || slash == parent) return 0;
    *slash = 0;
    if (strlen(path) >= MAXP || !realpath(parent, real)) return 0;
    size_t n = strlen(TREE);
    return !strncmp(real, TREE, n) && (!real[n] || real[n] == '/');
}
static int real_dir(const char *path) {
    struct stat st;
    return inside(path) && !lstat(path, &st) && S_ISDIR(st.st_mode);
}

// One change to the tree, such as could leave the library with a wrong idea of a directory.
static void change(void) {
    static int serial;
    char a[MAXP], b[MAXP], target[MAXP];
    const char *d = any_of('d'), *o;
    serial++;
    if (!inside(d) && strcmp(d, TREE)) return;
    const int what = rnd(12);
    // FUZZ_TRACE=1 tells which change came before a difference.
    if (getenv("FUZZ_TRACE")) printf("change %d (kind %d, pid %d, question %ld) on \"%s\"\n", serial, what, getpid(), questions, d);
    switch (what) {
    case 0:
    case 1:  // a directory becomes a symlink
        if (!strcmp(d, TREE)) break;
        snprintf(a, sizeof a, "%s.old", d);
        if (rename(d, a)) break;
        add(a, 'd');
        switch (rnd(5)) {
        case 0: strcpy(target, a); break;
        case 1: strcpy(target, base_of(a)); break;
        case 2: strcpy(target, any_of('d')); break;
        case 3: strcpy(target, outside[rnd(OUTSIDE)]); break;
        default: target_into(target, TREE);
        }
        if (symlink(target, d)) {}
        break;
    case 2:  // and a directory again
        snprintf(a, sizeof a, "%s.old", d);
        if (unlink(d)) break;
        if (rename(a, d) && mkdir(d, 0755)) {}
        break;
    case 3:  // a subtree moves
        if (!strcmp(d, TREE)) break;
        snprintf(a, sizeof a, "%s/m%d", any_of('d'), serial);
        if (inside(a) && !rename(d, a)) add(a, 'd');
        break;
    case 4:  // a directory and a symlink swap places
        o = any_of('s');
        if (!strcmp(d, TREE) || !inside(o)) break;
        if (syscall(SYS_renameat2, AT_FDCWD, d, AT_FDCWD, o, 2 /* RENAME_EXCHANGE */)) {}
        break;
    case 5:  // a new symlink
        snprintf(a, sizeof a, "%s/n%d", d, serial);
        target_into(target, d);
        if (inside(a) && !symlink(target, a)) add(a, 's');
        break;
    case 6:  // a symlink becomes a directory
        o = any_of('s');
        if (inside(o) && !unlink(o) && mkdir(o, 0755)) {}
        break;
    case 7:  // a hard link more or less
        snprintf(a, sizeof a, "%s/k%d", d, serial);
        o = any_of('f');
        if (!inside(a) || !inside(o)) break;
        if (rnd(2)) {
            if (!link(o, a)) add(a, 'f');
        } else if (unlink(o)) {}
        break;
    case 8:  // chmod follows a symlink
        if (real_dir(d)) chmod(d, rnd(4) ? 0755 : rnd(2) ? 0500 : 0);
        break;
    case 9:  // a directory takes the place of an empty one, with other content
        snprintf(a, sizeof a, "%s/e%d", any_of('d'), serial);
        snprintf(b, sizeof b, "%s/e%d/s1", any_of('d'), serial);
        if (!inside(a) || mkdir(a, 0755)) break;
        add(a, 'd');
        o = any_of('d');
        if (strcmp(o, TREE) && inside(o) && !rename(o, a)) {}
        target_into(target, a);
        if (inside(b) && symlink(target, b)) {}
        break;
    case 10:  // a symlink moves onto a name a directory had
        if (!strcmp(d, TREE)) break;
        snprintf(a, sizeof a, "%s.gone", d);
        if (rename(d, a)) break;
        add(a, 'd');
        o = any_of('s');
        if (inside(o) && rename(o, d)) {}
        break;
    default:  // a file where a directory was, or a hard link to a symlink, and back
        if (!strcmp(d, TREE)) break;
        if (rmdir(d)) break;
        o = any_of('s');
        switch (rnd(3)) {
        case 0: close(open(d, O_CREAT | O_WRONLY, 0644)); break;
        case 1:
            if (inside(o) && !link(o, d)) break;
            // fall through
        default:
            if (mkdir(d, 0755)) {}
        }
    }
}

// ---- The questions -------------------------------------------------------------------------

#define FDS 8
static int fds[FDS];
static char fdpath[FDS][MAXP];

static void make_path(char *out) {
    static const char *special[] = {"", ".", "..", "/", "//", "/..", "/.", "/usr/lib/libc.so.6", "/proc/self/stat",
                                    "/usr", "/usr/", "/root", "./", "../", "/bin/sh", "/usr/lib/../lib/libc.so.6"};
    if (!rnd(40)) {
        strcpy(out, special[rnd(sizeof special / sizeof *special)]);
        return;
    }
    if (!rnd(400)) {  // too long for anybody
        for (int i = 0; i < 2100; i++) memcpy(out + 2 * i, "/a", 3);
        return;
    }
    strcpy(out, rnd(12) ? any() : outside[rnd(OUTSIDE)]);
    for (int ops = rnd(4); ops--;) {
        char tmp[MAXP];
        size_t n = strlen(out);
        if (n > 1200) break;
        switch (rnd(8)) {
        case 0:
        case 1: sprintf(out + n, "/%s", base_of(any())); break;
        case 2: {
            static const char *tails[] = {"/", "/.", "/..", "//", "/missing", "/./.", "/../.."};
            strcat(out, tails[rnd(7)]);
            break;
        }
        case 3: snprintf(tmp, sizeof tmp, "%s/%s", any_of('s'), base_of(out)), strcpy(out, tmp); break;
        case 4:
        case 5: {  // something in the middle
            static const char *middles[] = {"/./", "//", "/../", "/missing/../", "///.//"};
            char *slash = out + rnd(n);
            while (*slash && *slash != '/') slash++;
            if (!*slash) break;
            snprintf(tmp, sizeof tmp, "%.*s%s%s", (int)(slash - out), out, middles[rnd(5)], slash + 1);
            strcpy(out, tmp);
            break;
        }
        case 6: {
            char *slash = out + 1 + rnd(n);
            while (*slash && *slash != '/') slash++;
            *slash = 0;
            break;
        }
        default: snprintf(tmp, sizeof tmp, "%s/%s", any_of('d'), base_of(out)), strcpy(out, tmp);
        }
    }
}

// The same path seen from a directory: as text only, which is all a question needs.
static void relative_to(char *out, const char *path, const char *dir) {
    size_t n = strlen(dir);
    out[0] = 0;
    if (!strncmp(path, dir, n) && (path[n] == '/' || n == 1)) {
        strcpy(out, path + n + (n > 1));
        return;
    }
    if (rnd(3))
        for (const char *c = dir; *c; c++)
            if (*c == '/' && c[1]) strcat(out, "../");
    strcat(out, path + 1);
}

static void questions_and_changes(long n, int changes) {
    char path[MAXP * 3], rel[MAXP * 3];
    for (long i = 0; i < n; i++) {
        if (changes && !rnd(400)) {
            if (rnd(2)) change();
            else {  // by another process
                fflush(stdout);
                pid_t pid = fork();
                if (!pid) change(), _exit(0);
                waitpid(pid, 0, 0);
            }
        }
        if (!rnd(300)) {
            const char *to = rnd(8) ? any() : outside[rnd(OUTSIDE)];
            if (!chdir(to)) strcpy(cwd, to);
        }
        if (!rnd(400)) {
            int k = rnd(FDS);
            if (rnd(4)) close(fds[k]);  // or keep it open, or ask about a closed one later
            strcpy(fdpath[k], rnd(8) ? any() : outside[rnd(OUTSIDE)]);
            fds[k] = open(fdpath[k], rnd(2) ? O_RDONLY : O_PATH);
        }
        make_path(path);
        int flags = rnd(3) ? 0 : AT_SYMLINK_NOFOLLOW;
        if (!rnd(50)) flags |= AT_NO_AUTOMOUNT;
        if (!rnd(200)) flags |= AT_EMPTY_PATH;
        switch (rnd(10)) {
        case 0:
        case 1:
        case 2:
            relative_to(rel, path, cwd);
            question_about(AT_FDCWD, "cwd", rel, flags);
            break;
        case 3:
        case 4: {
            int k = rnd(FDS);
            relative_to(rel, path, fdpath[k]);
            question_about(fds[k], fdpath[k], rel, flags);
            break;
        }
        case 5: question_about(fds[rnd(FDS)], "any", path, flags); break;
        default: question_about(AT_FDCWD, "cwd", path, flags);
        }
    }
}

static void *asker(void *seed) {
    char path[MAXP * 3];
    rng = (uint64_t)seed;
    for (long i = 0; i < 20000000 && !__atomic_load_n(&count, __ATOMIC_RELAXED); i++) {
        sprintf(path, "%s/%s", outside[rnd(4)], rnd(2) ? "libc.so.6" : rnd(2) ? "../bin/env" : "python3");
        question_about(AT_FDCWD, "cwd", path, rnd(2) ? 0 : AT_SYMLINK_NOFOLLOW);
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 4) return fprintf(stderr, "usage: fuzz run|ids|threads|poll ...\n"), 2;
    setvbuf(stdout, 0, _IOLBF, 0);
    libc_realpath = dlsym(dlopen("libc.so.6", RTLD_LAZY), "realpath");
    libc_getcwd = dlsym(dlopen("libc.so.6", RTLD_LAZY), "getcwd");
    if (!libc_realpath || !libc_getcwd) return fprintf(stderr, "no realpath in libc.so.6\n"), 2;
    long n = atol(argv[3]);
    rng = atol(argv[2]) * 0x9e3779b97f4a7c15ULL + 1;
    for (int i = 0; i < FDS; i++) fds[i] = -1, strcpy(fdpath[i], "/");

    if (!strcmp(argv[1], "poll")) {
        // A change made by another proot on the same rootfs must show at once.
        time_t until = time(0) + n;
        while (time(0) < until) {
            question_about(AT_FDCWD, "cwd", argv[2], 0);
            question_about(AT_FDCWD, "cwd", argv[2], AT_SYMLINK_NOFOLLOW);
        }
    } else if (!strcmp(argv[1], "threads")) {
        pthread_t threads[4];
        int none = 0;
        __atomic_store_n(&count, 0, __ATOMIC_RELAXED);
        for (long i = 0; i < 4; i++) pthread_create(&threads[i], 0, asker, (void *)(rng + i));
        // The askers run while count is 0; the tree is built and changed with a private count.
        pid_t pid = fork();
        if (!pid) {
            build();
            for (long i = 0; i < n; i++) change();
            _exit(0);
        }
        waitpid(pid, 0, 0);
        __atomic_store_n(&count, 1, __ATOMIC_RELAXED);
        for (int i = 0; i < 4; i++) pthread_join(threads[i], 0);
        (void)none;
    } else if (!strcmp(argv[1], "ids")) {
        build();
        questions_and_changes(n, 0);
        static const int steps[][3] = {{-1, 1000, 0}, {7, 7, 7}, {7, 8, 9}};
        for (int step = 0; step < 7; step++) {
            fflush(stdout);
            pid_t pid = fork();
            if (!pid) {
                const char *dir = step < 5 ? "" : any_of('d');
                if (step >= 5) printf("step %d: on \"%s\"\n", step, dir);
                int r = step < 3 ? setresgid(steps[step][0], steps[step][1], steps[step][2])
                                 : step < 5 ? setresuid(steps[step - 3][0], steps[step - 3][1], steps[step - 3][2])
                                 : step < 6 ? chroot(dir)
                                            // proot emulates this one as a new binding
                                            : mount("/usr/lib", dir, 0, MS_BIND, 0);
                if (step == 5 && !r && chdir("/")) {}
                printf("step %d: %s\n", step, r ? strerror(errno) : "ok");
                long before = diffs, asked = questions, answered = fastpath_stats ? fastpath_stats[0] : 0;
                questions_and_changes(n, 0);
                if (step == 4 && setuid(7)) {}
                questions_and_changes(n / 4, 0);
                struct stat st;
                if (stat("/usr/lib/libc.so.6", &st)) st.st_uid = st.st_gid = -1;
                printf("step %d: %ld questions, the library answered %ld, %ld differences; libc.so.6 is owned by %d:%d\n", step,
                       questions - asked, fastpath_stats ? fastpath_stats[0] - answered : -1, diffs - before, st.st_uid, st.st_gid);
                _exit(diffs > before);
            }
            int status;
            waitpid(pid, &status, 0);
            if (status) diffs++;
        }
    } else {
        hostroot = argc > 4 ? argv[4] : 0;
        build();
        questions_and_changes(n, 1);
    }

    printf("%ld questions: %ld found, %ld ENOENT, %ld ENOTDIR, %ld other errors; %ld unstable, %ld differences\n", questions,
           classes[0], classes[1], classes[2], classes[3], unstable, diffs);
    for (int k = 0; k < KINDS; k++) printf("%s %ld%s", kinds[k], asked[k], k + 1 < KINDS ? ", " : "\n");
    if (fastpath_stats) printf("library answered %lu, handed %lu to proot\n", fastpath_stats[0], fastpath_stats[1]);
    else printf("library without counters, or not loaded\n");
    return diffs != 0;
}
