/* Print requests share Action's caller ownership/cancellation. A genuine GTK
 * printer dialog supplies settings; single-use tokens retain those selections.
 * Included after the request primitives in portal-actions.c. */
typedef struct {
    gchar *owner;
    GVariant *settings, *page;
    gint64 expires;
} PrintToken;
static GHashTable *print_tokens;
static void print_token_free(gpointer data) {
    PrintToken *token = data; g_free(token->owner);
    g_variant_unref(token->settings); g_variant_unref(token->page); g_free(token);
}
static gboolean print_token_expire(gpointer data) {
    GHashTableIter it; gpointer value; g_hash_table_iter_init(&it, print_tokens);
    while (g_hash_table_iter_next(&it, NULL, &value))
        if (((PrintToken *)value)->expires < g_get_monotonic_time()) g_hash_table_iter_remove(&it);
    return G_SOURCE_CONTINUE;
}
static void print_owner_changed(GDBusConnection *bus, const gchar *sender, const gchar *path,
    const gchar *iface, const gchar *signal, GVariant *args, gpointer data) {
    const gchar *name, *old, *current; g_variant_get(args, "(&s&s&s)", &name, &old, &current);
    if (*current || name[0] != ':') return;
    GHashTableIter it; gpointer value; g_hash_table_iter_init(&it, print_tokens);
    while (g_hash_table_iter_next(&it, NULL, &value))
        if (g_str_equal(((PrintToken *)value)->owner, name)) g_hash_table_iter_remove(&it);
}
static void print_initialize(GDBusConnection *bus) {
    print_tokens = g_hash_table_new_full(g_direct_hash, g_direct_equal, NULL, print_token_free);
    g_timeout_add_seconds(30, print_token_expire, NULL);
    g_dbus_connection_signal_subscribe(bus, "org.freedesktop.DBus", "org.freedesktop.DBus", "NameOwnerChanged",
        "/org/freedesktop/DBus", NULL, G_DBUS_SIGNAL_FLAGS_NONE, print_owner_changed, NULL, NULL);
}
static void print_helper_finished(GObject *source, GAsyncResult *result, gpointer data) {
    Action *a = data; gchar *output = NULL; GError *error = NULL;
    gboolean ok = g_subprocess_communicate_utf8_finish(G_SUBPROCESS(source), result, &output, NULL, &error);
    if (g_atomic_int_get(&a->closed)) goto done;
    GVariant *reply = ok && output && strlen(output) <= MAX_WIRE_BYTES && g_subprocess_get_successful(G_SUBPROCESS(source))
        ? g_variant_parse(G_VARIANT_TYPE("(ua{sv}a{sv})"), output, NULL, NULL, &error) : NULL;
    if (!reply) { action_response(a, 2, NULL); goto done; }
    guint status; GVariant *settings, *page;
    g_variant_get(reply, "(u@a{sv}@a{sv})", &status, &settings, &page);
    if (status == 0 && g_str_equal(a->helper_mode, "--prepare")) {
        print_token_expire(NULL);
        if (g_hash_table_size(print_tokens) >= 64) action_response(a, 2, NULL);
        else {
            guint32 id; do { id = g_random_int(); } while (!id || g_hash_table_contains(print_tokens, GUINT_TO_POINTER(id)));
            PrintToken *token = g_new0(PrintToken, 1);
            token->owner = g_strdup(a->sender); token->settings = g_variant_ref(settings); token->page = g_variant_ref(page);
            token->expires = g_get_monotonic_time() + 15 * G_TIME_SPAN_MINUTE;
            g_hash_table_insert(print_tokens, GUINT_TO_POINTER(id), token);
            GVariantBuilder results; g_variant_builder_init(&results, G_VARIANT_TYPE_VARDICT);
            g_variant_builder_add(&results, "{sv}", "settings", settings);
            g_variant_builder_add(&results, "{sv}", "page-setup", page);
            g_variant_builder_add(&results, "{sv}", "token", g_variant_new_uint32(id));
            action_response(a, 0, g_variant_builder_end(&results));
        }
    } else action_response(a, status <= 2 ? status : 2, NULL);
    g_variant_unref(settings); g_variant_unref(page); g_variant_unref(reply);
done:
    if (error && !g_error_matches(error, G_IO_ERROR, G_IO_ERROR_CANCELLED)) g_printerr("Print helper: %s\n", error->message);
    g_clear_error(&error); g_free(output); action_unref(a);
}
static void print_helper_start(Action *a) {
    GVariant *input_value = g_variant_ref_sink(g_variant_new("(@a{sv}@a{sv}@asb)",
        a->print_settings, a->print_page, a->print_formats, a->print_modal));
    gchar *input = g_variant_print(input_value, TRUE); g_variant_unref(input_value);
    if (strlen(input) > MAX_WIRE_BYTES) { g_free(input); action_response(a, 2, NULL); return; }
    const gchar *program = g_getenv("OMALAUNCHER_PRINT_HELPER");
    if (!program || !*program) program = "/usr/local/lib/omalauncher/omalauncher-print-dialog";
    const gchar *argv[] = { program, a->helper_mode, a->helper_title, NULL };
    GSubprocessLauncher *launcher = g_subprocess_launcher_new(G_SUBPROCESS_FLAGS_STDIN_PIPE | G_SUBPROCESS_FLAGS_STDOUT_PIPE);
    g_subprocess_launcher_setenv(launcher, "GTK_USE_PORTAL", "0", TRUE);
    g_subprocess_launcher_setenv(launcher, "GTK_MODULES", "", TRUE);
    g_subprocess_launcher_setenv(launcher, "GDK_DEBUG", "", TRUE);
    if (a->fd_count) g_subprocess_launcher_take_fd(launcher, dup(a->fds[0]), 3);
    GError *error = NULL; a->helper = g_subprocess_launcher_spawnv(launcher, argv, &error); g_object_unref(launcher);
    if (!a->helper) {
        g_printerr("Start print helper: %s\n", error->message); g_clear_error(&error); g_free(input); action_response(a, 2, NULL); return;
    }
    a->helper_cancel = g_cancellable_new();
    g_subprocess_communicate_utf8_async(a->helper, input, a->helper_cancel, print_helper_finished, action_ref(a));
    g_free(input);
}
static void portal_print_call(GDBusConnection *bus, const gchar *sender, const gchar *method, GVariant *args, GDBusMethodInvocation *inv) {
    const gchar *parent, *title; GVariant *options, *settings = NULL, *page = NULL; gint handle = -1;
    gboolean prepare = g_str_equal(method, "PreparePrint");
    if (prepare) g_variant_get(args, "(&s&s@a{sv}@a{sv}@a{sv})", &parent, &title, &settings, &page, &options);
    else if (g_str_equal(method, "Print")) g_variant_get(args, "(&s&sh@a{sv})", &parent, &title, &handle, &options);
    else { g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.DBus.Error.UnknownMethod", method); return; }
    guint32 token_id = 0; g_variant_lookup(options, "token", "u", &token_id);
    PrintToken *token = token_id ? g_hash_table_lookup(print_tokens, GUINT_TO_POINTER(token_id)) : NULL;
    if (!prepare && token_id && (!token || !g_str_equal(token->owner, sender) || token->expires < g_get_monotonic_time())) {
        invalid(inv, "Print token is expired, consumed, or belongs to another caller"); goto done;
    }
    if ((settings && g_variant_get_size(settings) > MAX_WIRE_BYTES) || (page && g_variant_get_size(page) > MAX_WIRE_BYTES)) {
        invalid(inv, "Print settings are too large"); goto done;
    }
    Action *a = action_new(bus, sender, options, inv, prepare ? "prepare-print" : "print", REQUEST_RESPONSE);
    if (!a) goto done;
    if (!prepare && !action_fd(a, inv, handle, FALSE)) { action_bad_fd(a); goto done; }
    a->helper_mode = g_strdup(prepare ? "--prepare" : token ? "--print" : "--print-dialog");
    a->helper_title = g_strdup(*title ? title : "Print document");
    a->print_settings = token ? g_variant_ref(token->settings) : settings ? g_variant_ref(settings) : g_variant_ref_sink(g_variant_new_array(G_VARIANT_TYPE("{sv}"), NULL, 0));
    a->print_page = token ? g_variant_ref(token->page) : page ? g_variant_ref(page) : g_variant_ref_sink(g_variant_new_array(G_VARIANT_TYPE("{sv}"), NULL, 0));
    a->print_formats = g_variant_lookup_value(options, "supported_output_file_formats", G_VARIANT_TYPE_STRING_ARRAY);
    if (!a->print_formats) { const gchar *formats[] = { "pdf", "ps" }; a->print_formats = g_variant_ref_sink(g_variant_new_strv(formats, G_N_ELEMENTS(formats))); }
    a->print_modal = TRUE; g_variant_lookup(options, "modal", "b", &a->print_modal);
    if (token) g_hash_table_remove(print_tokens, GUINT_TO_POINTER(token_id)); /* Single use; never silently reselect a printer. */
    action_start(a);
done:
    if (settings) g_variant_unref(settings);
    if (page) g_variant_unref(page);
    g_variant_unref(options);
}
