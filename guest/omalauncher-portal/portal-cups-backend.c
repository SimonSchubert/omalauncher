/* CUPS backend wrapper for the private Android queue. CUPS-PDF deliberately
 * ignores PostProcessing's exit status; the inherited pipe makes that result
 * authoritative for CUPS job completion without guessing a PDF output path. */
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <unistd.h>

static volatile sig_atomic_t canceled;
static volatile sig_atomic_t child_pid;
static void cancel_job(int signal_number) {
    canceled = 1;
    if (child_pid > 0) kill(child_pid, SIGTERM);
}
/* Never let an empty/failed filter stream or raw PDF become a blank "success"
 * in CUPS-PDF's PostScript-only, text-based spool reader. Stage before starting
 * that backend; otherwise later filter failure could already have launched UI. */
static int stage_postscript(int argc, char **argv) {
    FILE *source = argc == 7 ? fopen(argv[6], "rb") : stdin;
    if (!source) { perror("ERROR: Read PostScript source"); return 0; }
    const char *directory = getenv("TMPDIR");
    char path[4096];
    int length = snprintf(path, sizeof path, "%s/omalauncher-postscript-XXXXXX", directory && *directory ? directory : "/tmp");
    int descriptor = length > 0 && (size_t)length < sizeof path ? mkstemp(path) : -1;
    if (descriptor < 0) { if (argc == 7) fclose(source); return 0; }
    if (unlink(path) != 0) { close(descriptor); if (argc == 7) fclose(source); return 0; }
    FILE *staged = fdopen(descriptor, "w+b");
    if (!staged) { close(descriptor); if (argc == 7) fclose(source); return 0; }
    unsigned char prefix[2], buffer[65536];
    int ok = !canceled && fread(prefix, 1, sizeof prefix, source) == sizeof prefix && prefix[0] == '%' && prefix[1] == '!';
    size_t total = sizeof prefix;
    if (ok) ok = fwrite(prefix, 1, sizeof prefix, staged) == sizeof prefix;
    while (ok && !canceled) {
        size_t count = fread(buffer, 1, sizeof buffer, source);
        if (!count) break;
        if (total + count > 256u * 1024u * 1024u || fwrite(buffer, 1, count, staged) != count) { ok = 0; break; }
        total += count;
    }
    ok = ok && !canceled && !ferror(source) && fseek(staged, 0, SEEK_SET) == 0;
    if (argc == 7) fclose(source);
    if (ok) ok = dup2(fileno(staged), STDIN_FILENO) >= 0;
    fclose(staged);
    if (!ok) fputs("ERROR: Expected a complete, bounded PostScript document\n", stderr);
    else argv[6] = NULL; /* CUPS-PDF reads the validated snapshot from stdin. */
    return ok;
}
int main(int argc, char **argv) {
    if (argc == 1) {
        puts("direct omalauncher-pdf:/ \"Android print services\" \"CUPS-PDF to Android\""); return 0;
    }
    if (argc != 6 && argc != 7) { fputs("ERROR: Invalid CUPS job arguments\n", stderr); return 1; }
    struct sigaction action = { .sa_handler = cancel_job };
    sigemptyset(&action.sa_mask); sigaction(SIGTERM, &action, NULL); sigaction(SIGINT, &action, NULL);
    if (!stage_postscript(argc, argv)) return canceled ? 5 : 1;
    int channel[2];
    if (pipe(channel) != 0) { perror("ERROR: Print-result pipe"); return 1; }
    int report = fcntl(channel[1], F_DUPFD, 5); /* Never borrow CUPS back/side-channel fds 3 or 4. */
    close(channel[1]); channel[1] = report;
    if (report < 0) { close(channel[0]); return 1; }
    fcntl(channel[0], F_SETFD, FD_CLOEXEC);
    char descriptor[32]; snprintf(descriptor, sizeof descriptor, "%d", channel[1]);
    setenv("OMALAUNCHER_PRINT_RESULT_FD", descriptor, 1);
    setenv("OMALAUNCHER_PRINT_TITLE", argv[3], 1);
    /* CUPS filters have consumed page, copy and colour settings. Backend-level
     * job overrides must not replace the hook, PDF directory or safe GS call. */
    argv[5] = "Label=1 PostProcessing=/usr/local/lib/omalauncher/omalauncher-print-android";
    setenv("DEVICE_URI", "cups-pdf:/omalauncher", 1);
    pid_t child = fork();
    if (child == 0) {
        close(channel[0]);
        execv("/usr/lib/cups/backend/cups-pdf", argv);
        perror("ERROR: Execute CUPS-PDF"); _exit(1);
    }
    close(channel[1]);
    if (child < 0) { close(channel[0]); perror("ERROR: Fork CUPS-PDF"); return 1; }
    child_pid = child;
    if (canceled) kill(child, SIGTERM);
    int status; pid_t waited;
    do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
    child_pid = 0;
    fcntl(channel[0], F_SETFL, O_NONBLOCK);
    char answer = 0;
    ssize_t count = read(channel[0], &answer, 1); close(channel[0]);
    if (canceled || (count == 1 && answer == '1')) return 5; /* CUPS_BACKEND_CANCEL */
    if (waited != child || !WIFEXITED(status) || WEXITSTATUS(status) != 0 || count != 1 || answer != '0') {
        fputs("ERROR: Android print did not complete; refusing a false CUPS success\n", stderr); return 1;
    }
    return 0;
}
