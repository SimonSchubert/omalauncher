// Omalauncher's Android command broker. See docs/ANDROID_INTEGRATION.md.
// Single-threaded supervisor + one isolated worker per request: never fork ART.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define MAX_CLIENTS 16
#define MAX_LINE 65536
#define MAX_HEADER (256 * 1024)
#define MAX_ARGS 1024
#define MAX_ENV 1024

static long long millis(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

// An absolute deadline prevents a client holding a worker by trickling bytes.
static int line(int fd, char *out, size_t cap, long long deadline) {
    size_t n = 0;
    while (n + 1 < cap) {
        long long left = deadline - millis();
        if (left <= 0) return -1;
        struct pollfd p[2] = {{fd, POLLIN, 0}, {STDIN_FILENO, POLLHUP, 0}};
        int r = poll(p, 2, (int)left);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0 || p[1].revents) return -1;
        char c;
        if (read(fd, &c, 1) != 1 || c == '\0') return -1;
        if (c == '\n') { out[n] = 0; return (int)n; }
        out[n++] = c;
    }
    return -1;
}

static int decode(char *s) {
    char *out = s;
    for (char *in = s; *in; in++) {
        if (*in != '\\') { *out++ = *in; continue; }
        in++;
        if (*in == 'n') *out++ = '\n';
        else if (*in == 'r') *out++ = '\r';
        else if (*in == '\\') *out++ = '\\';
        else return -1;
    }
    *out = 0;
    return 0;
}

static int receive_fds(int sock, int fds[4]) {
    char byte;
    struct iovec io = {&byte, 1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(16 * sizeof(int))]; } control;
    struct msghdr msg = {0};
    msg.msg_iov = &io;
    msg.msg_iovlen = 1;
    msg.msg_control = control.bytes;
    msg.msg_controllen = sizeof(control.bytes);
    ssize_t n = recvmsg(sock, &msg, MSG_CMSG_CLOEXEC);
    int count = 0;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); n > 0 && c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS) continue;
        size_t size = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        int *received = (int *)CMSG_DATA(c);
        for (size_t i = 0; i < size; i++) {
            if (count < 4) fds[count] = received[i]; else close(received[i]);
            count++;
        }
    }
    return n == 1 && byte == 'F' && count == 4 && !(msg.msg_flags & (MSG_CTRUNC | MSG_TRUNC)) ? 0 : -1;
}

static void reply(int sock, int code) {
    char response[32];
    int n = snprintf(response, sizeof(response), "EXIT %d\n", code);
    (void)send(sock, response, (size_t)n, MSG_NOSIGNAL);
}

static void serve_client(int sock) {
    int fds[4] = {-1, -1, -1, -1};
    char *args[MAX_ARGS + 1] = {0};
    char *env[MAX_ENV] = {0};
    int argc = 0, envc = 0, code = 125;
    const char *failure = "invalid descriptor message";
    char *buffer = malloc(MAX_LINE);
    if (!buffer) { reply(sock, code); return; }
    long long deadline = millis() + 5000;
    struct timeval timeout = {5, 0};
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    if (receive_fds(sock, fds) != 0) goto done;
    failure = "invalid protocol version or handshake timeout";
    if (line(sock, buffer, MAX_LINE, deadline) < 0 || strcmp(buffer, "TAWCANDO 1")) goto done;
    size_t total = 0;
    for (;;) {
        failure = "invalid or oversized request header";
        int n = line(sock, buffer, MAX_LINE, deadline);
        if (n < 0 || (total += (size_t)n + 1) > MAX_HEADER) goto done;
        if (n == 0) break;
        if (!strncmp(buffer, "ARGV ", 5) && argc < MAX_ARGS) {
            if (decode(buffer + 5)) goto done;
            args[argc] = strdup(buffer + 5);
            if (!args[argc++]) goto done;
        } else if (!strncmp(buffer, "ENV ", 4) && envc < MAX_ENV) {
            failure = "invalid environment entry";
            char *eq = strchr(buffer + 4, '=');
            if (!eq || eq == buffer + 4 || memchr(buffer + 4, '\r', (size_t)(eq - buffer - 4))) goto done;
            if (decode(eq + 1)) goto done;
            env[envc] = strdup(buffer + 4);
            if (!env[envc++]) goto done;
        } else goto done;
    }
    if (!argc || !*args[0]) goto done;
    failure = "cannot configure command environment";
    // The worker has no threads. Environment mutation and execvp are safe here.
    unsetenv("LD_PRELOAD");
    unsetenv("LD_LIBRARY_PATH");
    if (!getenv("PATH")) setenv("PATH", "/system/bin:/system/xbin", 1);
    for (int i = 0; i < envc; i++) {
        char *eq = strchr(env[i], '=');
        *eq = 0;
        if (setenv(env[i], eq + 1, 1)) goto done;
    }
    pid_t owner = getpid();
    pid_t child = fork();
    if (child < 0) { code = 126; goto done; }
    if (!child) {
        // If a worker is killed unexpectedly, its direct command must die too.
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        if (getppid() != owner) _exit(126);
        for (int i = 0; i < 3; i++) if (dup2(fds[i], i) < 0) _exit(126);
        if (setpgid(0, 0) || fchdir(fds[3])) {
            dprintf(STDERR_FILENO, "ando: cannot enter working directory: %s\n", strerror(errno));
            _exit(126);
        }
        for (int i = 0; i < 4; i++) close(fds[i]);
        signal(SIGPIPE, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGTERM, SIG_DFL);
        execvp(args[0], args);
        int err = errno;
        dprintf(STDERR_FILENO, "ando: %s: %s\n", args[0], strerror(err));
        _exit(err == ENOENT ? 127 : 126);
    }
    setpgid(child, child);
    failure = NULL;
    for (int i = 0; i < 4; i++) { close(fds[i]); fds[i] = -1; }
    for (;;) {
        // WNOWAIT reserves the pid until group cleanup; never signal a recycled pgid.
        siginfo_t info = {0};
        if (waitid(P_PID, (id_t)child, &info, WEXITED | WNOHANG | WNOWAIT) < 0) break;
        if (info.si_pid) {
            code = info.si_code == CLD_EXITED ? info.si_status : 128 + info.si_status;
            break;
        }
        struct pollfd p[2] = {{sock, POLLIN, 0}, {STDIN_FILENO, POLLHUP, 0}};
        int r = poll(p, 2, 50);
        if (r < 0 && errno == EINTR) continue;
        if (r < 0 || p[1].revents || (p[0].revents & (POLLHUP | POLLERR | POLLNVAL))) break;
        if (p[0].revents & POLLIN) {
            if (line(sock, buffer, 64, millis() + 1000) < 0) break;
            int sig = 0, used = 0;
            if (sscanf(buffer, "SIG %d%n", &sig, &used) != 1 || buffer[used] ||
                (sig != SIGINT && sig != SIGTERM && sig != SIGHUP && sig != SIGQUIT)) break;
            kill(-child, sig);
        }
    }
    kill(-child, SIGKILL);
    while (waitpid(child, NULL, 0) < 0 && errno == EINTR) {}
done:
    if (failure && fds[2] >= 0) {
        // A malformed caller must not stall shutdown with a full stderr pipe.
        struct pollfd output = {fds[2], POLLOUT, 0};
        if (poll(&output, 1, 100) > 0 && (output.revents & POLLOUT)) dprintf(fds[2], "ando: %s\n", failure);
    }
    for (int i = 0; i < 4; i++) if (fds[i] >= 0) close(fds[i]);
    for (int i = 0; i < argc; i++) free(args[i]);
    for (int i = 0; i < envc; i++) free(env[i]);
    free(buffer);
    reply(sock, code);
}

int ando_broker(const char *path) {
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    if (strlen(path) >= sizeof(addr.sun_path)) return 126;
    strcpy(addr.sun_path, path);
    int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server < 0) return 126;
    umask(0077);
    unlink(path);
    if (bind(server, (struct sockaddr *)&addr, sizeof(addr)) || listen(server, MAX_CLIENTS)) {
        perror("ando: broker"); close(server); return 126;
    }
    signal(SIGPIPE, SIG_IGN);
    pid_t workers[MAX_CLIENTS] = {0};
    puts("READY"); fflush(stdout);
    for (;;) {
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (workers[i] && waitpid(workers[i], NULL, WNOHANG) == workers[i]) workers[i] = 0;
        }
        struct pollfd p[2] = {{server, POLLIN, 0}, {STDIN_FILENO, POLLIN | POLLHUP, 0}};
        int r = poll(p, 2, 100);
        if (r < 0 && errno == EINTR) continue;
        // stdin's write end belongs only to the app. Closing it stops all workers too.
        if (r < 0 || p[1].revents) break;
        if (!(p[0].revents & POLLIN)) continue;
        int client = accept4(server, NULL, NULL, SOCK_CLOEXEC);
        if (client < 0) continue;
        struct ucred cred;
        socklen_t len = sizeof(cred);
        int slot = 0;
        while (slot < MAX_CLIENTS && workers[slot]) slot++;
        if (getsockopt(client, SOL_SOCKET, SO_PEERCRED, &cred, &len) || cred.uid != getuid() || slot == MAX_CLIENTS) {
            close(client); continue;
        }
        pid_t worker = fork();
        if (!worker) {
            close(server);
            serve_client(client);
            close(client);
            _exit(0);
        }
        if (worker > 0) workers[slot] = worker;
        close(client);
    }
    close(server);
    unlink(path);
    for (int i = 0; i < MAX_CLIENTS; i++) if (workers[i]) {
        while (waitpid(workers[i], NULL, 0) < 0 && errno == EINTR) {}
    }
    return 0;
}
