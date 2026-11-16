// What a traced syscall costs without proot's own work: a tracer that does nothing.
// nulltrace <mode> <iterations> [cpu]     prints nanoseconds per stat("/")
//   native   no filter, no tracer
//   cont     seccomp trace stop at entry, tracer continues at once (1 stop)
//   exit     stop at entry and at exit (2 stops), like proot for stat under -0 or --link2symlink
//   regs     as exit, and the tracer reads and writes the registers at both stops
//   work     as regs, and the tracer reads the path from the tracee and lstats it per component,
//            a stand-in for proot's canonicalization
// Tracer and tracee are pinned to [cpu] (default: the last one), as patch 0002 does.
// Build: NDK clang -O2 -static nulltrace.c -o nulltrace; run as the adb shell user.
#define _GNU_SOURCE
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static long now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000000000L + t.tv_nsec;
}

static void child(int filtered, long n) {
    struct stat st;
    if (filtered) {
        ptrace(PTRACE_TRACEME, 0, 0, 0);
        raise(SIGSTOP);
        struct sock_filter f[] = {
            BPF_STMT(BPF_LD + BPF_W + BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_newfstatat, 0, 1),
            BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRACE),
            BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_fprog p = { sizeof f / sizeof *f, f };
        prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
        if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &p)) { perror("seccomp"); _exit(1); }
    }
    long t0 = now_ns();
    for (long i = 0; i < n; i++) stat("/system/lib64/libc.so", &st);
    printf("%.0f\n", (double)(now_ns() - t0) / n);
    fflush(stdout);
    _exit(0);
}

int main(int argc, char **argv) {
    if (argc < 3) return fprintf(stderr, "usage: nulltrace native|cont|exit|regs|work n [cpu]\n"), 2;
    const char *mode = argv[1];
    long n = atol(argv[2]);
    int cpu = argc > 3 ? atoi(argv[3]) : (int)sysconf(_SC_NPROCESSORS_CONF) - 1;
    cpu_set_t set;
    CPU_ZERO(&set);
    CPU_SET(cpu, &set);
    if (cpu >= 0) sched_setaffinity(0, sizeof set, &set);

    int native = !strcmp(mode, "native");
    int exits = strcmp(mode, "cont") != 0, regs = !strcmp(mode, "regs") || !strcmp(mode, "work");
    int work = !strcmp(mode, "work");
    pid_t pid = fork();
    if (pid == 0) child(!native, n);
    int status, first = 1, at_exit = 0;
    while (waitpid(pid, &status, __WALL) > 0) {
        if (WIFEXITED(status) || WIFSIGNALED(status)) break;
        if (first) {
            first = 0;
            ptrace(PTRACE_SETOPTIONS, pid, 0, PTRACE_O_TRACESECCOMP | PTRACE_O_TRACESYSGOOD);
            ptrace(PTRACE_CONT, pid, 0, 0);
            continue;
        }
        int sig = WSTOPSIG(status);
        int seccomp = status >> 8 == (SIGTRAP | PTRACE_EVENT_SECCOMP << 8);
        int sysstop = sig == (SIGTRAP | 0x80);
        if (!seccomp && !sysstop) { ptrace(PTRACE_CONT, pid, 0, sig); continue; }
        if (regs) {
            struct user_regs_struct r;
            struct iovec io = { &r, sizeof r };
            ptrace(PTRACE_GETREGSET, pid, NT_PRSTATUS, &io);
            if (work && seccomp) {
                char path[4096], part[4096];
                struct iovec l = { path, sizeof path }, rem = { (void *)r.regs[1], sizeof path };
                // Like proot: the read may cross into an unmapped page, so read up to the page end.
                rem.iov_len = 4096 - (r.regs[1] & 4095);
                process_vm_readv(pid, &l, 1, &rem, 1, 0);
                struct stat st;
                for (char *s = path + 1;; s++) {
                    if (*s == '/' || !*s) {
                        memcpy(part, path, s - path);
                        part[s - path] = 0;
                        lstat(part, &st);
                        if (!*s) break;
                    }
                }
            }
            ptrace(PTRACE_SETREGSET, pid, NT_PRSTATUS, &io);
        }
        if (seccomp && exits) { at_exit = 1; ptrace(PTRACE_SYSCALL, pid, 0, 0); }
        else { at_exit = 0; ptrace(PTRACE_CONT, pid, 0, 0); }
    }
    (void)at_exit;
    return 0;
}
