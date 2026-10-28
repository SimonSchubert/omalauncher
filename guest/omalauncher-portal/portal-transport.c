/* Shared bounded AF_UNIX/SCM_RIGHTS transport for the D-Bus service and the
 * standalone CUPS-PDF postprocessor. No pathname is an Android file capability. */
#include "portals.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

static gboolean transport_error(GError **error, const gchar *what) {
    gint saved = errno;
    g_set_error(error, G_IO_ERROR, g_io_error_from_errno(saved), "%s: %s", what, g_strerror(saved));
    return FALSE;
}
gint portal_transport_connect(const gchar *runtime, GError **error) {
    gchar name[sizeof(((struct sockaddr_un *)0)->sun_path)] = {0};
    gchar *path = g_build_filename(runtime, "omalauncher-portal-socket", NULL);
    gint input = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK); g_free(path);
    if (input < 0) { transport_error(error, "Read portal endpoint"); return -1; }
    struct stat st;
    if (fstat(input, &st) || !S_ISREG(st.st_mode) || st.st_size <= 0 || st.st_size >= (off_t)sizeof name) {
        close(input); g_set_error_literal(error, G_IO_ERROR, G_IO_ERROR_INVALID_DATA, "Invalid portal endpoint file"); return -1;
    }
    ssize_t count = read(input, name, sizeof name - 1); close(input);
    if (count <= 0) { g_set_error_literal(error, G_IO_ERROR, G_IO_ERROR_INVALID_DATA, "Empty portal endpoint"); return -1; }
    g_strchomp(name);
    if (!*name) { g_set_error_literal(error, G_IO_ERROR, G_IO_ERROR_INVALID_DATA, "Empty portal endpoint"); return -1; }
    gint fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) { transport_error(error, "Create portal socket"); return -1; }
    struct timeval timeout = { .tv_sec = 5 };
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof timeout);
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    memcpy(address.sun_path + 1, name, strlen(name));
    if (connect(fd, (struct sockaddr *)&address, offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name)) != 0) {
        transport_error(error, "Connect Android portal"); close(fd); return -1;
    }
    return fd;
}
gboolean portal_transport_send(gint fd, const gchar *packet, gsize length, const gint *fds, guint n_fds, GError **error) {
    if (!length || length > 65536 || n_fds > 8) {
        g_set_error_literal(error, G_IO_ERROR, G_IO_ERROR_INVALID_ARGUMENT, "Portal packet limit exceeded"); return FALSE;
    }
    guint32 size = htonl((guint32)length);
    struct iovec iov[] = { { &size, sizeof size }, { (gpointer)packet, length } };
    union { struct cmsghdr alignment; char bytes[CMSG_SPACE(sizeof(gint) * 8)]; } control = {0};
    struct msghdr message = { .msg_iov = iov, .msg_iovlen = G_N_ELEMENTS(iov) };
    if (n_fds) {
        message.msg_control = control.bytes; message.msg_controllen = CMSG_SPACE(sizeof(gint) * n_fds);
        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&message);
        cmsg->cmsg_level = SOL_SOCKET; cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof(gint) * n_fds); memcpy(CMSG_DATA(cmsg), fds, sizeof(gint) * n_fds);
    }
    ssize_t sent;
    do { sent = sendmsg(fd, &message, MSG_NOSIGNAL); } while (sent < 0 && errno == EINTR);
    if (sent < 0) return transport_error(error, "Send portal packet");
    const gsize total = sizeof size + length;
    while ((gsize)sent < total) {
        const gchar *p = (gsize)sent < sizeof size ? (const gchar *)&size + sent : packet + sent - sizeof size;
        gsize remaining = (gsize)sent < sizeof size ? sizeof size - sent : total - sent;
        ssize_t written = send(fd, p, remaining, MSG_NOSIGNAL);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return transport_error(error, "Complete portal packet");
        sent += written;
    }
    return TRUE;
}
void portal_packet_field(GString *packet, const gchar *key, const gchar *value) {
    gchar *encoded = g_base64_encode((const guchar *)value, strlen(value));
    g_string_append_printf(packet, "%s=%s\n", key, encoded); g_free(encoded);
}
