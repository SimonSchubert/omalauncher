/* Android-backed portals, speaking the public xdg-desktop-portal interfaces.
 * A bounded Unix-socket packet carries real SCM_RIGHTS descriptors, not paths.
 * The Android endpoint checks kernel peer credentials. Closing a request shuts
 * down its connection, cancelling host UI/leases even when the guest exits.
 */
#include "portals.h"
#include <gio/gunixfdlist.h>
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <arpa/inet.h>
#include <unistd.h>

#define MAX_REQUESTS 64
#define MAX_ATTACHMENTS 8
#define MAX_WIRE_BYTES (64 * 1024)
#define REQUEST_TIMEOUT_SECONDS (15 * 60)
typedef enum { REQUEST_RESPONSE, REQUEST_HOLD, REQUEST_BOOLEAN, REQUEST_VOID, REQUEST_NOTIFICATION } ReplyKind;
typedef struct {
    gint refs, closed, socket_fd;
    GDBusConnection *bus;
    GDBusMethodInvocation *invocation; /* owned until transferred to a return_* call */
    gchar *sender, *path, *operation;
    GString *payload, *incoming;
    gint fds[MAX_ATTACHMENTS];
    guint fd_count, registration, io_watch, owner_watch, timeout;
    gchar *application_id, *notification_id;
    GPtrArray *action_names, *action_targets;
    gboolean oversized;
    guint64 notification_revision;
    gchar *helper_mode, *helper_title;
    GVariant *print_settings, *print_page, *print_formats;
    gboolean print_modal;
    GSubprocess *helper;
    GCancellable *helper_cancel;
    ReplyKind kind;
} Action;
static GHashTable *actions;
static gchar *actions_runtime;
static GDBusNodeInfo *action_request_info;
static guint64 notification_revision;
static void notification_invoke(Action *a, const gchar *index);
static void notification_retire_older(Action *a);
static void print_helper_start(Action *a);
static const gchar action_request_xml[] =
    "<node><interface name='org.freedesktop.portal.Request'><method name='Close'/>"
    "<signal name='Response'><arg type='u'/><arg type='a{sv}'/></signal></interface></node>";

static Action *action_ref(Action *a) { g_atomic_int_inc(&a->refs); return a; }
static void action_unref(gpointer data) {
    Action *a = data;
    if (!g_atomic_int_dec_and_test(&a->refs)) return;
    if (a->socket_fd >= 0) close(a->socket_fd);
    for (guint i = 0; i < a->fd_count; i++) close(a->fds[i]);
    g_clear_object(&a->invocation);
    g_free(a->application_id); g_free(a->notification_id);
    g_free(a->helper_mode); g_free(a->helper_title);
    g_clear_pointer(&a->print_settings, g_variant_unref); g_clear_pointer(&a->print_page, g_variant_unref);
    g_clear_pointer(&a->print_formats, g_variant_unref);
    g_clear_object(&a->helper); g_clear_object(&a->helper_cancel);
    g_clear_pointer(&a->action_names, g_ptr_array_unref);
    g_clear_pointer(&a->action_targets, g_ptr_array_unref);
    g_string_free(a->payload, TRUE); g_string_free(a->incoming, TRUE);
    g_free(a->sender); g_free(a->path); g_free(a->operation); g_free(a);
}
static void field(Action *a, const gchar *key, const gchar *value) {
    if (value && strlen(value) > MAX_WIRE_BYTES / 2) { a->oversized = TRUE; return; }
    gchar *encoded = g_base64_encode((const guchar *)(value ? value : ""), value ? strlen(value) : 0);
    g_string_append_printf(a->payload, "%s=%s\n", key, encoded); g_free(encoded);
}
static void number_field(Action *a, const gchar *key, guint64 value) {
    gchar text[32]; g_snprintf(text, sizeof text, "%" G_GUINT64_FORMAT, value); field(a, key, text);
}
static void invalid(GDBusMethodInvocation *inv, const gchar *message) {
    g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.portal.Error.InvalidArgument", message);
}
static void action_stop(Action *a) {
    if (!g_atomic_int_compare_and_exchange(&a->closed, 0, 1)) return;
    action_ref(a);
    if (a->helper_cancel) g_cancellable_cancel(a->helper_cancel);
    if (a->helper) g_subprocess_force_exit(a->helper);
    g_hash_table_remove(actions, a->path);
    /* Never close from another thread: shutdown interrupts I/O, while the
     * descriptor itself remains owned until the final task/watch reference. */
    gint fd = g_atomic_int_get(&a->socket_fd);
    if (fd >= 0) shutdown(fd, SHUT_RDWR);
    if (a->invocation) g_dbus_method_invocation_return_dbus_error(g_steal_pointer(&a->invocation),
        "org.freedesktop.portal.Error.Cancelled", "Request cancelled");
    if (a->registration) { guint id = a->registration; a->registration = 0; g_dbus_connection_unregister_object(a->bus, id); }
    if (a->io_watch) { guint id = a->io_watch; a->io_watch = 0; g_source_remove(id); }
    if (a->timeout) { guint id = a->timeout; a->timeout = 0; g_source_remove(id); }
    if (a->owner_watch) { guint id = a->owner_watch; a->owner_watch = 0; g_bus_unwatch_name(id); }
    action_unref(a);
}
static void action_response(Action *a, guint status, GVariant *results) {
    if (g_atomic_int_get(&a->closed)) return;
    if (status == 0 && (a->kind == REQUEST_HOLD || a->kind == REQUEST_NOTIFICATION)) {
        if (a->kind == REQUEST_NOTIFICATION) notification_retire_older(a);
        if (a->invocation) g_dbus_method_invocation_return_value(g_steal_pointer(&a->invocation), a->kind == REQUEST_HOLD ? g_variant_new("(o)", a->path) : NULL);
        if (a->timeout) { guint id = a->timeout; a->timeout = 0; g_source_remove(id); }
        return; /* Inhibit has no Response signal and lives until Close/owner loss. */
    }
    if (a->kind == REQUEST_RESPONSE) {
        if (a->invocation) { /* Failure before a handle could be returned. */
            g_dbus_method_invocation_return_dbus_error(g_steal_pointer(&a->invocation),
                "org.freedesktop.portal.Error.Failed", "Android portal request failed");
        } else {
            GVariant *dict = results ? results : g_variant_new_array(G_VARIANT_TYPE("{sv}"), NULL, 0);
            g_dbus_connection_emit_signal(a->bus, a->sender, a->path, "org.freedesktop.portal.Request", "Response",
                g_variant_new("(u@a{sv})", status, dict), NULL);
        }
    } else if (a->invocation) {
        if (a->kind == REQUEST_BOOLEAN && status < 2)
            g_dbus_method_invocation_return_value(g_steal_pointer(&a->invocation), g_variant_new("(b)", status == 0));
        else if (status == 0)
            g_dbus_method_invocation_return_value(g_steal_pointer(&a->invocation), NULL);
        else g_dbus_method_invocation_return_dbus_error(g_steal_pointer(&a->invocation),
            status == 1 ? "org.freedesktop.portal.Error.Cancelled" : "org.freedesktop.portal.Error.Failed", "Android portal request failed");
    }
    action_stop(a);
}
static gboolean action_timeout(gpointer data) { Action *a = data; a->timeout = 0; action_response(a, 2, NULL); return G_SOURCE_REMOVE; }
static void action_owner_lost(GDBusConnection *bus, const gchar *name, gpointer data) {
    Action *a = data;
    /* Notifications are application-scoped and outlive the original connection. */
    if (a->kind != REQUEST_NOTIFICATION) action_stop(a);
}
static void action_close(GDBusConnection *bus, const gchar *sender, const gchar *path, const gchar *iface,
                         const gchar *method, GVariant *args, GDBusMethodInvocation *inv, gpointer data) {
    Action *a = data;
    if (!g_str_equal(sender, a->sender)) {
        g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.portal.Error.NotAllowed", "Request belongs to another caller"); return;
    }
    g_dbus_method_invocation_return_value(inv, NULL); action_stop(a);
}
static const GDBusInterfaceVTable action_request_vtable = { .method_call = action_close };
static gboolean action_read(GIOChannel *channel, GIOCondition condition, gpointer data) {
    Action *a = data; gchar bytes[4096]; ssize_t count;
    while ((count = read(a->socket_fd, bytes, sizeof bytes)) > 0) {
        if (a->incoming->len + count > MAX_WIRE_BYTES) { action_response(a, 2, NULL); return G_SOURCE_REMOVE; }
        g_string_append_len(a->incoming, bytes, count);
    }
    if (a->incoming->len > MAX_WIRE_BYTES) { action_response(a, 2, NULL); return G_SOURCE_REMOVE; }
    gchar *newline;
    while ((newline = strchr(a->incoming->str, '\n')) != NULL) {
        gsize length = newline - a->incoming->str;
        gchar *line = g_strndup(a->incoming->str, length);
        g_string_erase(a->incoming, 0, length + 1);
        if (a->kind == REQUEST_NOTIFICATION && g_str_has_prefix(line, "A\t")) {
            notification_invoke(a, line + 2); g_free(line); continue;
        }
        guint status = line[0] >= '0' && line[0] <= '2' && (line[1] == '\0' || line[1] == '\t') ? (guint)(line[0] - '0') : 2;
        g_free(line); action_response(a, status, NULL);
        if (g_atomic_int_get(&a->closed)) return G_SOURCE_REMOVE;
    }
    if (count == 0 || (condition & (G_IO_HUP | G_IO_ERR | G_IO_NVAL)) || (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK)) {
        action_response(a, 2, NULL); return G_SOURCE_REMOVE;
    }
    return G_SOURCE_CONTINUE;
}
static gboolean valid_token(const gchar *token) {
    if (!*token || strlen(token) > 128) return FALSE;
    for (const gchar *p = token; *p; p++) if (!g_ascii_isalnum(*p) && *p != '_') return FALSE;
    return TRUE;
}
static Action *action_new(GDBusConnection *bus, const gchar *sender, GVariant *options,
                          GDBusMethodInvocation *inv, const gchar *operation, ReplyKind kind) {
    if (g_hash_table_size(actions) >= MAX_REQUESTS) {
        g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.portal.Error.LimitExceeded", "Too many pending portal requests"); return NULL;
    }
    const gchar *token = NULL;
    GVariant *value = g_variant_lookup_value(options, "handle_token", NULL);
    if (value && !g_variant_is_of_type(value, G_VARIANT_TYPE_STRING)) { g_variant_unref(value); invalid(inv, "handle_token must be a string"); return NULL; }
    if (value) token = g_variant_get_string(value, NULL);
    gchar *random = g_uuid_string_random();
    for (gchar *p = random; *p; p++) if (*p == '-') *p = '_';
    if (!token) token = random;
    if (!valid_token(token)) { g_clear_pointer(&value, g_variant_unref); g_free(random); invalid(inv, "Invalid handle_token"); return NULL; }
    Action *a = g_new0(Action, 1); a->refs = 1; a->socket_fd = -1;
    a->bus = bus; a->sender = g_strdup(sender); a->kind = kind; a->operation = g_strdup(operation);
    gchar *escaped = g_strdup(sender + (sender[0] == ':'));
    for (gchar *p = escaped; *p; p++) if (*p == '.') *p = '_';
    a->path = g_strdup_printf(PORTAL_OBJECT_PATH "/request/%s/%s", escaped, token);
    g_free(escaped); g_free(random); g_clear_pointer(&value, g_variant_unref);
    a->payload = g_string_new(NULL); a->incoming = g_string_new(NULL);
    GError *error = NULL;
    a->registration = g_dbus_connection_register_object(bus, a->path, action_request_info->interfaces[0],
        &action_request_vtable, action_ref(a), action_unref, &error);
    if (!a->registration) {
        /* GDBus has already destroyed the registration's user_data reference,
         * including on failure. Only our initial reference remains. */
        invalid(inv, error ? error->message : "Duplicate request handle"); g_clear_error(&error); action_unref(a); return NULL;
    }
    a->invocation = inv; /* Interface handler transfers ownership; no extra ref. */
    g_hash_table_insert(actions, a->path, a);
    a->owner_watch = g_bus_watch_name_on_connection(bus, sender, G_BUS_NAME_WATCHER_FLAGS_NONE, NULL, action_owner_lost, action_ref(a), action_unref);
    a->timeout = g_timeout_add_seconds_full(G_PRIORITY_DEFAULT, REQUEST_TIMEOUT_SECONDS, action_timeout, action_ref(a), action_unref);
    field(a, "version", "1"); field(a, "operation", operation);
    return a;
}
static gboolean action_fd(Action *a, GDBusMethodInvocation *inv, gint handle, gboolean directory) {
    GUnixFDList *list = g_dbus_message_get_unix_fd_list(g_dbus_method_invocation_get_message(inv));
    GError *error = NULL;
    gint fd = list && handle >= 0 && handle < g_unix_fd_list_get_length(list) ? g_unix_fd_list_get(list, handle, &error) : -1;
    struct stat st;
    gboolean ok = fd >= 0 && a->fd_count < MAX_ATTACHMENTS && fstat(fd, &st) == 0 && (S_ISREG(st.st_mode) || (directory && S_ISDIR(st.st_mode)));
    if (ok) a->fds[a->fd_count++] = fd; else if (fd >= 0) close(fd);
    g_clear_error(&error); return ok;
}
static void action_connect(GTask *task, gpointer source, gpointer data, GCancellable *cancellable) {
    Action *a = data; gboolean ok = FALSE;
    gint fd = portal_transport_connect(actions_runtime, NULL);
    if (fd < 0) goto done;
    g_atomic_int_set(&a->socket_fd, fd);
    if (g_atomic_int_get(&a->closed)) goto done;
    ok = portal_transport_send(fd, a->payload->str, a->payload->len, a->fds, a->fd_count, NULL) && !g_atomic_int_get(&a->closed);
    if (ok) { gint flags = fcntl(fd, F_GETFL); ok = flags >= 0 && fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0; }
done:
    g_task_return_boolean(task, ok);
}
static void action_ready(GObject *source, GAsyncResult *result, gpointer ignored) {
    Action *a = g_task_get_task_data(G_TASK(result));
    gboolean ok = g_task_propagate_boolean(G_TASK(result), NULL);
    if (g_atomic_int_get(&a->closed)) return;
    if (!ok) { action_response(a, 2, NULL); return; }
    GIOChannel *channel = g_io_channel_unix_new(a->socket_fd);
    g_io_channel_set_encoding(channel, NULL, NULL); g_io_channel_set_buffered(channel, FALSE);
    a->io_watch = g_io_add_watch_full(channel, G_PRIORITY_DEFAULT, G_IO_IN | G_IO_HUP | G_IO_ERR | G_IO_NVAL,
        action_read, action_ref(a), action_unref);
    g_io_channel_unref(channel);
}
static void action_start(Action *a) {
    number_field(a, "fd-count", a->fd_count);
    if (a->oversized || a->payload->len > MAX_WIRE_BYTES) { action_response(a, 2, NULL); action_unref(a); return; }
    if (a->kind == REQUEST_RESPONSE) g_dbus_method_invocation_return_value(g_steal_pointer(&a->invocation), g_variant_new("(o)", a->path));
    if (a->helper_mode) { print_helper_start(a); action_unref(a); return; }
    GTask *task = g_task_new(NULL, NULL, action_ready, NULL);
    g_task_set_task_data(task, action_ref(a), action_unref); g_task_run_in_thread(task, action_connect);
    g_object_unref(task); action_unref(a);
}
static void action_bad_fd(Action *a) {
    invalid(g_steal_pointer(&a->invocation), "Expected an attached regular-file descriptor (or a directory for OpenDirectory)");
    action_stop(a); action_unref(a);
}
/* Typed lookups return NULL for both absence and the wrong type. For email,
 * confusing those cases can silently drop attachments/recipients from a
 * successful compose request. Validate known options before creating a handle. */
static gboolean email_options_valid(GVariant *options, GDBusMethodInvocation *inv) {
    static const struct { const gchar *key, *type; } known[] = {
        { "address", "s" }, { "addresses", "as" }, { "cc", "as" }, { "bcc", "as" },
        { "subject", "s" }, { "body", "s" }, { "attachment_fds", "ah" }
    };
    for (guint i = 0; i < G_N_ELEMENTS(known); i++) {
        GVariant *value = g_variant_lookup_value(options, known[i].key, NULL);
        gboolean valid = !value || g_variant_is_of_type(value, G_VARIANT_TYPE(known[i].type));
        g_clear_pointer(&value, g_variant_unref);
        if (!valid) {
            gchar *message = g_strdup_printf("%s must have type %s", known[i].key, known[i].type);
            invalid(inv, message); g_free(message); return FALSE;
        }
    }
    return TRUE;
}
static void option_string(Action *a, GVariant *options, const gchar *key) {
    const gchar *text = NULL; if (g_variant_lookup(options, key, "&s", &text)) field(a, key, text);
}
static void option_bool(Action *a, GVariant *options, const gchar *key) {
    gboolean value = FALSE; g_variant_lookup(options, key, "b", &value); field(a, key, value ? "1" : "0");
}
static void option_addresses(Action *a, GVariant *options, const gchar *key) {
    GVariant *array = g_variant_lookup_value(options, key, G_VARIANT_TYPE_STRING_ARRAY);
    if (!array) return;
    gsize count = g_variant_n_children(array);
    for (gsize i = 0; i < count && !a->oversized; i++) {
        GVariant *item = g_variant_get_child_value(array, i); gchar *name = g_strdup_printf("%s-%zu", key, i);
        field(a, name, g_variant_get_string(item, NULL)); g_free(name); g_variant_unref(item);
    }
    g_variant_unref(array);
}
/* A real application name is essential: notifications survive the connection
 * which posted them, and app.* actions use org.freedesktop.Application. */
static gchar *application_id(GDBusConnection *bus, const gchar *sender) {
    GVariant *result = g_dbus_connection_call_sync(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "ListNames", NULL, G_VARIANT_TYPE("(as)"), G_DBUS_CALL_FLAGS_NONE, 3000, NULL, NULL);
    if (!result) return NULL;
    gchar **names; g_variant_get(result, "(^as)", &names); g_variant_unref(result);
    gchar *id = NULL;
    for (guint i = 0; names[i] && !id; i++) {
        if (!g_application_id_is_valid(names[i]) || g_str_has_prefix(names[i], "org.freedesktop.")) continue;
        GVariant *owner = g_dbus_connection_call_sync(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "GetNameOwner", g_variant_new("(s)", names[i]), G_VARIANT_TYPE("(s)"), G_DBUS_CALL_FLAGS_NONE, 1000, NULL, NULL);
        if (owner) { const gchar *unique; g_variant_get(owner, "(&s)", &unique); if (g_str_equal(unique, sender)) id = g_strdup(names[i]); g_variant_unref(owner); }
    }
    g_strfreev(names); return id;
}
static Action *notification_find(const gchar *app, const gchar *id) {
    GHashTableIter iter; gpointer value; g_hash_table_iter_init(&iter, actions);
    while (g_hash_table_iter_next(&iter, NULL, &value)) {
        Action *a = value;
        if (a->kind == REQUEST_NOTIFICATION && g_strcmp0(a->application_id, app) == 0 && g_strcmp0(a->notification_id, id) == 0) return a;
    }
    return NULL;
}
static void notification_retire_older(Action *a) {
    GPtrArray *old = g_ptr_array_new_with_free_func(action_unref);
    GHashTableIter it; gpointer value; g_hash_table_iter_init(&it, actions);
    while (g_hash_table_iter_next(&it, NULL, &value)) {
        Action *other = value;
        if (other != a && other->kind == REQUEST_NOTIFICATION && other->notification_revision < a->notification_revision &&
            g_strcmp0(other->application_id, a->application_id) == 0 && g_strcmp0(other->notification_id, a->notification_id) == 0)
            g_ptr_array_add(old, action_ref(other));
    }
    for (guint i = 0; i < old->len; i++) action_stop(g_ptr_array_index(old, i));
    g_ptr_array_unref(old);
}
static void nullable_variant_unref(gpointer value) { if (value) g_variant_unref(value); }
typedef struct { GDBusConnection *bus; gchar *app, *id, *action; GVariant *parameters; } NotificationDispatch;
static void notification_dispatch_free(NotificationDispatch *d) { g_object_unref(d->bus); g_free(d->app); g_free(d->id); g_free(d->action); g_variant_unref(d->parameters); g_free(d); }
static void notification_emit(NotificationDispatch *d) {
    g_dbus_connection_emit_signal(d->bus, d->app, PORTAL_OBJECT_PATH, "org.freedesktop.portal.Notification", "ActionInvoked",
        g_variant_new("(ss@av)", d->id, d->action, g_variant_ref(d->parameters)), NULL);
}
static void notification_activated(GObject *source, GAsyncResult *result, gpointer data) {
    NotificationDispatch *d = data; GError *error = NULL;
    GVariant *reply = g_dbus_connection_call_finish(G_DBUS_CONNECTION(source), result, &error);
    if (reply) { if (*d->action && !g_str_has_prefix(d->action, "app.")) notification_emit(d); g_variant_unref(reply); }
    else { g_message("notification activation failed: %s", error->message); g_clear_error(&error); }
    notification_dispatch_free(d);
}
static void notification_invoke(Action *a, const gchar *index) {
    if (!index[0] || index[1] || index[0] < '0' || index[0] > '3') return;
    guint n = index[0] - '0'; if (!a->action_names || n >= a->action_names->len) return;
    const gchar *name = g_ptr_array_index(a->action_names, n); GVariant *target = g_ptr_array_index(a->action_targets, n);
    GVariantBuilder params; g_variant_builder_init(&params, G_VARIANT_TYPE("av")); if (target) g_variant_builder_add(&params, "v", target);
    NotificationDispatch *d = g_new0(NotificationDispatch, 1); d->bus = g_object_ref(a->bus); d->app = g_strdup(a->application_id);
    d->id = g_strdup(a->notification_id); d->action = g_strdup(name); d->parameters = g_variant_ref_sink(g_variant_builder_end(&params));
    gboolean app_action = g_str_has_prefix(name, "app.");
    if (*name && !app_action) {
        GVariant *owned = g_dbus_connection_call_sync(a->bus, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "NameHasOwner", g_variant_new("(s)", a->application_id), G_VARIANT_TYPE("(b)"), G_DBUS_CALL_FLAGS_NONE, 1000, NULL, NULL);
        gboolean alive = FALSE; if (owned) { g_variant_get(owned, "(b)", &alive); g_variant_unref(owned); }
        if (alive) { notification_emit(d); notification_dispatch_free(d); return; }
    }
    gchar *path = g_strconcat("/", a->application_id, NULL);
    for (gchar *p = path; *p; p++) { if (*p == '.') *p = '/'; else if (*p == '-') *p = '_'; }
    GVariant *platform = g_variant_new_array(G_VARIANT_TYPE("{sv}"), NULL, 0);
    GVariant *args = app_action ? g_variant_new("(s@av@a{sv})", name + 4, g_variant_ref(d->parameters), platform) : g_variant_new("(@a{sv})", platform);
    g_dbus_connection_call(a->bus, a->application_id, path, "org.freedesktop.Application", app_action ? "ActivateAction" : "Activate", args,
        NULL, G_DBUS_CALL_FLAGS_NONE, 15000, NULL, notification_activated, d); g_free(path);
}
static gboolean notification_fields(Action *a, GVariant *dict) {
    option_string(a, dict, "title"); option_string(a, dict, "body"); option_string(a, dict, "priority");
    a->action_names = g_ptr_array_new_with_free_func(g_free); a->action_targets = g_ptr_array_new_with_free_func(nullable_variant_unref);
    const gchar *default_action = ""; g_variant_lookup(dict, "default-action", "&s", &default_action);
    g_ptr_array_add(a->action_names, g_strdup(default_action));
    g_ptr_array_add(a->action_targets, g_variant_lookup_value(dict, "default-action-target", NULL));
    GVariant *buttons = g_variant_lookup_value(dict, "buttons", G_VARIANT_TYPE("aa{sv}"));
    if (buttons && g_variant_n_children(buttons) > 3) { g_variant_unref(buttons); return FALSE; }
    for (gsize i = 0; buttons && i < g_variant_n_children(buttons); i++) {
        GVariant *button = g_variant_get_child_value(buttons, i); const gchar *label = NULL, *name = NULL;
        gboolean valid = g_variant_lookup(button, "label", "&s", &label) && g_variant_lookup(button, "action", "&s", &name) && *name;
        if (!valid) { g_variant_unref(button); g_variant_unref(buttons); return FALSE; }
        gchar *key = g_strdup_printf("button-%zu", i + 1); field(a, key, label); g_free(key);
        g_ptr_array_add(a->action_names, g_strdup(name)); g_ptr_array_add(a->action_targets, g_variant_lookup_value(button, "target", NULL)); g_variant_unref(button);
    }
    g_clear_pointer(&buttons, g_variant_unref);
    for (guint i = 0; i < a->action_targets->len; i++) { GVariant *v = g_ptr_array_index(a->action_targets, i); if (v && g_variant_get_size(v) > MAX_WIRE_BYTES) return FALSE; }
    return TRUE;
}
#include "portal-print-requests.inc"

static const gchar portals_xml[] = "<node>"
 "<interface name='org.freedesktop.portal.Print'>"
 "<method name='PreparePrint'><arg type='s' direction='in'/><arg type='s' direction='in'/><arg type='a{sv}' direction='in'/><arg type='a{sv}' direction='in'/><arg type='a{sv}' direction='in'/><arg type='o' direction='out'/></method>"
 "<method name='Print'><arg type='s' direction='in'/><arg type='s' direction='in'/><arg type='h' direction='in'/><arg type='a{sv}' direction='in'/><arg type='o' direction='out'/></method>"
 "<property name='version' type='u' access='read'/></interface>"
 "<interface name='org.freedesktop.portal.OpenURI'>"
 "<method name='OpenURI'><arg type='s' direction='in'/><arg type='s' direction='in'/><arg type='a{sv}' direction='in'/><arg type='o' direction='out'/></method>"
 "<method name='OpenFile'><arg type='s' direction='in'/><arg type='h' direction='in'/><arg type='a{sv}' direction='in'/><arg type='o' direction='out'/></method>"
 "<method name='OpenDirectory'><arg type='s' direction='in'/><arg type='h' direction='in'/><arg type='a{sv}' direction='in'/><arg type='o' direction='out'/></method>"
 "<method name='SchemeSupported'><arg type='s' direction='in'/><arg type='a{sv}' direction='in'/><arg type='b' direction='out'/></method>"
 "<property name='version' type='u' access='read'/></interface>"
 "<interface name='org.freedesktop.portal.Email'><method name='ComposeEmail'><arg type='s' direction='in'/><arg type='a{sv}' direction='in'/><arg type='o' direction='out'/></method>"
 "<property name='version' type='u' access='read'/></interface>"
 "<interface name='org.freedesktop.portal.Inhibit'><method name='Inhibit'><arg type='s' direction='in'/><arg type='u' direction='in'/><arg type='a{sv}' direction='in'/><arg type='o' direction='out'/></method>"
 "<property name='version' type='u' access='read'/></interface>"
 "<interface name='org.freedesktop.portal.Notification'>"
 "<method name='AddNotification'><arg type='s' direction='in'/><arg type='a{sv}' direction='in'/></method>"
 "<method name='RemoveNotification'><arg type='s' direction='in'/></method>"
 "<signal name='ActionInvoked'><arg type='s'/><arg type='s'/><arg type='av'/></signal>"
 "<property name='version' type='u' access='read'/></interface></node>";
static void portal_call(GDBusConnection *bus, const gchar *sender, const gchar *path, const gchar *iface,
                        const gchar *method, GVariant *args, GDBusMethodInvocation *inv, gpointer unused) {
    if (g_str_equal(iface, "org.freedesktop.portal.Print")) { portal_print_call(bus, sender, method, args, inv); return; }
    const gchar *parent = "", *text = ""; GVariant *options = NULL; gint handle = -1; Action *a = NULL;
    if (g_str_equal(iface, "org.freedesktop.portal.Notification")) {
        const gchar *id; GVariant *dict = NULL;
        if (g_str_equal(method, "AddNotification")) g_variant_get(args, "(&s@a{sv})", &id, &dict); else g_variant_get(args, "(&s)", &id);
        gchar *app = application_id(bus, sender);
        if (!app) g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.portal.Error.NotAllowed", "Notification caller must own its application D-Bus name");
        else if (!*id || strlen(id) > 256) invalid(inv, "Invalid notification id");
        else if (g_str_equal(method, "RemoveNotification")) {
            Action *old; while ((old = notification_find(app, id)) != NULL) action_stop(old); g_dbus_method_invocation_return_value(inv, NULL);
        } else {
            GVariant *empty = g_variant_ref_sink(g_variant_new_array(G_VARIANT_TYPE("{sv}"), NULL, 0));
            a = action_new(bus, sender, empty, inv, "notification", REQUEST_NOTIFICATION); g_variant_unref(empty);
            if (a) {
                a->application_id = g_strdup(app); a->notification_id = g_strdup(id); field(a, "application-id", app); field(a, "notification-id", id);
                a->notification_revision = ++notification_revision; number_field(a, "notification-revision", a->notification_revision);
                if (!notification_fields(a, dict)) {
                    invalid(g_steal_pointer(&a->invocation), "Invalid notification actions"); action_stop(a); action_unref(a); a = NULL;
                }
            }
        }
        g_free(app); g_clear_pointer(&dict, g_variant_unref);
    } else if (g_str_equal(iface, "org.freedesktop.portal.OpenURI")) {
        if (g_str_equal(method, "SchemeSupported")) g_variant_get(args, "(&s@a{sv})", &text, &options);
        else if (g_str_equal(method, "OpenURI")) g_variant_get(args, "(&s&s@a{sv})", &parent, &text, &options);
        else g_variant_get(args, "(&sh@a{sv})", &parent, &handle, &options);
        const gchar *op = g_str_equal(method, "OpenURI") ? "open-uri" : g_str_equal(method, "OpenFile") ? "open-file" : g_str_equal(method, "OpenDirectory") ? "open-directory" : "scheme-supported";
        a = action_new(bus, sender, options, inv, op, g_str_equal(method, "SchemeSupported") ? REQUEST_BOOLEAN : REQUEST_RESPONSE);
        if (a) {
            field(a, "uri", text); option_bool(a, options, "ask"); option_bool(a, options, "writable");
            if (handle >= 0 && !action_fd(a, inv, handle, TRUE)) { action_bad_fd(a); a = NULL; }
            else if ((g_str_equal(method, "OpenFile") || g_str_equal(method, "OpenDirectory")) && handle < 0) { action_bad_fd(a); a = NULL; }
        }
    } else if (g_str_equal(iface, "org.freedesktop.portal.Email")) {
        g_variant_get(args, "(&s@a{sv})", &parent, &options);
        a = email_options_valid(options, inv) ? action_new(bus, sender, options, inv, "email", REQUEST_RESPONSE) : NULL;
        if (a) {
            option_string(a, options, "address"); option_string(a, options, "subject"); option_string(a, options, "body");
            option_addresses(a, options, "addresses"); option_addresses(a, options, "cc"); option_addresses(a, options, "bcc");
            GVariant *fds = g_variant_lookup_value(options, "attachment_fds", G_VARIANT_TYPE("ah"));
            gboolean ok = !fds || g_variant_n_children(fds) <= MAX_ATTACHMENTS;
            for (gsize i = 0; ok && fds && i < g_variant_n_children(fds); i++) {
                GVariant *v = g_variant_get_child_value(fds, i); ok = action_fd(a, inv, g_variant_get_handle(v), FALSE); g_variant_unref(v);
            }
            g_clear_pointer(&fds, g_variant_unref);
            if (!ok) { action_bad_fd(a); a = NULL; }
        }
    } else if (g_str_equal(iface, "org.freedesktop.portal.Inhibit")) {
        guint flags; g_variant_get(args, "(&su@a{sv})", &parent, &flags, &options);
        if (!flags || (flags & ~12u)) g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.portal.Error.NotSupported", "Android supports suspend/idle inhibition, not desktop logout or user switching");
        else {
            a = action_new(bus, sender, options, inv, "inhibit", REQUEST_HOLD);
            if (a) { number_field(a, "flags", flags); option_string(a, options, "reason"); }
        }
    } else g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.DBus.Error.UnknownMethod", "Unknown portal method");
    if (a) action_start(a);
    g_clear_pointer(&options, g_variant_unref);
}
static GVariant *portal_property(GDBusConnection *bus, const gchar *sender, const gchar *path, const gchar *iface,
                                 const gchar *property, GError **error, gpointer unused) {
    if (g_str_equal(property, "version")) return g_variant_new_uint32(g_str_equal(iface, "org.freedesktop.portal.OpenURI") ? 5 : (g_str_equal(iface, "org.freedesktop.portal.Email") || g_str_equal(iface, "org.freedesktop.portal.Print")) ? 3 : 1);
    g_set_error(error, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_PROPERTY, "Unknown property"); return NULL;
}
static const GDBusInterfaceVTable portal_vtable = { .method_call = portal_call, .get_property = portal_property };
gboolean portal_actions_start(GDBusConnection *bus, const gchar *runtime, GError **error) {
    actions = g_hash_table_new(g_str_hash, g_str_equal); actions_runtime = g_strdup(runtime);
    print_initialize(bus);
    action_request_info = g_dbus_node_info_new_for_xml(action_request_xml, error);
    if (!action_request_info) return FALSE;
    GDBusNodeInfo *info = g_dbus_node_info_new_for_xml(portals_xml, error); if (!info) return FALSE;
    for (guint i = 0; info->interfaces[i]; i++) {
        if (!g_dbus_connection_register_object(bus, PORTAL_OBJECT_PATH, info->interfaces[i], &portal_vtable, NULL, NULL, error)) { g_dbus_node_info_unref(info); return FALSE; }
    }
    g_dbus_node_info_unref(info); return TRUE;
}
