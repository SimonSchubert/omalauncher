/* CUPS-PDF PostProcessing hook: forwards the real finished PDF descriptor.
 * Reports Android PrintJob's terminal outcome to the wrapper's private pipe.
 * Never deletes the PDF on failure; never interprets a shell command or URI. */
#include "portals.h"
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

static volatile sig_atomic_t canceled;
static volatile sig_atomic_t active_socket = -1;
static void cancel_print(int signal_number) {
    canceled = 1;
    if (active_socket >= 0) shutdown(active_socket, SHUT_RDWR);
}
static gint result_descriptor(void) {
    const gchar *text = g_getenv("OMALAUNCHER_PRINT_RESULT_FD");
    guint64 value;
    return text && g_ascii_string_to_unsigned(text, 10, 5, 1048575, &value, NULL) ? (gint)value : -1;
}
int main(int argc, char **argv) {
    const gchar *filename = argc == 3 && g_str_equal(argv[1], "--file") ? argv[2] : argc >= 2 ? argv[1] : NULL;
    gint report = result_descriptor(), socket_fd = -1, input = -1;
    guint status = 2;
    GError *error = NULL;
    signal(SIGPIPE, SIG_IGN);
    struct sigaction action = { .sa_handler = cancel_print };
    sigemptyset(&action.sa_mask); sigaction(SIGTERM, &action, NULL); sigaction(SIGINT, &action, NULL);
    if (!filename) goto done;
    if (report >= 0) {
        struct pollfd parent = { .fd = report, .events = POLLERR | POLLHUP };
        if (poll(&parent, 1, 0) < 0 || parent.revents & (POLLERR | POLLHUP | POLLNVAL)) { status = 1; goto done; }
    }
    input = open(filename, O_RDONLY | O_CLOEXEC | O_NONBLOCK | O_NOFOLLOW);
    struct stat st; gchar magic[5];
    if (input < 0 || fstat(input, &st) || !S_ISREG(st.st_mode) || pread(input, magic, sizeof magic, 0) != sizeof magic || memcmp(magic, "%PDF-", sizeof magic)) goto done;
    const gchar *runtime = g_getenv("XDG_RUNTIME_DIR");
    if (!runtime || !*runtime) runtime = "/run/user/0";
    socket_fd = portal_transport_connect(runtime, &error);
    if (socket_fd < 0) goto done;
    active_socket = socket_fd;
    if (canceled) goto done;
    GString *packet = g_string_new(NULL);
    portal_packet_field(packet, "version", "1"); portal_packet_field(packet, "operation", "print");
    portal_packet_field(packet, "fd-count", "1");
    const gchar *title = g_getenv("OMALAUNCHER_PRINT_TITLE");
    gchar *basename = g_path_get_basename(filename);
    portal_packet_field(packet, "title", title && *title && strlen(title) < 4096 ? title : basename);
    g_free(basename);
    gboolean sent = portal_transport_send(socket_fd, packet->str, packet->len, &input, 1, &error);
    g_string_free(packet, TRUE);
    if (!sent) goto done;
    const gint64 deadline = g_get_monotonic_time() + 15 * G_TIME_SPAN_MINUTE;
    gchar reply[8]; guint used = 0;
    while (!canceled && g_get_monotonic_time() < deadline) {
        struct pollfd pending[] = {
            { .fd = socket_fd, .events = POLLIN },
            { .fd = report, .events = POLLERR | POLLHUP }
        };
        gint ready = poll(pending, G_N_ELEMENTS(pending), 1000);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) break;
        if (report >= 0 && pending[1].revents & (POLLERR | POLLHUP | POLLNVAL)) { status = 1; break; }
        if (!(pending[0].revents & (POLLIN | POLLHUP | POLLERR | POLLNVAL))) continue;
        ssize_t count = read(socket_fd, reply + used, 1);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        used++;
        if (reply[used - 1] == '\n') {
            if (used == 2 && reply[0] >= '0' && reply[0] <= '2') status = reply[0] - '0';
            break;
        }
        if (used == sizeof reply) break;
    }
done:
    if (canceled) status = 1;
    active_socket = -1;
    if (socket_fd >= 0) close(socket_fd);
    if (input >= 0) close(input);
    if (error) { g_printerr("Android print handoff: %s\n", error->message); g_clear_error(&error); }
    if (report >= 0) { gchar value = '0' + status; ssize_t ignored = write(report, &value, 1); (void)ignored; }
    return status == 0 ? 0 : status == 1 ? 5 : 1;
}
