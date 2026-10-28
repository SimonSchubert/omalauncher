/*
 * A GTK3 module that sends GtkFileChooserDialog through the file chooser
 * portal, so GTK3 apps (wxWidgets ones too, like PrusaSlicer) get the same
 * picker as portal-aware apps. GTK3 only uses the portal for
 * GtkFileChooserNative; most apps make a plain dialog.
 *
 * The dialog is never shown: its show asks the portal instead, the picked
 * files are kept on the dialog, and its own response is emitted, so the app
 * goes on as if they'd been picked in it. The dialog's get_files and
 * get_current_folder answer from what was picked; a dialog that was never
 * shown has no selection of its own. Without a session bus or a portal, the
 * dialog shows as usual.
 *
 * Load with GTK_MODULES=omalauncher-filechooser (and GTK_PATH naming the
 * directory above modules/).
 */
#include <gtk/gtk.h>
#include <string.h>

/* GtkFileChooserIface from gtk/gtkfilechooserprivate.h, as of GTK 3.22 (3.x no longer changes); only the start is used. */
typedef struct {
    GTypeInterface base_iface;
    gboolean (*set_current_folder)(GtkFileChooser *, GFile *, GError **);
    GFile *(*get_current_folder)(GtkFileChooser *);
    void (*set_current_name)(GtkFileChooser *, const gchar *);
    gchar *(*get_current_name)(GtkFileChooser *);
    gboolean (*select_file)(GtkFileChooser *, GFile *, GError **);
    void (*unselect_file)(GtkFileChooser *, GFile *);
    void (*select_all)(GtkFileChooser *);
    void (*unselect_all)(GtkFileChooser *);
    GSList *(*get_files)(GtkFileChooser *);
} FileChooserIface;

#define PICKED "omalauncher-picked" /* GSList of GFile */
#define PENDING "omalauncher-pending"

static void (*parent_show)(GtkWidget *);
static GSList *(*parent_get_files)(GtkFileChooser *);
static GFile *(*parent_get_current_folder)(GtkFileChooser *);

static void free_files(gpointer list) { g_slist_free_full(list, g_object_unref); }

static gpointer ref_file(gconstpointer file, gpointer unused) { return g_object_ref((gpointer) file); }

static GSList *get_files(GtkFileChooser *chooser) {
    GSList *picked = g_object_get_data(G_OBJECT(chooser), PICKED);
    if (picked) return g_slist_copy_deep(picked, ref_file, NULL);
    return parent_get_files(chooser);
}

static GFile *get_current_folder(GtkFileChooser *chooser) {
    GSList *picked = g_object_get_data(G_OBJECT(chooser), PICKED);
    if (picked) {
        GFile *first = picked->data;
        GtkFileChooserAction action = gtk_file_chooser_get_action(chooser);
        if (action == GTK_FILE_CHOOSER_ACTION_SELECT_FOLDER || action == GTK_FILE_CHOOSER_ACTION_CREATE_FOLDER) return g_object_ref(first);
        GFile *parent = g_file_get_parent(first);
        if (parent) return parent;
    }
    return parent_get_current_folder(chooser);
}

/* The first of [ids] the dialog has a button for, else [fallback]. */
static gint response_id(GtkDialog *dialog, const gint *ids, gsize n, gint fallback) {
    for (gsize i = 0; i < n; i++)
        if (gtk_dialog_get_widget_for_response(dialog, ids[i])) return ids[i];
    return fallback;
}

static void find_widget(GtkWidget *widget, gpointer found) {
    if (*(GtkWidget **) found) return;
    if (GTK_IS_FILE_CHOOSER_WIDGET(widget))
        *(GtkWidget **) found = widget;
    else if (GTK_IS_CONTAINER(widget))
        gtk_container_forall(GTK_CONTAINER(widget), find_widget, found);
}

static void note_response(GtkDialog *dialog, gint id, gpointer responded) { *(gboolean *) responded = TRUE; }

/*
 * Accepts as if a file had been double-clicked: the dialog's file widget requests a response. A plain
 * gtk_dialog_response would be stopped: GtkFileChooserDialog checks its own file list on an accept, and one
 * never shown has nothing in it; a requested response skips that check. The accept button, greyed out with
 * nothing picked in the dialog itself, is enabled first. Failing that, the response goes out directly.
 */
static void accept_picked(GtkDialog *dialog) {
    static const gint accept[] = {GTK_RESPONSE_ACCEPT, GTK_RESPONSE_OK, GTK_RESPONSE_YES, GTK_RESPONSE_APPLY};
    for (gsize i = 0; i < G_N_ELEMENTS(accept); i++) {
        GtkWidget *button = gtk_dialog_get_widget_for_response(dialog, accept[i]);
        if (button) gtk_widget_set_sensitive(button, TRUE);
    }
    GtkWidget *widget = NULL;
    find_widget(GTK_WIDGET(dialog), &widget);
    gboolean responded = FALSE;
    gulong handler = g_signal_connect(dialog, "response", G_CALLBACK(note_response), &responded);
    if (widget) g_signal_emit_by_name(widget, "response-requested");
    g_signal_handler_disconnect(dialog, handler);
    if (!responded) gtk_dialog_response(dialog, response_id(dialog, accept, G_N_ELEMENTS(accept), GTK_RESPONSE_ACCEPT));
}

typedef struct {
    GtkWidget *dialog; /* a reference */
    GDBusConnection *bus;
    guint subscription;
} Pending;

static void pending_free(Pending *p) {
    if (p->subscription) g_dbus_connection_signal_unsubscribe(p->bus, p->subscription);
    g_object_unref(p->bus);
    g_object_unref(p->dialog);
    g_free(p);
}

static void on_response(GDBusConnection *bus, const gchar *sender, const gchar *path, const gchar *iface, const gchar *signal,
                        GVariant *params, gpointer data) {
    Pending *p = data;
    guint32 response;
    GVariant *results;
    g_variant_get(params, "(u@a{sv})", &response, &results);
    GSList *files = NULL;
    const gchar **uris = NULL;
    if (response == 0 && g_variant_lookup(results, "uris", "^a&s", &uris)) {
        for (const gchar **u = uris; *u; u++) files = g_slist_append(files, g_file_new_for_uri(*u));
        g_free(uris);
    }
    g_variant_unref(results);

    GtkWidget *dialog = g_object_ref(p->dialog);
    g_object_set_data(G_OBJECT(dialog), PENDING, NULL); /* frees p */
    g_object_set_data_full(G_OBJECT(dialog), PICKED, files, free_files);
    if (files) {
        accept_picked(GTK_DIALOG(dialog));
    } else {
        static const gint cancel[] = {GTK_RESPONSE_CANCEL, GTK_RESPONSE_CLOSE, GTK_RESPONSE_NO};
        gtk_dialog_response(GTK_DIALOG(dialog), response_id(GTK_DIALOG(dialog), cancel, G_N_ELEMENTS(cancel), GTK_RESPONSE_DELETE_EVENT));
    }
    g_object_unref(dialog);
}

/* Asks the portal for [dialog]; false if it can't be asked, and the dialog should show. */
static gboolean ask_portal(GtkWidget *dialog) {
    GDBusConnection *bus = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, NULL);
    if (!bus) return FALSE;
    GtkFileChooser *chooser = GTK_FILE_CHOOSER(dialog);
    GtkFileChooserAction action = gtk_file_chooser_get_action(chooser);
    gboolean save = action == GTK_FILE_CHOOSER_ACTION_SAVE;

    static guint counter;
    gchar *token = g_strdup_printf("gtk3module%u", ++counter);
    gchar *unique = g_strdup(g_dbus_connection_get_unique_name(bus) + 1);
    for (gchar *c = unique; *c; c++)
        if (*c == '.') *c = '_';
    gchar *path = g_strdup_printf("/org/freedesktop/portal/desktop/request/%s/%s", unique, token);
    g_free(unique);

    GVariantBuilder options;
    g_variant_builder_init(&options, G_VARIANT_TYPE_VARDICT);
    g_variant_builder_add(&options, "{sv}", "handle_token", g_variant_new_string(token));
    g_free(token);
    if (gtk_file_chooser_get_select_multiple(chooser) && !save) g_variant_builder_add(&options, "{sv}", "multiple", g_variant_new_boolean(TRUE));
    if (action == GTK_FILE_CHOOSER_ACTION_SELECT_FOLDER || action == GTK_FILE_CHOOSER_ACTION_CREATE_FOLDER)
        g_variant_builder_add(&options, "{sv}", "directory", g_variant_new_boolean(TRUE));
    GFile *folder = parent_get_current_folder(chooser);
    gchar *folder_path = folder ? g_file_get_path(folder) : NULL;
    if (folder_path) g_variant_builder_add(&options, "{sv}", "current_folder", g_variant_new_bytestring(folder_path));
    g_free(folder_path);
    g_clear_object(&folder);
    if (save) {
        gchar *name = gtk_file_chooser_get_current_name(chooser);
        if (name && *name) g_variant_builder_add(&options, "{sv}", "current_name", g_variant_new_string(name));
        g_free(name);
    }

    Pending *p = g_new0(Pending, 1);
    p->dialog = g_object_ref(dialog);
    p->bus = bus;
    /* Subscribed before the call, so a quick Response isn't missed. The path names this connection and a
     * fresh token, so it's only ever this request's. */
    p->subscription = g_dbus_connection_signal_subscribe(bus, NULL, "org.freedesktop.portal.Request", "Response", path, NULL,
                                                         G_DBUS_SIGNAL_FLAGS_NONE, on_response, p, NULL);
    const gchar *title = gtk_window_get_title(GTK_WINDOW(dialog));
    GError *error = NULL;
    GVariant *ret = g_dbus_connection_call_sync(bus, "org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop",
                                                "org.freedesktop.portal.FileChooser", save ? "SaveFile" : "OpenFile",
                                                g_variant_new("(ss@a{sv})", "", title ? title : "", g_variant_builder_end(&options)),
                                                G_VARIANT_TYPE("(o)"), G_DBUS_CALL_FLAGS_NONE, 5000, NULL, &error);
    g_free(path);
    if (!ret) {
        g_printerr("omalauncher-filechooser: %s; showing GTK's dialog\n", error->message);
        g_error_free(error);
        pending_free(p);
        return FALSE;
    }
    g_variant_unref(ret);
    g_object_set_data_full(G_OBJECT(dialog), PENDING, p, (GDestroyNotify) pending_free);
    return TRUE;
}

static void show(GtkWidget *widget) {
    /* Shown again (apps reuse dialogs): a new pick. */
    g_object_set_data(G_OBJECT(widget), PICKED, NULL);
    if (g_object_get_data(G_OBJECT(widget), PENDING)) return; /* already asking */
    if (!ask_portal(widget)) parent_show(widget);
}

G_MODULE_EXPORT void gtk_module_init(gint *argc, gchar ***argv) {
    GtkWidgetClass *klass = g_type_class_ref(GTK_TYPE_FILE_CHOOSER_DIALOG);
    parent_show = klass->show;
    klass->show = show;
    FileChooserIface *iface = g_type_interface_peek(klass, GTK_TYPE_FILE_CHOOSER);
    parent_get_files = iface->get_files;
    iface->get_files = get_files;
    parent_get_current_folder = iface->get_current_folder;
    iface->get_current_folder = get_current_folder;
}
