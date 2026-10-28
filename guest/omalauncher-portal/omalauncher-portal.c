/*
 * omalauncher-portal: the file chooser portal (org.freedesktop.portal.FileChooser)
 * and the notification service (org.freedesktop.Notifications) for Linux apps
 * in omalauncher.
 *
 * It's the portal service itself, not a backend for xdg-desktop-portal: that
 * one identifies each caller through /proc/<pid>/root, which under proot
 * names the host path and can't be opened, so it refuses every call.
 *
 * OpenFile and SaveFile ask the launcher's Files app to pick (through the
 * open bridge FIFO, as omarchy-edit asks the editor), and the picked files
 * come back as file URIs in the request's Response signal. Picking happens in
 * Linux's own file system, so the paths are real.
 *
 * Request, one line on the bridge:
 *   omarchy-pick:<reply fifo>\t<open|folder|save>\t<1|0 multiple>\t<folder>\t<name>\t<title>
 * Reply, on the reply FIFO: the picked paths, one per line; an empty line is a cancel.
 */
#include "portals.h"
#include <errno.h>
#include <fcntl.h>
#include <gio/gio.h>
#include <gio/gunixinputstream.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define BUS_NAME "org.freedesktop.portal.Desktop"
#define OBJECT_PATH "/org/freedesktop/portal/desktop"

static const char *runtime_dir;
static GDBusConnection *bus;
static GDBusNodeInfo *request_info;

static const gchar introspection_xml[] =
    "<node>"
    "  <interface name='org.freedesktop.portal.FileChooser'>"
    "    <method name='OpenFile'>"
    "      <arg type='s' name='parent_window' direction='in'/>"
    "      <arg type='s' name='title' direction='in'/>"
    "      <arg type='a{sv}' name='options' direction='in'/>"
    "      <arg type='o' name='handle' direction='out'/>"
    "    </method>"
    "    <method name='SaveFile'>"
    "      <arg type='s' name='parent_window' direction='in'/>"
    "      <arg type='s' name='title' direction='in'/>"
    "      <arg type='a{sv}' name='options' direction='in'/>"
    "      <arg type='o' name='handle' direction='out'/>"
    "    </method>"
    "    <property name='version' type='u' access='read'/>"
    "  </interface>"
    "</node>";

static const gchar request_xml[] =
    "<node>"
    "  <interface name='org.freedesktop.portal.Request'>"
    "    <method name='Close'/>"
    "    <signal name='Response'>"
    "      <arg type='u' name='response'/>"
    "      <arg type='a{sv}' name='results'/>"
    "    </signal>"
    "  </interface>"
    "</node>";

/* Tabs and newlines would break the one-line request. */
static gchar *field(const gchar *s) {
    gchar *f = g_strdup(s ? s : "");
    for (gchar *p = f; *p; p++)
        if (*p == '\t' || *p == '\n' || *p == '\r') *p = ' ';
    return f;
}

/* An "ay" option (a NUL-terminated path), or NULL. */
static gchar *bytes_option(GVariant *options, const char *key) {
    GVariant *v = g_variant_lookup_value(options, key, G_VARIANT_TYPE_BYTESTRING);
    if (!v) return NULL;
    /* Usually NUL-terminated; take it either way. */
    gsize len = 0;
    const gchar *data = g_variant_get_fixed_array(v, &len, 1);
    gchar *s = g_strndup(data, len);
    g_variant_unref(v);
    return s;
}

/* Asks the launcher; returns the picked paths (NULL-terminated, maybe empty), or NULL if it couldn't ask. */
static gchar **pick(const char *mode, gboolean multiple, const char *folder, const char *name, const char *title, GCancellable *cancellable) {
    if (g_cancellable_is_cancelled(cancellable)) return NULL;
    gchar *reply = g_strdup_printf("%s/pick.XXXXXX", runtime_dir);
    int tmp = g_mkstemp(reply);
    if (tmp < 0) { g_free(reply); return NULL; }
    close(tmp);
    unlink(reply);
    if (mkfifo(reply, 0600) != 0) { g_free(reply); return NULL; }
    int reader = open(reply, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    int keeper = reader >= 0 ? open(reply, O_WRONLY | O_NONBLOCK | O_CLOEXEC) : -1;
    if (reader < 0 || keeper < 0) {
        if (reader >= 0) close(reader);
        unlink(reply); g_free(reply); return NULL;
    }

    gchar *f = field(folder), *n = field(name), *t = field(title);
    gchar *line = g_strdup_printf("omarchy-pick:%s\t%s\t%d\t%s\t%s\t%s\n", reply, mode, multiple ? 1 : 0, f, n, t);
    g_free(f), g_free(n), g_free(t);

    /* Not blocking: with no launcher reading the bridge, there's nobody to ask. */
    gchar *bridge = g_strdup_printf("%s/open.fifo", runtime_dir);
    int fd = open(bridge, O_WRONLY | O_NONBLOCK);
    g_free(bridge);
    long atomic_limit = fd >= 0 ? fpathconf(fd, _PC_PIPE_BUF) : -1;
    gboolean sent = atomic_limit > 0 && strlen(line) <= (gsize) atomic_limit && write(fd, line, strlen(line)) == (ssize_t) strlen(line);
    if (fd >= 0) close(fd);
    g_free(line);
    if (!sent) {
        close(reader); close(keeper);
        unlink(reply);
        g_free(reply);
        return NULL;
    }

    /* Wait cancellably. Drop the keeper after the first bytes so the
     * launcher's closing its writer frames the end of the reply. */
    GInputStream *stream = g_unix_input_stream_new(reader, TRUE);
    GString *answer = g_string_new(NULL);
    gchar buffer[4096]; gssize count; gboolean failed = FALSE;
    while ((count = g_input_stream_read(stream, buffer, sizeof(buffer), cancellable, NULL)) > 0) {
        if (keeper >= 0) { close(keeper); keeper = -1; }
        if (answer->len + count > 64 * 1024) { failed = TRUE; break; }
        g_string_append_len(answer, buffer, count);
    }
    if (keeper >= 0) close(keeper);
    g_object_unref(stream);
    unlink(reply);
    g_free(reply);
    if (count < 0 || failed) { g_string_free(answer, TRUE); return NULL; }
    gchar **lines = g_strsplit(answer->str, "\n", -1);
    g_string_free(answer, TRUE);
    GPtrArray *paths = g_ptr_array_new();
    for (gchar **l = lines; *l; l++)
        if (**l == '/') g_ptr_array_add(paths, g_strdup(*l));
    g_strfreev(lines);
    g_ptr_array_add(paths, NULL);
    return (gchar **) g_ptr_array_free(paths, FALSE);
}

/* One dialog: its Request object, and what the app asked. */
typedef struct {
    gchar *sender, *path, *mode, *folder, *name, *title;
    gboolean multiple;
    guint registration;
    gint closed; /* the app closed the request: no Response */
    GCancellable *cancellable;
    guint owner_watch, timeout;
} Request;
static gint active_file_requests;

static void request_free(Request *r) {
    if (r->owner_watch) g_bus_unwatch_name(r->owner_watch);
    if (r->timeout) g_source_remove(r->timeout);
    g_clear_object(&r->cancellable);
    g_atomic_int_add(&active_file_requests, -1);
    g_free(r->sender), g_free(r->path), g_free(r->mode), g_free(r->folder), g_free(r->name), g_free(r->title);
    g_free(r);
}

static void request_cancel(Request *r) {
    g_atomic_int_set(&r->closed, 1);
    g_cancellable_cancel(r->cancellable);
    if (r->registration) {
        g_dbus_connection_unregister_object(bus, r->registration);
        r->registration = 0;
    }
}
static void file_owner_gone(GDBusConnection *connection, const gchar *name, gpointer data) { request_cancel(data); }
static gboolean file_timeout(gpointer data) {
    Request *r = data; r->timeout = 0; g_cancellable_cancel(r->cancellable);
    return G_SOURCE_REMOVE;
}

static void request_call(GDBusConnection *conn, const gchar *sender, const gchar *path, const gchar *iface, const gchar *method,
                         GVariant *params, GDBusMethodInvocation *invocation, gpointer data) {
    Request *r = data;
    if (g_strcmp0(sender, r->sender)) {
        g_dbus_method_invocation_return_dbus_error(invocation, "org.freedesktop.DBus.Error.AccessDenied", "Request belongs to another caller");
        return;
    }
    request_cancel(r);
    g_dbus_method_invocation_return_value(invocation, NULL);
}

static const GDBusInterfaceVTable request_vtable = {request_call, NULL, NULL, {0}};

/* Waits for the launcher in a thread of its own (several dialogs can be open), then answers on the Request. */
static void run_request(GTask *task, gpointer source, gpointer data, GCancellable *cancellable) {
    Request *r = data;
    gchar **paths = pick(r->mode, r->multiple, r->folder, r->name, r->title, cancellable);
    GVariantBuilder results;
    g_variant_builder_init(&results, G_VARIANT_TYPE_VARDICT);
    guint response = 2; /* other: the launcher couldn't be asked */
    if (paths) {
        response = paths[0] ? 0 : 1; /* picked, or cancelled */
        GVariantBuilder uris;
        g_variant_builder_init(&uris, G_VARIANT_TYPE_STRING_ARRAY);
        for (gchar **p = paths; *p; p++) {
            gchar *uri = g_filename_to_uri(*p, NULL, NULL);
            if (uri) g_variant_builder_add(&uris, "s", uri);
            g_free(uri);
        }
        g_variant_builder_add(&results, "{sv}", "uris", g_variant_builder_end(&uris));
        g_strfreev(paths);
    }
    GVariant *body = g_variant_new("(u@a{sv})", response, g_variant_builder_end(&results));
    g_task_return_pointer(task, g_variant_ref_sink(body), (GDestroyNotify) g_variant_unref);
}

/* Terminal emission and Close both run on the GLib main context. */
static void file_request_done(GObject *source, GAsyncResult *result, gpointer data) {
    GTask *task = G_TASK(result); Request *r = g_task_get_task_data(task);
    GVariant *body = g_task_propagate_pointer(task, NULL);
    if (!g_atomic_int_get(&r->closed) && body)
        g_dbus_connection_emit_signal(bus, r->sender, r->path, "org.freedesktop.portal.Request", "Response", body, NULL);
    if (r->registration) { g_dbus_connection_unregister_object(bus, r->registration); r->registration = 0; }
    if (body) g_variant_unref(body);
}

/* The Request's object path, as clients predict it from their handle_token to subscribe before calling. */
static gchar *request_path(const gchar *sender, GVariant *options) {
    static guint counter;
    const gchar *token = NULL;
    g_variant_lookup(options, "handle_token", "&s", &token);
    if (token && strchr(token, '/')) return NULL;
    gchar *own = token ? NULL : g_strdup_printf("omalauncher%u", g_atomic_int_add(&counter, 1));
    gchar *name = g_strdup(sender + (sender[0] == ':'));
    for (gchar *c = name; *c; c++)
        if (*c == '.') *c = '_';
    gchar *path = g_strdup_printf("%s/request/%s/%s", OBJECT_PATH, name, token ? token : own);
    g_free(name), g_free(own);
    return path;
}

static void method_call(GDBusConnection *conn, const gchar *sender, const gchar *path, const gchar *iface, const gchar *method,
                        GVariant *params, GDBusMethodInvocation *invocation, gpointer user_data) {
    const gchar *parent, *title;
    GVariant *options;
    g_variant_get(params, "(&s&s@a{sv})", &parent, &title, &options);

    if (g_atomic_int_get(&active_file_requests) >= 64) {
        g_variant_unref(options);
        g_dbus_method_invocation_return_dbus_error(invocation, "org.freedesktop.DBus.Error.LimitsExceeded", "Too many pending file requests");
        return;
    }
    Request *r = g_new0(Request, 1);
    g_atomic_int_inc(&active_file_requests);
    r->cancellable = g_cancellable_new();
    gboolean save = g_strcmp0(method, "SaveFile") == 0;
    gboolean directory = FALSE;
    g_variant_lookup(options, "multiple", "b", &r->multiple);
    g_variant_lookup(options, "directory", "b", &directory);
    r->multiple = r->multiple && !save;
    r->mode = g_strdup(save ? "save" : directory ? "folder" : "open");
    r->folder = bytes_option(options, "current_folder");
    const gchar *suggested = NULL;
    g_variant_lookup(options, "current_name", "&s", &suggested);
    r->name = g_strdup(suggested ? suggested : "");
    gchar *file = save ? bytes_option(options, "current_file") : NULL;
    if (file) {
        /* Saving over an existing file: start in its folder, with its name. */
        g_free(r->folder), g_free(r->name);
        r->folder = g_path_get_dirname(file);
        r->name = g_path_get_basename(file);
        g_free(file);
    }
    if (!r->folder) r->folder = g_strdup("");
    r->title = g_strdup(title);
    r->sender = g_strdup(sender);
    r->path = request_path(sender, options);
    g_variant_unref(options);

    if (!r->path || !g_variant_is_object_path(r->path)) {
        g_dbus_method_invocation_return_dbus_error(invocation, "org.freedesktop.DBus.Error.InvalidArgs", "Invalid request token");
        request_free(r); return;
    }

    GError *error = NULL;
    r->registration = g_dbus_connection_register_object(conn, r->path, request_info->interfaces[0], &request_vtable, r, NULL, &error);
    if (!r->registration) {
        g_dbus_method_invocation_return_gerror(invocation, error);
        g_error_free(error);
        request_free(r);
        return;
    }
    g_dbus_method_invocation_return_value(invocation, g_variant_new("(o)", r->path));
    r->owner_watch = g_bus_watch_name_on_connection(conn, sender, G_BUS_NAME_WATCHER_FLAGS_NONE, NULL, file_owner_gone, r, NULL);
    r->timeout = g_timeout_add_seconds(3600, file_timeout, r);
    GTask *task = g_task_new(NULL, r->cancellable, file_request_done, NULL);
    g_task_set_check_cancellable(task, FALSE);
    g_task_set_task_data(task, r, (GDestroyNotify) request_free);
    g_task_run_in_thread(task, run_request);
    g_object_unref(task);
}

static GVariant *get_property(GDBusConnection *conn, const gchar *sender, const gchar *path, const gchar *iface, const gchar *property,
                              GError **error, gpointer user_data) {
    return g_variant_new_uint32(4);
}

static const GDBusInterfaceVTable vtable = {method_call, get_property, NULL, {0}};

/* ---- Notifications (org.freedesktop.Notifications): notify-send and apps' notifications become Android ones. ---- */

#define NOTIFY_NAME "org.freedesktop.Notifications"
#define NOTIFY_PATH "/org/freedesktop/Notifications"

static const gchar notify_xml[] =
    "<node>"
    "  <interface name='org.freedesktop.Notifications'>"
    "    <method name='GetCapabilities'><arg type='as' direction='out'/></method>"
    "    <method name='Notify'>"
    "      <arg type='s' direction='in'/><arg type='u' direction='in'/><arg type='s' direction='in'/>"
    "      <arg type='s' direction='in'/><arg type='s' direction='in'/><arg type='as' direction='in'/>"
    "      <arg type='a{sv}' direction='in'/><arg type='i' direction='in'/><arg type='u' direction='out'/>"
    "    </method>"
    "    <method name='CloseNotification'><arg type='u' direction='in'/></method>"
    "    <method name='GetServerInformation'>"
    "      <arg type='s' direction='out'/><arg type='s' direction='out'/><arg type='s' direction='out'/><arg type='s' direction='out'/>"
    "    </method>"
    "    <signal name='NotificationClosed'><arg type='u'/><arg type='u'/></signal>"
    "    <signal name='ActionInvoked'><arg type='u'/><arg type='s'/></signal>"
    "  </interface>"
    "</node>";

/* One line to the launcher; never waits: with nothing reading, the notification is dropped. */
static void tell_launcher(const gchar *line) {
    gchar *bridge = g_strdup_printf("%s/open.fifo", runtime_dir);
    int fd = open(bridge, O_WRONLY | O_NONBLOCK);
    g_free(bridge);
    if (fd < 0) return;
    ssize_t n = write(fd, line, strlen(line));
    (void) n;
    close(fd);
}

/* Fits a field on the bridge's one line: tabs become spaces, and backslashes and newlines are escaped. */
static gchar *escape(const gchar *s) {
    GString *out = g_string_new(NULL);
    for (const gchar *p = s ? s : ""; *p; p++) {
        if (*p == '\\') g_string_append(out, "\\\\");
        else if (*p == '\n') g_string_append(out, "\\n");
        else if (*p == '\t' || *p == '\r') g_string_append_c(out, ' ');
        else g_string_append_c(out, *p);
    }
    return g_string_free(out, FALSE);
}

static void notify_call(GDBusConnection *conn, const gchar *sender, const gchar *path, const gchar *iface, const gchar *method,
                        GVariant *params, GDBusMethodInvocation *invocation, gpointer user_data) {
    static guint32 counter;
    if (g_strcmp0(method, "GetCapabilities") == 0) {
        const gchar *caps[] = {"body", NULL};
        g_dbus_method_invocation_return_value(invocation, g_variant_new("(^as)", caps));
    } else if (g_strcmp0(method, "GetServerInformation") == 0) {
        g_dbus_method_invocation_return_value(invocation, g_variant_new("(ssss)", "omalauncher", "omalauncher", "1", "1.2"));
    } else if (g_strcmp0(method, "Notify") == 0) {
        const gchar *app, *icon, *summary, *body;
        guint32 replaces;
        g_variant_get(params, "(&su&s&s&s@as@a{sv}i)", &app, &replaces, &icon, &summary, &body, NULL, NULL, NULL);
        guint32 id = replaces ? replaces : ++counter;
        gchar *a = escape(app), *t = escape(summary), *b = escape(body);
        gchar *line = g_strdup_printf("omarchy-notify:%u\t%s\t%s\t%s\n", id, a, t, b);
        tell_launcher(line);
        g_free(line), g_free(a), g_free(t), g_free(b);
        g_dbus_method_invocation_return_value(invocation, g_variant_new("(u)", id));
    } else if (g_strcmp0(method, "CloseNotification") == 0) {
        guint32 id;
        g_variant_get(params, "(u)", &id);
        gchar *line = g_strdup_printf("omarchy-notify-close:%u\n", id);
        tell_launcher(line);
        g_free(line);
        g_dbus_connection_emit_signal(conn, NULL, NOTIFY_PATH, NOTIFY_NAME, "NotificationClosed", g_variant_new("(uu)", id, 3), NULL);
        g_dbus_method_invocation_return_value(invocation, NULL);
    }
}

static const GDBusInterfaceVTable notify_vtable = {notify_call, NULL, NULL, {0}};

static void name_lost(GDBusConnection *conn, const gchar *name, gpointer user_data) {
    g_printerr("omalauncher-portal: couldn't own %s\n", name);
    /* No bus at all: nothing left to serve. One name taken by someone else: the other still works. */
    if (conn == NULL) g_main_loop_quit(user_data);
}

static void serve(GDBusConnection *conn, const gchar *path, const gchar *xml, const GDBusInterfaceVTable *vt) {
    GDBusNodeInfo *info = g_dbus_node_info_new_for_xml(xml, NULL);
    GError *error = NULL;
    if (!g_dbus_connection_register_object(conn, path, info->interfaces[0], vt, NULL, NULL, &error)) {
        g_printerr("omalauncher-portal: %s\n", error->message);
        g_error_free(error);
    }
    g_dbus_node_info_unref(info);
}

int main(void) {
    runtime_dir = g_getenv("XDG_RUNTIME_DIR");
    if (!runtime_dir) runtime_dir = "/run/user/0";
    GError *error = NULL;
    bus = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, &error);
    if (!bus) {
        g_printerr("omalauncher-portal: %s\n", error->message);
        return 1;
    }
    GMainLoop *loop = g_main_loop_new(NULL, FALSE);
    /* Objects first, then the names: a call can come as soon as a name is ours. Both run in one process,
     * started by whichever the bus is asked for first. */
    request_info = g_dbus_node_info_new_for_xml(request_xml, NULL);
    serve(bus, OBJECT_PATH, introspection_xml, &vtable);
    serve(bus, NOTIFY_PATH, notify_xml, &notify_vtable);
    if (!portal_settings_start(bus, runtime_dir, &error)) {
        g_printerr("omalauncher-portal: Settings: %s\n", error->message);
        g_clear_error(&error);
        return 1;
    }
    if (!portal_actions_start(bus, runtime_dir, &error)) {
        g_printerr("omalauncher-portal: actions: %s\n", error->message);
        g_clear_error(&error);
        return 1;
    }
    if (!portal_status_start(bus, runtime_dir, &error)) {
        g_printerr("omalauncher-portal: status: %s\n", error->message);
        g_clear_error(&error);
        return 1;
    }
    g_bus_own_name_on_connection(bus, BUS_NAME, G_BUS_NAME_OWNER_FLAGS_NONE, NULL, name_lost, loop, NULL);
    g_bus_own_name_on_connection(bus, NOTIFY_NAME, G_BUS_NAME_OWNER_FLAGS_NONE, NULL, name_lost, loop, NULL);
    g_main_loop_run(loop);
    return 0;
}
