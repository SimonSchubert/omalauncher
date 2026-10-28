/* Test-only startup injection: publish B at the entry to watch installation.
 * Old code has already sampled A here; watch-first code has not sampled yet.
 * No production hooks, timing sleeps, or main-context dispatch are involved. */
#define _GNU_SOURCE
#include <gio/gio.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

GFileMonitor *g_file_monitor_directory(GFile *file, GFileMonitorFlags flags,
                                      GCancellable *cancellable, GError **error) {
    static unsigned calls;
    const char *kind = getenv("PORTAL_TEST_WATCH_KIND");
    unsigned target = kind && strcmp(kind, "status") == 0 ? 2 : 1;
    ++calls;
    if (target == 2 && calls == 1) {
        /* GIO shares directory watches. Isolate status from the earlier
         * Settings watch, which could otherwise queue our injected rename
         * for a status subscriber added later and hide the old-order bug. */
        g_set_error_literal(error, G_IO_ERROR, G_IO_ERROR_NOT_SUPPORTED,
                            "test: isolating status startup watch");
        return NULL;
    }
    if (calls == target) {
        const char *runtime = getenv("XDG_RUNTIME_DIR");
        char *path = g_build_filename(runtime, target == 1 ? "omalauncher-color-scheme" : "omalauncher-system-status", NULL);
        char *tmp = g_strconcat(path, ".startup", NULL);
        const char *text = target == 1 ? "dark\n#112233\n1\n1\n" : "power-saver=1\navailable=1\nmetered=0\nconnectivity=4\n";
        FILE *out = fopen(tmp, "w");
        if (!out || fputs(text, out) < 0 || fclose(out) != 0 || rename(tmp, path) != 0) {
            perror("startup injection");
            _exit(91);
        }
        fprintf(stderr, "startup injection: %s before watch %u\n", kind, target);
        g_free(tmp);
        g_free(path);
    }
    GFileMonitor *(*real_monitor)(GFile *, GFileMonitorFlags, GCancellable *, GError **) = dlsym(RTLD_NEXT, "g_file_monitor_directory");
    if (!real_monitor) _exit(92);
    return real_monitor(file, flags, cancellable, error);
}
