/* Actual GTK caller-side formatting of PreparePrint settings. GTK's portal
 * caller uses the file backend too (gtkprintoperation-portal.c:finish_print).
 * No printer dialog, personal configuration, Android, or simulated renderer. */
#include <gtk/gtkunixprint.h>
#include <stdio.h>

static void draw_page(GtkPrintOperation *operation, GtkPrintContext *context,
                      gint page, gpointer data) {
    cairo_t *cr = gtk_print_context_get_cairo_context(context);
    gchar *text = g_strdup_printf("GTK selected page %d", page + 1);
    cairo_select_font_face(cr, "Sans", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_NORMAL);
    cairo_set_font_size(cr, 18);
    cairo_move_to(cr, 72, 100);
    cairo_show_text(cr, text);
    g_free(text);
}

static gboolean accept_file_dialog(gpointer data) {
    GList *windows = gtk_window_list_toplevels();
    for (GList *item = windows; item; item = item->next) {
        if (!GTK_IS_PRINT_UNIX_DIALOG(item->data)) continue;
        GtkPrinter *printer = gtk_print_unix_dialog_get_selected_printer(item->data);
        if (!printer || !gtk_printer_has_details(printer)) continue;
        g_assert_cmpstr(gtk_printer_get_name(printer), ==, "Print to File");
        gtk_dialog_response(GTK_DIALOG(item->data), GTK_RESPONSE_OK);
        g_list_free(windows);
        return G_SOURCE_REMOVE;
    }
    g_list_free(windows);
    return G_SOURCE_CONTINUE;
}

int main(int argc, char **argv) {
    if (argc != 2 || !gtk_init_check(NULL, NULL)) return 1;
    g_object_set(gtk_settings_get_default(), "gtk-print-backends", "file", NULL);
    GtkPrintSettings *settings = gtk_print_settings_new();
    gtk_print_settings_set_printer(settings, "Print to File");
    gtk_print_settings_set_n_copies(settings, 2);
    GtkPageRange range = {1, 1}; /* Zero-based: only the second of three pages. */
    gtk_print_settings_set_print_pages(settings, GTK_PRINT_PAGES_RANGES);
    gtk_print_settings_set_page_ranges(settings, &range, 1);
    gtk_print_settings_set(settings, GTK_PRINT_SETTINGS_OUTPUT_FILE_FORMAT, "pdf");
    GError *error = NULL;
    gchar *uri = g_filename_to_uri(argv[1], NULL, &error);
    if (!uri) { fprintf(stderr, "%s\n", error->message); return 1; }
    gtk_print_settings_set(settings, GTK_PRINT_SETTINGS_OUTPUT_URI, uri);
    g_free(uri);
    GtkPrintOperation *operation = gtk_print_operation_new();
    gtk_print_operation_set_n_pages(operation, 3);
    gtk_print_operation_set_print_settings(operation, settings);
    g_signal_connect(operation, "draw-page", G_CALLBACK(draw_page), NULL);
    /* GTK's direct PRINT finder deliberately skips virtual printers. Accept
     * our own file-printer dialog through GTK instead, without user input. */
    g_timeout_add(20, accept_file_dialog, NULL);
    GtkPrintOperationResult result = gtk_print_operation_run(operation, GTK_PRINT_OPERATION_ACTION_PRINT_DIALOG, NULL, &error);
    if (result != GTK_PRINT_OPERATION_RESULT_APPLY) {
        fprintf(stderr, "GTK rendering failed: %s\n", error ? error->message : "not applied");
        return 1;
    }
    while (!gtk_print_operation_is_finished(operation)) g_main_context_iteration(NULL, TRUE);
    if (gtk_print_operation_get_status(operation) == GTK_PRINT_STATUS_FINISHED_ABORTED) {
        fprintf(stderr, "GTK rendering aborted\n"); return 1;
    }
    g_object_unref(operation);
    g_object_unref(settings);
    return 0;
}
