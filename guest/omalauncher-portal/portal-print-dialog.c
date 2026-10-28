/* Native GTK/CUPS print-dialog subprocess. The D-Bus service owns request and
 * token lifetimes. Public GTK 3.22+ print/page-setup GVariant APIs preserve the
 * application's actual settings rather than inventing printer/page selections. */
#include <gtk/gtkunixprint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static GMainLoop *wait_loop;
static gboolean completed;
static guint result_status = 2;
static void result(guint status, GtkPrintSettings *settings, GtkPageSetup *page) {
    GVariantBuilder empty1, empty2;
    g_variant_builder_init(&empty1, G_VARIANT_TYPE_VARDICT);
    g_variant_builder_init(&empty2, G_VARIANT_TYPE_VARDICT);
    GVariant *value = g_variant_ref_sink(g_variant_new("(u@a{sv}@a{sv})", status,
        settings ? gtk_print_settings_to_gvariant(settings) : g_variant_builder_end(&empty1),
        page ? gtk_page_setup_to_gvariant(page) : g_variant_builder_end(&empty2)));
    gchar *text = g_variant_print(value, TRUE); puts(text); g_free(text); g_variant_unref(value);
}
static gboolean printer_match(GtkPrinter *printer, gpointer data) {
    gpointer *pair = data;
    if (g_strcmp0(gtk_printer_get_name(printer), pair[0]) != 0) return FALSE;
    pair[1] = g_object_ref(printer); return TRUE;
}
static void details_ready(GtkPrinter *printer, gboolean success, gpointer data) {
    completed = TRUE; *(gboolean *)data = success; g_main_loop_quit(wait_loop);
}
static void sent(GtkPrintJob *job, gpointer data, const GError *error) {
    result_status = error ? 2 : 0; completed = TRUE;
    if (error) g_printerr("CUPS submission failed: %s\n", error->message);
    g_main_loop_quit(wait_loop);
}
static GtkPrinter *selected_printer(GtkPrintSettings *settings) {
    const gchar *name = gtk_print_settings_get_printer(settings);
    if (!name) return NULL;
    gpointer pair[] = { (gpointer)name, NULL };
    gtk_enumerate_printers(printer_match, pair, NULL, TRUE);
    GtkPrinter *printer = pair[1];
    if (printer && !gtk_printer_has_details(printer)) {
        gboolean success = FALSE; completed = FALSE;
        gulong signal = g_signal_connect(printer, "details-acquired", G_CALLBACK(details_ready), &success);
        gtk_printer_request_details(printer);
        if (!completed) g_main_loop_run(wait_loop);
        g_signal_handler_disconnect(printer, signal);
        if (!success) g_clear_object(&printer);
    }
    return printer;
}
int main(int argc, char **argv) {
    if (argc == 2 && g_str_equal(argv[1], "--self-test")) {
        GtkPrintSettings *settings = gtk_print_settings_new();
        gtk_print_settings_set_printer(settings, "Android");
        gtk_print_settings_set_n_copies(settings, 3);
        GtkPageSetup *page = gtk_page_setup_new();
        GVariant *serialized = gtk_print_settings_to_gvariant(settings);
        GtkPrintSettings *copy = gtk_print_settings_new_from_gvariant(serialized);
        g_assert_cmpstr(gtk_print_settings_get_printer(copy), ==, "Android");
        g_assert_cmpint(gtk_print_settings_get_n_copies(copy), ==, 3);
        g_variant_unref(serialized);
        serialized = gtk_page_setup_to_gvariant(page);
        GtkPageSetup *page_copy = gtk_page_setup_new_from_gvariant(serialized);
        g_assert_nonnull(page_copy);
        g_assert_cmpfloat(gtk_page_setup_get_paper_width(page, GTK_UNIT_MM), ==, gtk_page_setup_get_paper_width(page_copy, GTK_UNIT_MM));
        g_variant_unref(serialized); result(0, copy, page_copy);
        g_object_unref(settings); g_object_unref(copy); g_object_unref(page); g_object_unref(page_copy);
        return 0;
    }
    if (argc != 3 || (!g_str_equal(argv[1], "--prepare") && !g_str_equal(argv[1], "--print") && !g_str_equal(argv[1], "--print-dialog"))) return 2;
    gchar input[65537]; gsize length = fread(input, 1, sizeof input - 1, stdin); input[length] = 0;
    if (!feof(stdin)) return 2;
    GError *error = NULL;
    GVariant *initial = g_variant_parse(G_VARIANT_TYPE("(a{sv}a{sv}asb)"), input, NULL, NULL, &error);
    if (!initial) { g_printerr("Invalid print parameters: %s\n", error->message); g_clear_error(&error); return 2; }
    GVariant *settings_value, *page_value; gchar **formats; gboolean modal;
    g_variant_get(initial, "(@a{sv}@a{sv}^asb)", &settings_value, &page_value, &formats, &modal);
    GtkPrintSettings *settings = gtk_print_settings_new_from_gvariant(settings_value);
    GtkPageSetup *page = g_variant_n_children(page_value) ? gtk_page_setup_new_from_gvariant(page_value) : gtk_page_setup_new();
    if (!page) page = gtk_page_setup_new();
    if (!settings) settings = gtk_print_settings_new();
    g_variant_unref(settings_value); g_variant_unref(page_value); g_variant_unref(initial);
    if (!gtk_print_settings_get_printer(settings)) gtk_print_settings_set_printer(settings, "Android");
    if (!gtk_init_check(NULL, NULL)) { result(2, NULL, NULL); return 0; }
    wait_loop = g_main_loop_new(NULL, FALSE);
    GtkPrinter *printer = NULL;
    if (!g_str_equal(argv[1], "--print")) {
        GtkWidget *dialog = gtk_print_unix_dialog_new(argv[2], NULL);
        /* PreparePrint callers render these settings before Print, just as
         * GtkPrintOperation's portal path does. Without the manual capability
         * flags GTK disables controls unsupported by the printer itself. Do
         * not expand them again when submitting the already-rendered source. */
        GtkPrintCapabilities capabilities = GTK_PRINT_CAPABILITY_PAGE_SET |
            GTK_PRINT_CAPABILITY_COPIES | GTK_PRINT_CAPABILITY_COLLATE |
            GTK_PRINT_CAPABILITY_REVERSE | GTK_PRINT_CAPABILITY_SCALE |
            GTK_PRINT_CAPABILITY_NUMBER_UP;
        for (guint i = 0; formats[i]; i++) {
            if (g_str_equal(formats[i], "pdf")) capabilities |= GTK_PRINT_CAPABILITY_GENERATE_PDF;
            if (g_str_equal(formats[i], "ps")) capabilities |= GTK_PRINT_CAPABILITY_GENERATE_PS;
        }
        gtk_print_unix_dialog_set_manual_capabilities(GTK_PRINT_UNIX_DIALOG(dialog), capabilities);
        gtk_print_unix_dialog_set_embed_page_setup(GTK_PRINT_UNIX_DIALOG(dialog), TRUE);
        gtk_print_unix_dialog_set_settings(GTK_PRINT_UNIX_DIALOG(dialog), settings);
        gtk_print_unix_dialog_set_page_setup(GTK_PRINT_UNIX_DIALOG(dialog), page);
        gtk_window_set_modal(GTK_WINDOW(dialog), modal);
        gint response = gtk_dialog_run(GTK_DIALOG(dialog));
        if (response != GTK_RESPONSE_OK) { result(1, NULL, NULL); gtk_widget_destroy(dialog); return 0; }
        GtkPrintSettings *chosen = gtk_print_unix_dialog_get_settings(GTK_PRINT_UNIX_DIALOG(dialog));
        GtkPageSetup *chosen_page = gtk_print_unix_dialog_get_page_setup(GTK_PRINT_UNIX_DIALOG(dialog));
        printer = gtk_print_unix_dialog_get_selected_printer(GTK_PRINT_UNIX_DIALOG(dialog));
        if (printer) g_object_ref(printer);
        g_set_object(&settings, chosen); g_set_object(&page, chosen_page); g_object_unref(chosen);
        gtk_widget_destroy(dialog);
    }
    g_strfreev(formats);
    if (g_str_equal(argv[1], "--prepare")) result(printer ? 0 : 2, settings, page);
    else {
        if (!printer) printer = selected_printer(settings);
        if (printer) {
            GtkPrintJob *job = gtk_print_job_new(argv[2], printer, settings, page);
            if (gtk_print_job_set_source_fd(job, 3, &error)) {
                completed = FALSE; gtk_print_job_send(job, sent, NULL, NULL);
                if (!completed) g_main_loop_run(wait_loop);
            } else { g_printerr("Invalid print source: %s\n", error->message); g_clear_error(&error); }
            g_object_unref(job);
        }
        result(result_status, NULL, NULL);
    }
    g_clear_object(&printer); g_object_unref(settings); g_object_unref(page); g_main_loop_unref(wait_loop);
    return 0;
}
