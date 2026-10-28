/* Persistent D-Bus client: unlike one-shot gdbus, owns request handles until
 * their Response/Close, and sends actual message-attached Unix descriptors. */
#include <gio/gio.h>
#include <gio/gunixfdlist.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#define BUS "org.freedesktop.portal.Desktop"
#define PATH "/org/freedesktop/portal/desktop"
static GMainLoop *loop;
static guint response = G_MAXUINT;
static GVariant *response_results;
static void response_cb(GDBusConnection *c, const gchar *s, const gchar *p, const gchar *i, const gchar *n, GVariant *args, gpointer d) {
    g_clear_pointer(&response_results, g_variant_unref);
    g_variant_get(args, "(u@a{sv})", &response, &response_results); g_main_loop_quit(loop);
}
static gboolean timed_out(gpointer data) { g_main_loop_quit(loop); return G_SOURCE_REMOVE; }
static void action_cb(GDBusConnection *c, const gchar *s, const gchar *p, const gchar *i, const gchar *n, GVariant *args, gpointer d) {
    const gchar *id, *action; GVariant *params;
    g_variant_get(args, "(&s&s@av)", &id, &action, &params);
    g_assert_cmpstr(id, ==, "test"); g_assert_cmpstr(action, ==, "custom"); g_assert_cmpuint(g_variant_n_children(params), ==, 1);
    GVariant *wrapped = g_variant_get_child_value(params, 0), *target = g_variant_get_variant(wrapped);
    g_assert_cmpuint(g_variant_get_uint32(target), ==, 42); g_variant_unref(target); g_variant_unref(wrapped); g_variant_unref(params);
    response = 0; g_main_loop_quit(loop);
}
static GDBusConnection *connection(void) {
    GError *error = NULL;
    GDBusConnection *c = g_dbus_connection_new_for_address_sync(g_getenv("DBUS_SESSION_BUS_ADDRESS"),
        G_DBUS_CONNECTION_FLAGS_AUTHENTICATION_CLIENT | G_DBUS_CONNECTION_FLAGS_MESSAGE_BUS_CONNECTION, NULL, NULL, &error);
    g_assert_no_error(error); return c;
}
static GVariant *options(const gchar *token) {
    GVariantBuilder b; g_variant_builder_init(&b, G_VARIANT_TYPE_VARDICT);
    g_variant_builder_add(&b, "{sv}", "handle_token", g_variant_new_string(token)); return g_variant_builder_end(&b);
}
static GVariant *call(GDBusConnection *c, const gchar *path, const gchar *iface, const gchar *method, GVariant *args, GError **error) {
    return g_dbus_connection_call_sync(c, BUS, path, iface, method, args, NULL, G_DBUS_CALL_FLAGS_NONE, 5000, NULL, error);
}
static gchar *open_uri(GDBusConnection *c, const gchar *token, const gchar *uri, GError **error) {
    GVariant *result = call(c, PATH, "org.freedesktop.portal.OpenURI", "OpenURI", g_variant_new("(ss@a{sv})", "", uri, options(token)), error);
    if (!result) return NULL;
    gchar *handle; g_variant_get(result, "(o)", &handle); g_variant_unref(result); return handle;
}
static void close_request(GDBusConnection *c, const gchar *path) {
    GError *error = NULL; GVariant *result = call(c, path, "org.freedesktop.portal.Request", "Close", NULL, &error);
    g_assert_no_error(error); g_assert_nonnull(result); g_variant_unref(result);
}
static void wait_response(guint expected) {
    guint timer = g_timeout_add(5000, timed_out, NULL); g_main_loop_run(loop);
    if (response != G_MAXUINT) g_source_remove(timer);
    g_assert_cmpuint(response, ==, expected);
}
static GVariant *print_document(GDBusConnection *c, const gchar *filename, guint32 token, GError **error) {
    gint fd = open(filename, O_RDONLY | O_CLOEXEC); g_assert_cmpint(fd, >=, 0);
    GUnixFDList *fds = g_unix_fd_list_new(); gint index = g_unix_fd_list_append(fds, fd, error); g_assert_no_error(*error);
    GVariantBuilder b; g_variant_builder_init(&b, G_VARIANT_TYPE_VARDICT);
    g_variant_builder_add(&b, "{sv}", "handle_token", g_variant_new_string("print"));
    g_variant_builder_add(&b, "{sv}", "token", g_variant_new_uint32(token));
    GVariant *result = g_dbus_connection_call_with_unix_fd_list_sync(c, BUS, PATH, "org.freedesktop.portal.Print", "Print",
        g_variant_new("(ssh@a{sv})", "", "Fixture", index, g_variant_builder_end(&b)), G_VARIANT_TYPE("(o)"), G_DBUS_CALL_FLAGS_NONE, 5000, fds, NULL, NULL, error);
    g_object_unref(fds); close(fd); return result;
}
static void invalid_print_token(GDBusConnection *c, const gchar *filename, guint32 token) {
    GError *error = NULL; GVariant *result = print_document(c, filename, token, &error);
    g_assert_null(result); g_assert_nonnull(error);
    gchar *name = g_dbus_error_get_remote_error(error);
    g_assert_cmpstr(name, ==, "org.freedesktop.portal.Error.InvalidArgument"); g_free(name); g_clear_error(&error);
}
static void print_case(GDBusConnection *c, const gchar *test, const gchar *filename) {
    const gchar *title = g_str_equal(test, "print-cancel") ? "cancel" : g_str_equal(test, "print-close") ? "hold" : "Fixture";
    GError *error = NULL;
    GVariant *result = call(c, PATH, "org.freedesktop.portal.Print", "PreparePrint",
        g_variant_new("(ss@a{sv}@a{sv}@a{sv})", "", title, options("unused-settings"), options("unused-page"), options("prepare")), &error);
    g_assert_no_error(error); g_assert_nonnull(result);
    gchar *handle; g_variant_get(result, "(o)", &handle); g_variant_unref(result);
    if (g_str_equal(test, "print-close")) {
        GDBusConnection *other = connection();
        result = call(other, handle, "org.freedesktop.portal.Request", "Close", NULL, &error);
        g_assert_null(result); g_assert_nonnull(error); g_clear_error(&error);
        g_dbus_connection_close_sync(other, NULL, NULL); g_object_unref(other);
        close_request(c, handle); g_assert_cmpuint(response, ==, G_MAXUINT);
    } else if (g_str_equal(test, "print-cancel")) {
        wait_response(1); g_assert_false(g_variant_lookup(response_results, "token", "u", NULL));
    } else {
        wait_response(0);
        guint32 token = 0; g_assert_true(g_variant_lookup(response_results, "token", "u", &token)); g_assert_cmpuint(token, >, 0);
        GVariant *settings = g_variant_lookup_value(response_results, "settings", G_VARIANT_TYPE_VARDICT);
        GVariant *page = g_variant_lookup_value(response_results, "page-setup", G_VARIANT_TYPE_VARDICT);
        g_assert_nonnull(settings); g_assert_nonnull(page); g_assert_cmpuint(g_variant_n_children(page), >, 0);
        const gchar *printer = NULL; g_assert_true(g_variant_lookup(settings, "printer", "&s", &printer)); g_assert_cmpstr(printer, ==, "Android");
        g_variant_unref(settings); g_variant_unref(page);
        GDBusConnection *other = connection(); invalid_print_token(other, filename, token);
        g_dbus_connection_close_sync(other, NULL, NULL); g_object_unref(other);
        response = G_MAXUINT; result = print_document(c, filename, token, &error);
        g_assert_no_error(error); g_assert_nonnull(result); g_variant_unref(result); wait_response(0);
        invalid_print_token(c, filename, token);
    }
    g_free(handle);
}
static gchar *file_request(GDBusConnection *c, const gchar *token, const gchar *test, const gchar *filename, GError **error) {
    GVariantBuilder b; g_variant_builder_init(&b, G_VARIANT_TYPE_VARDICT);
    g_variant_builder_add(&b, "{sv}", "handle_token", g_variant_new_string(token));
    g_variant_builder_add(&b, "{sv}", "multiple", g_variant_new_boolean(TRUE));
    gboolean save = g_str_equal(test, "files-save");
    if (save) g_variant_builder_add(&b, "{sv}", "current_file", g_variant_new_bytestring(filename));
    GVariant *result = call(c, PATH, "org.freedesktop.portal.FileChooser", save ? "SaveFile" : "OpenFile",
        g_variant_new("(ss@a{sv})", "", "Files fixture", g_variant_builder_end(&b)), error);
    if (!result) return NULL;
    gchar *path; g_variant_get(result, "(o)", &path); g_variant_unref(result); return path;
}

static void files_case(GDBusConnection *c, const gchar *test, int argc, char **argv) {
    GError *error = NULL;
    if (g_str_equal(test, "files-limit")) {
        GPtrArray *handles = g_ptr_array_new_with_free_func(g_free);
        for (guint index = 0; index < 64; index++) {
            gchar *token = g_strdup_printf("files%u", index);
            gchar *path = file_request(c, token, test, NULL, &error); g_free(token);
            g_assert_no_error(error); g_assert_nonnull(path); g_ptr_array_add(handles, path);
        }
        gchar *path = file_request(c, "overflow", test, NULL, &error);
        g_assert_null(path); g_assert_nonnull(error); g_clear_error(&error);
        for (guint index = 0; index < handles->len; index++) close_request(c, g_ptr_array_index(handles, index));
        g_ptr_array_unref(handles); return;
    }
    gchar *path = file_request(c, g_str_equal(test, "files-invalid") ? "invalid/token" : "files", test, argc > 2 ? argv[2] : NULL, &error);
    if (g_str_equal(test, "files-invalid")) {
        g_assert_null(path); g_assert_nonnull(error); g_clear_error(&error); return;
    }
    g_assert_no_error(error); g_assert_nonnull(path);
    if (g_str_equal(test, "files-close")) {
        GDBusConnection *other = connection();
        GVariant *reply = call(other, path, "org.freedesktop.portal.Request", "Close", NULL, &error);
        g_assert_null(reply); g_assert_nonnull(error);
        gchar *remote = g_dbus_error_get_remote_error(error); g_assert_cmpstr(remote, ==, "org.freedesktop.DBus.Error.AccessDenied");
        g_free(remote); g_clear_error(&error); g_dbus_connection_close_sync(other, NULL, NULL); g_object_unref(other);
        close_request(c, path);
        g_timeout_add(100, timed_out, NULL); g_main_loop_run(loop); g_assert_cmpuint(response, ==, G_MAXUINT);
    } else if (g_str_equal(test, "files-gone")) {
        g_dbus_connection_close_sync(c, NULL, NULL);
    } else {
        gboolean cancelled = g_str_equal(test, "files-cancel"); wait_response(cancelled ? 1 : 0);
        GVariant *uris = g_variant_lookup_value(response_results, "uris", G_VARIANT_TYPE_STRING_ARRAY); g_assert_nonnull(uris);
        g_assert_cmpuint(g_variant_n_children(uris), ==, cancelled ? 0 : argc - 2);
        if (!cancelled) for (int index = 2; index < argc; index++) {
            const gchar *actual; g_variant_get_child(uris, index - 2, "&s", &actual);
            gchar *expected = g_filename_to_uri(argv[index], NULL, NULL); g_assert_cmpstr(actual, ==, expected); g_free(expected);
        }
        g_variant_unref(uris);
    }
    g_free(path);
}

static GVariant *email_attachment_request(GDBusConnection *c, GUnixFDList *fds, GVariant *attachments, GError **error) {
    GVariantBuilder b; g_variant_builder_init(&b, G_VARIANT_TYPE_VARDICT);
    g_variant_builder_add(&b, "{sv}", "handle_token", g_variant_new_string("email"));
    g_variant_builder_add(&b, "{sv}", "attachment_fds", attachments);
    return g_dbus_connection_call_with_unix_fd_list_sync(c, BUS, PATH, "org.freedesktop.portal.Email", "ComposeEmail",
        g_variant_new("(s@a{sv})", "", g_variant_builder_end(&b)), G_VARIANT_TYPE("(o)"), G_DBUS_CALL_FLAGS_NONE, 5000, fds, NULL, NULL, error);
}
static void email_invalid_fds(GDBusConnection *c, const gchar *kind, const gchar *filename) {
    GError *error = NULL;
    gint fd = open(filename, O_RDONLY | O_CLOEXEC); g_assert_cmpint(fd, >=, 0);
    GUnixFDList *fds = g_unix_fd_list_new();
    gint first = g_unix_fd_list_append(fds, fd, &error); close(fd); g_assert_no_error(error);
    gint bad = 99;
    if (g_str_equal(kind, "directory")) {
        gchar *parent = g_path_get_dirname(filename);
        fd = open(parent, O_RDONLY | O_DIRECTORY | O_CLOEXEC); g_free(parent); g_assert_cmpint(fd, >=, 0);
        bad = g_unix_fd_list_append(fds, fd, &error); close(fd); g_assert_no_error(error);
    }
    /* More than the service's pending-request limit. Reuse the same handle so
     * rejected requests must release both registration and partial FD copies. */
    for (guint attempt = 0; attempt < 70; attempt++) {
        GVariantBuilder attachments; g_variant_builder_init(&attachments, G_VARIANT_TYPE("ah"));
        g_variant_builder_add(&attachments, "h", first);
        if (g_str_equal(kind, "too-many")) {
            for (guint i = 1; i < 9; i++) g_variant_builder_add(&attachments, "h", first);
        } else if (!g_str_equal(kind, "missing-list")) g_variant_builder_add(&attachments, "h", bad);
        GVariant *result = email_attachment_request(c, g_str_equal(kind, "missing-list") ? NULL : fds,
            g_variant_builder_end(&attachments), &error);
        g_assert_null(result); g_assert_nonnull(error);
        gchar *remote = g_dbus_error_get_remote_error(error);
        g_assert_cmpstr(remote, ==, "org.freedesktop.portal.Error.InvalidArgument");
        g_free(remote); g_clear_error(&error);
    }
    GVariantBuilder valid; g_variant_builder_init(&valid, G_VARIANT_TYPE("ah"));
    g_variant_builder_add(&valid, "h", first);
    GVariant *result = email_attachment_request(c, fds, g_variant_builder_end(&valid), &error);
    g_object_unref(fds); g_assert_no_error(error); g_assert_nonnull(result); g_variant_unref(result); wait_response(0);
}

static guint monitor_network_changes, monitor_power_changes;

static void monitor_network_changed(GNetworkMonitor *monitor, gboolean available, gpointer data) {
    monitor_network_changes++;
}

static void monitor_power_changed(GObject *monitor, GParamSpec *spec, gpointer data) {
    monitor_power_changes++;
}

static gboolean monitors_match(GNetworkMonitor *network, GPowerProfileMonitor *power, gboolean enabled) {
    return g_network_monitor_get_network_available(network) == enabled &&
           g_network_monitor_get_network_metered(network) == !enabled &&
           g_network_monitor_get_connectivity(network) == (enabled ? G_NETWORK_CONNECTIVITY_FULL : G_NETWORK_CONNECTIVITY_LOCAL) &&
           g_power_profile_monitor_get_power_saver_enabled(power) == enabled;
}

static void await_monitors(GNetworkMonitor *network, GPowerProfileMonitor *power, gboolean enabled, gboolean require_changes) {
    gint64 until = g_get_monotonic_time() + 3 * G_TIME_SPAN_SECOND;
    while ((!monitors_match(network, power, enabled) || (require_changes && (!monitor_network_changes || !monitor_power_changes))) &&
           g_get_monotonic_time() < until) {
        while (g_main_context_iteration(NULL, FALSE)) {}
        g_usleep(1000);
    }
    g_assert_true(monitors_match(network, power, enabled));
    if (require_changes) {
        g_assert_cmpuint(monitor_network_changes, >, 0);
        g_assert_cmpuint(monitor_power_changes, >, 0);
    }
}

/* Exercise GLib's real consumer implementations, not hand-written D-Bus calls.
 * The GTK-only negative control exits42 rather than aborting/dumping a core. */
static int monitor_frontends(const gchar *snapshot) {
    GNetworkMonitor *network = g_network_monitor_get_default();
    GPowerProfileMonitor *power = g_power_profile_monitor_dup_default();
    g_print("network-backend=%s\npower-backend=%s\n", G_OBJECT_TYPE_NAME(network), G_OBJECT_TYPE_NAME(power));
    if (g_strcmp0(G_OBJECT_TYPE_NAME(network), "GNetworkMonitorPortal") ||
        g_strcmp0(G_OBJECT_TYPE_NAME(power), "GPowerProfileMonitorPortal")) {
        g_object_unref(power);
        return 42;
    }
    g_signal_connect(network, "network-changed", G_CALLBACK(monitor_network_changed), NULL);
    g_signal_connect(power, "notify::power-saver-enabled", G_CALLBACK(monitor_power_changed), NULL);
    /* Power's initial GetAll is synchronous; network initial getters are async. */
    await_monitors(network, power, TRUE, FALSE);
    monitor_network_changes = monitor_power_changes = 0;
    GError *error = NULL;
    g_assert_true(g_file_set_contents(snapshot, "power-saver=0\navailable=0\nmetered=1\nconnectivity=1\n", -1, &error));
    g_assert_no_error(error);
    await_monitors(network, power, FALSE, TRUE);
    g_print("GLib monitor initial state and live transitions: OK\n");
    g_object_unref(power);
    return 0;
}

int main(int argc, char **argv) {
    g_assert_cmpint(argc, >=, 2);
    if (g_str_equal(argv[1], "monitor-frontends")) {
        g_assert_cmpint(argc, ==, 3);
        return monitor_frontends(argv[2]);
    }
    GDBusConnection *c = connection(); GError *error = NULL;
    loop = g_main_loop_new(NULL, FALSE);
    g_dbus_connection_signal_subscribe(c, BUS, "org.freedesktop.portal.Request", "Response", NULL, NULL, G_DBUS_SIGNAL_FLAGS_NONE, response_cb, NULL, NULL);
    if (g_str_has_prefix(argv[1], "files-")) {
        files_case(c, argv[1], argc, argv);
    } else if (g_str_equal(argv[1], "legacy-notify")) {
        GVariantBuilder hints; g_variant_builder_init(&hints, G_VARIANT_TYPE_VARDICT);
        GVariant *result = call(c, "/org/freedesktop/Notifications", "org.freedesktop.Notifications", "Notify",
            g_variant_new("(susss@as@a{sv}i)", "Legacy fixture", 0u, "", "Legacy title", "Legacy body", g_variant_new_strv(NULL, 0), g_variant_builder_end(&hints), -1), &error);
        g_assert_no_error(error); g_assert_nonnull(result); guint id; g_variant_get(result, "(u)", &id); g_assert_cmpuint(id, >, 0); g_variant_unref(result);
    } else if (g_str_has_prefix(argv[1], "print-")) {
        print_case(c, argv[1], argc > 2 ? argv[2] : NULL);
    } else if (g_str_has_prefix(argv[1], "notification")) {
        const gchar *app = g_str_equal(argv[1], "notification") ? "org.omalauncher.PortalTest" : "org.omalauncher.PortalExit";
        GVariant *name = g_dbus_connection_call_sync(c, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName", g_variant_new("(su)", app, 0u), G_VARIANT_TYPE("(u)"), G_DBUS_CALL_FLAGS_NONE, 3000, NULL, &error);
        g_assert_no_error(error); g_assert_nonnull(name); g_variant_unref(name);
        g_dbus_connection_signal_subscribe(c, BUS, "org.freedesktop.portal.Notification", "ActionInvoked", NULL, NULL, G_DBUS_SIGNAL_FLAGS_NONE, action_cb, NULL, NULL);
        gboolean withdraw = g_str_equal(argv[1], "notification-withdraw");
        gboolean replace = g_str_equal(argv[1], "notification-replace");
        if (!withdraw) for (guint round = 0; round < (replace ? 2u : 1u); round++) {
            GVariantBuilder b; g_variant_builder_init(&b, G_VARIANT_TYPE_VARDICT);
            g_variant_builder_add(&b, "{sv}", "title", g_variant_new_string(g_str_equal(argv[1], "notification") ? "Portal test" : "Persistent test"));
            g_variant_builder_add(&b, "{sv}", "body", g_variant_new_string(round ? "replacement" : "initial"));
            g_variant_builder_add(&b, "{sv}", "default-action", g_variant_new_string("custom"));
            g_variant_builder_add(&b, "{sv}", "default-action-target", g_variant_new_uint32(42));
            GVariant *result = call(c, PATH, "org.freedesktop.portal.Notification", "AddNotification", g_variant_new("(s@a{sv})", "test", g_variant_builder_end(&b)), &error);
            g_assert_no_error(error); g_assert_nonnull(result); g_variant_unref(result);
        }
        if (g_str_equal(argv[1], "notification")) wait_response(0);
        if (withdraw || replace || g_str_equal(argv[1], "notification")) {
            GVariant *result = call(c, PATH, "org.freedesktop.portal.Notification", "RemoveNotification", g_variant_new("(s)", "test"), &error);
            g_assert_no_error(error); g_assert_nonnull(result); g_variant_unref(result);
        }
    } else if (g_str_equal(argv[1], "email-invalid-fds")) {
        g_assert_cmpint(argc, ==, 4);
        email_invalid_fds(c, argv[2], argv[3]);
    } else if (g_str_equal(argv[1], "email-empty") || g_str_equal(argv[1], "email-empty-list")) {
        GVariant *result = g_str_equal(argv[1], "email-empty")
            ? call(c, PATH, "org.freedesktop.portal.Email", "ComposeEmail", g_variant_new("(s@a{sv})", "", options("email")), &error)
            : email_attachment_request(c, NULL, g_variant_new_array(G_VARIANT_TYPE_HANDLE, NULL, 0), &error);
        g_assert_no_error(error); g_assert_nonnull(result); g_variant_unref(result); wait_response(0);
    } else if (g_str_equal(argv[1], "email-invalid-option")) {
        g_assert_cmpint(argc, ==, 4);
        GVariant *value = NULL;
        if (g_str_equal(argv[3], "s")) value = g_variant_new_string("file:///not-an-attachment-capability");
        else if (g_str_equal(argv[3], "as")) {
            const gchar *paths[] = {"file:///not-an-attachment-capability", NULL};
            value = g_variant_new_strv(paths, -1);
        } else if (g_str_equal(argv[3], "ai")) {
            gint32 index = 0;
            value = g_variant_new_fixed_array(G_VARIANT_TYPE_INT32, &index, 1, sizeof index);
        } else value = g_variant_new_uint32(7);
        GVariantBuilder b; g_variant_builder_init(&b, G_VARIANT_TYPE_VARDICT);
        g_variant_builder_add(&b, "{sv}", "handle_token", g_variant_new_string("email"));
        g_variant_builder_add(&b, "{sv}", argv[2], value);
        GVariant *result = call(c, PATH, "org.freedesktop.portal.Email", "ComposeEmail",
            g_variant_new("(s@a{sv})", "", g_variant_builder_end(&b)), &error);
        g_assert_null(result); g_assert_nonnull(error);
        gchar *remote = g_dbus_error_get_remote_error(error);
        g_assert_cmpstr(remote, ==, "org.freedesktop.portal.Error.InvalidArgument");
        g_free(remote); g_clear_error(&error);
    } else if (g_str_equal(argv[1], "email") || g_str_equal(argv[1], "email-many")) {
        g_assert_cmpint(argc, >=, 3);
        GUnixFDList *fds = g_unix_fd_list_new();
        GVariantBuilder b, attachments;
        g_variant_builder_init(&b, G_VARIANT_TYPE_VARDICT);
        g_variant_builder_init(&attachments, G_VARIANT_TYPE("ah"));
        g_variant_builder_add(&b, "{sv}", "handle_token", g_variant_new_string("email"));
        g_variant_builder_add(&b, "{sv}", "address", g_variant_new_string("legacy@example.org"));
        const gchar *recipients[] = {"first@example.org", "second@example.org", NULL};
        const gchar *cc[] = {"copy@example.org", NULL}, *bcc[] = {"hidden@example.org", NULL};
        if (g_str_equal(argv[1], "email-many")) {
            const gchar *keys[] = {"addresses", "cc", "bcc"};
            for (guint k = 0; k < G_N_ELEMENTS(keys); k++) {
                GVariantBuilder list; g_variant_builder_init(&list, G_VARIANT_TYPE_STRING_ARRAY);
                for (guint i = 0; i < 65; i++) {
                    gchar *value = g_strdup_printf("%s-%u@example.org", keys[k], i);
                    g_variant_builder_add(&list, "s", value); g_free(value);
                }
                g_variant_builder_add(&b, "{sv}", keys[k], g_variant_builder_end(&list));
            }
        } else {
            g_variant_builder_add(&b, "{sv}", "addresses", g_variant_new_strv(recipients, -1));
            g_variant_builder_add(&b, "{sv}", "cc", g_variant_new_strv(cc, -1));
            g_variant_builder_add(&b, "{sv}", "bcc", g_variant_new_strv(bcc, -1));
        }
        g_variant_builder_add(&b, "{sv}", "subject", g_variant_new_string("Portal email fixture"));
        g_variant_builder_add(&b, "{sv}", "body", g_variant_new_string("First line\noperation=print\nLast line"));
        for (int i = 2; i < argc; i++) {
            gint fd = open(argv[i], O_RDONLY | O_CLOEXEC); g_assert_cmpint(fd, >=, 0);
            gint index = g_unix_fd_list_append(fds, fd, &error); close(fd); g_assert_no_error(error);
            g_variant_builder_add(&attachments, "h", index);
        }
        g_variant_builder_add(&b, "{sv}", "attachment_fds", g_variant_builder_end(&attachments));
        GVariant *result = g_dbus_connection_call_with_unix_fd_list_sync(c, BUS, PATH, "org.freedesktop.portal.Email", "ComposeEmail",
            g_variant_new("(s@a{sv})", "", g_variant_builder_end(&b)), G_VARIANT_TYPE("(o)"), G_DBUS_CALL_FLAGS_NONE, 5000, fds, NULL, NULL, &error);
        g_object_unref(fds); g_assert_no_error(error); g_assert_nonnull(result); g_variant_unref(result); wait_response(0);
    } else if (g_str_equal(argv[1], "uri")) {
        gchar *handle = open_uri(c, "uri", "https://example.org/portal-test", &error); g_assert_no_error(error); g_assert_nonnull(handle); wait_response(0); g_free(handle);
    } else if (g_str_equal(argv[1], "cancel") || g_str_equal(argv[1], "duplicate")) {
        gchar *handle = open_uri(c, "pending", "https://example.org/hold", &error); g_assert_no_error(error);
        if (g_str_equal(argv[1], "duplicate")) {
            gchar *duplicate = open_uri(c, "pending", "https://example.org/hold", &error);
            g_assert_null(duplicate); g_assert_nonnull(error);
            gchar *name = g_dbus_error_get_remote_error(error); g_assert_cmpstr(name, ==, "org.freedesktop.portal.Error.InvalidArgument"); g_free(name); g_clear_error(&error);
        } else {
            GDBusConnection *other = connection();
            GVariant *result = call(other, handle, "org.freedesktop.portal.Request", "Close", NULL, &error);
            g_assert_null(result); g_assert_nonnull(error);
            gchar *name = g_dbus_error_get_remote_error(error); g_assert_cmpstr(name, ==, "org.freedesktop.portal.Error.NotAllowed"); g_free(name); g_clear_error(&error);
            g_dbus_connection_close_sync(other, NULL, NULL); g_object_unref(other);
        }
        close_request(c, handle); g_free(handle);
    } else if (g_str_equal(argv[1], "fd") || g_str_equal(argv[1], "bad-fd")) {
        g_assert_cmpint(argc, >=, 3);
        gint fd = open(argv[2], O_RDONLY | O_CLOEXEC); g_assert_cmpint(fd, >=, 0); lseek(fd, 3, SEEK_SET);
        GUnixFDList *fds = g_unix_fd_list_new(); gint index = g_unix_fd_list_append(fds, fd, &error); g_assert_no_error(error);
        gboolean bad = g_str_equal(argv[1], "bad-fd");
        GVariant *result = g_dbus_connection_call_with_unix_fd_list_sync(c, BUS, PATH, "org.freedesktop.portal.OpenURI", "OpenFile",
            g_variant_new("(sh@a{sv})", "", bad ? 97 : index, options("fd")), G_VARIANT_TYPE("(o)"), G_DBUS_CALL_FLAGS_NONE, 5000, fds, NULL, NULL, &error);
        if (bad) {
            g_assert_null(result); gchar *name = g_dbus_error_get_remote_error(error);
            g_assert_cmpstr(name, ==, "org.freedesktop.portal.Error.InvalidArgument"); g_free(name); g_clear_error(&error);
        } else { g_assert_no_error(error); g_assert_nonnull(result); g_variant_unref(result); wait_response(0); }
        g_assert_cmpint(lseek(fd, 0, SEEK_CUR), ==, 3); g_object_unref(fds); close(fd);
    } else if (g_str_equal(argv[1], "inhibit") || g_str_equal(argv[1], "inhibit-exit")) {
        GVariant *result = call(c, PATH, "org.freedesktop.portal.Inhibit", "Inhibit", g_variant_new("(su@a{sv})", "", 8u, options("inhibit")), &error);
        g_assert_no_error(error); g_assert_nonnull(result);
        gchar *handle; g_variant_get(result, "(o)", &handle); g_variant_unref(result);
        g_assert_cmpuint(response, ==, G_MAXUINT);
        if (g_str_equal(argv[1], "inhibit")) close_request(c, handle);
        g_free(handle);
    } else if (g_str_equal(argv[1], "bad-token")) {
        gchar *handle = open_uri(c, "../invalid", "https://example.org", &error); g_assert_null(handle); g_assert_nonnull(error);
        gchar *name = g_dbus_error_get_remote_error(error); g_assert_cmpstr(name, ==, "org.freedesktop.portal.Error.InvalidArgument"); g_free(name); g_clear_error(&error);
    } else g_assert_not_reached();
    g_dbus_connection_close_sync(c, NULL, NULL); g_object_unref(c); g_main_loop_unref(loop);
    g_clear_pointer(&response_results, g_variant_unref);
    g_print("%s: OK\n", argv[1]); return 0;
}
