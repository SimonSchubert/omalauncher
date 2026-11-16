// Syscall microbenchmark for proot: prints nanoseconds per operation.
// pbench <op> <iterations> [arg] [parallel]
//   getpid           not intercepted by proot's seccomp filter: the floor
//   stat <path>      path translation on every call
//   lstat <path>
//   nostat <path>    stat of a path that must not exist
//   open <path>      open + close
//   noopen <path>    open of a path that must not exist
//   statx <path>
//   access <path>
//   fstat <path>     of a descriptor opened once
//   getcwd           needs proot's exit-stage fixup
//   readlink <path>
//   exec <path>      fork + execve + wait
// With parallel > 1, that many processes run the loop at once and the result is
// wall time per op over all of them (throughput), which shows proot's single tracer.
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>

static long now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000000000L + t.tv_nsec;
}

static int loop(const char *op, long n, const char *arg) {
    struct stat st;
    char buf[4096];
    struct statx stx;
    int fd = !strcmp(op, "fstat") ? open(arg, O_RDONLY) : -1;
    for (long i = 0; i < n; i++) {
        if (!strcmp(op, "getpid")) syscall(SYS_getpid);
        else if (!strcmp(op, "stat")) { if (stat(arg, &st)) return perror(arg), 1; }
        else if (!strcmp(op, "lstat")) { if (lstat(arg, &st)) return perror(arg), 1; }
        else if (!strcmp(op, "nostat")) { if (!stat(arg, &st)) return fprintf(stderr, "%s exists\n", arg), 1; }
        else if (!strcmp(op, "open")) { int fd = open(arg, O_RDONLY); if (fd < 0) return perror(arg), 1; close(fd); }
        else if (!strcmp(op, "noopen")) { if (open(arg, O_RDONLY) >= 0) return fprintf(stderr, "%s exists\n", arg), 1; }
        else if (!strcmp(op, "statx")) { if (statx(AT_FDCWD, arg, 0, STATX_BASIC_STATS, &stx)) return perror(arg), 1; }
        else if (!strcmp(op, "access")) { if (access(arg, R_OK)) return perror(arg), 1; }
        else if (!strcmp(op, "fstat")) { if (fstat(fd, &st)) return perror(arg), 1; }
        else if (!strcmp(op, "getcwd")) { if (!getcwd(buf, sizeof buf)) return perror("getcwd"), 1; }
        else if (!strcmp(op, "readlink")) { if (readlink(arg, buf, sizeof buf) < 0) return perror(arg), 1; }
        else if (!strcmp(op, "exec")) {
            pid_t p = fork();
            if (p == 0) { execl(arg, arg, (char *)0); _exit(127); }
            int s; waitpid(p, &s, 0);
            if (!WIFEXITED(s) || WEXITSTATUS(s)) return fprintf(stderr, "exec %s failed\n", arg), 1;
        } else return fprintf(stderr, "unknown op %s\n", op), 2;
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 3) return fprintf(stderr, "usage: pbench op n [arg] [parallel]\n"), 2;
    const char *op = argv[1], *arg = argc > 3 ? argv[3] : "/";
    long n = atol(argv[2]);
    int par = argc > 4 ? atoi(argv[4]) : 1;
    long t0 = now_ns();
    if (par <= 1) {
        if (loop(op, n, arg)) return 1;
    } else {
        for (int i = 0; i < par; i++) if (fork() == 0) _exit(loop(op, n, arg));
        int s, bad = 0;
        while (wait(&s) > 0) bad |= !WIFEXITED(s) || WEXITSTATUS(s);
        if (bad) return 1;
    }
    printf("%.0f\n", (double)(now_ns() - t0) / ((double)n * par));
    return 0;
}
