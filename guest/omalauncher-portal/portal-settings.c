/* Settings v2, completed from the original unfinished Settings implementation.
 * Contract: https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Settings.html
 * Built alongside omalauncher-portal.c; shares its session bus and runtime directory. */
#include "portals.h"
#include <fcntl.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#define OBJECT_PATH PORTAL_OBJECT_PATH
static const gchar *runtime_dir;
static GDBusConnection *bus;
#define APPEARANCE "org.freedesktop.appearance"
#define APPEARANCE_FILE "omalauncher-color-scheme"

static const gchar settings_xml[] =
    "<node><interface name='org.freedesktop.portal.Settings'>"
    "<method name='ReadAll'><arg type='as' direction='in'/><arg type='a{sa{sv}}' direction='out'/></method>"
    "<method name='ReadOne'><arg type='s' direction='in'/><arg type='s' direction='in'/><arg type='v' direction='out'/></method>"
    "<method name='Read'><arg type='s' direction='in'/><arg type='s' direction='in'/><arg type='v' direction='out'/></method>"
    "<signal name='SettingChanged'><arg type='s'/><arg type='s'/><arg type='v'/></signal>"
    "<property name='version' type='u' access='read'/>"
    "</interface></node>";

typedef struct {
    guint32 scheme;
    gdouble red, green, blue;
    guint32 contrast, reduced_motion;
} Appearance;
static Appearance last_appearance;
static GFileMonitor *appearance_monitor;

/* Android publishes light|dark, #RRGGBB, contrast, reduced-motion. The last
 * two lines are optional for old writers. Missing/bad preferences mean no
 * preference; (-1,-1,-1) is the spec's unset accent, not a fabricated color. */
static Appearance appearance_read(void) {
    Appearance a = {0, -1, -1, -1, 0, 0};
    gchar *path = g_build_filename(runtime_dir, APPEARANCE_FILE, NULL);
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK);
    g_free(path);
    if (fd < 0) return a;
    struct stat st;
    gchar data[128] = {0};
    ssize_t n = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) ? read(fd, data, sizeof(data) - 1) : -1;
    close(fd);
    if (n < 0) return a;
    gchar **lines = g_strsplit(data, "\n", 5);
    guint count = g_strv_length(lines);
    if (g_strcmp0(lines[0], "dark") == 0) a.scheme = 1;
    else if (g_strcmp0(lines[0], "light") == 0) a.scheme = 2;
    const gchar *hex = lines[0] && lines[1] ? lines[1] : "";
    gboolean valid = strlen(hex) == 7 && hex[0] == '#';
    for (gsize i = 1; valid && i < 7; i++) valid = g_ascii_isxdigit(hex[i]);
    if (valid) {
        guint64 rgb = g_ascii_strtoull(hex + 1, NULL, 16);
        a.red = ((rgb >> 16) & 255) / 255.0;
        a.green = ((rgb >> 8) & 255) / 255.0;
        a.blue = (rgb & 255) / 255.0;
    }
    if (count > 2 && g_str_equal(lines[2], "1")) a.contrast = 1;
    if (count > 3 && g_str_equal(lines[3], "1")) a.reduced_motion = 1;
    g_strfreev(lines);
    return a;
}

static GVariant *appearance_value(Appearance a, const gchar *key) {
    if (g_str_equal(key, "color-scheme")) return g_variant_new_uint32(a.scheme);
    if (g_str_equal(key, "accent-color")) return g_variant_new("(ddd)", a.red, a.green, a.blue);
    if (g_str_equal(key, "contrast")) return g_variant_new_uint32(a.contrast);
    if (g_str_equal(key, "reduced-motion")) return g_variant_new_uint32(a.reduced_motion);
    return NULL;
}

/* Empty list OR an empty string matches everything. Only a trailing * globs. */
static gboolean settings_wanted(GVariant *namespaces, const gchar *ns) {
    if (g_variant_n_children(namespaces) == 0) return TRUE;
    GVariantIter iter;
    const gchar *pattern;
    g_variant_iter_init(&iter, namespaces);
    while (g_variant_iter_next(&iter, "&s", &pattern)) {
        gsize n = strlen(pattern);
        if (!n || g_str_equal(pattern, ns)) return TRUE;
        if (pattern[n - 1] == '*' && strncmp(pattern, ns, n - 1) == 0) return TRUE;
    }
    return FALSE;
}

static void settings_call(GDBusConnection *conn, const gchar *sender, const gchar *path, const gchar *iface, const gchar *method,
                          GVariant *params, GDBusMethodInvocation *invocation, gpointer user_data) {
    Appearance a = appearance_read();
    if (g_str_equal(method, "ReadAll")) {
        GVariant *namespaces = g_variant_get_child_value(params, 0);
        GVariantBuilder all;
        g_variant_builder_init(&all, G_VARIANT_TYPE("a{sa{sv}}"));
        if (settings_wanted(namespaces, APPEARANCE)) {
            GVariantBuilder ns;
            g_variant_builder_init(&ns, G_VARIANT_TYPE_VARDICT);
            g_variant_builder_add(&ns, "{sv}", "color-scheme", appearance_value(a, "color-scheme"));
            g_variant_builder_add(&ns, "{sv}", "accent-color", appearance_value(a, "accent-color"));
            g_variant_builder_add(&ns, "{sv}", "contrast", appearance_value(a, "contrast"));
            g_variant_builder_add(&ns, "{sv}", "reduced-motion", appearance_value(a, "reduced-motion"));
            g_variant_builder_add(&all, "{sa{sv}}", APPEARANCE, &ns);
        }
        g_variant_unref(namespaces);
        g_dbus_method_invocation_return_value(invocation, g_variant_new("(a{sa{sv}})", &all));
        return;
    }
    const gchar *ns, *key;
    g_variant_get(params, "(&s&s)", &ns, &key);
    GVariant *value = g_str_equal(ns, APPEARANCE) ? appearance_value(a, key) : NULL;
    if (!value) {
        g_dbus_method_invocation_return_dbus_error(invocation, "org.freedesktop.portal.Error.NotFound", "Requested setting not found");
        return;
    }
    /* Legacy Read really does require the extra variant, unlike ReadOne. */
    if (g_str_equal(method, "Read")) value = g_variant_new_variant(value);
    g_dbus_method_invocation_return_value(invocation, g_variant_new("(v)", value));
}

static GVariant *settings_property(GDBusConnection *conn, const gchar *sender, const gchar *path, const gchar *iface,
                                   const gchar *name, GError **error, gpointer user_data) {
    return g_variant_new_uint32(2);
}
static const GDBusInterfaceVTable settings_vtable = {settings_call, settings_property, NULL, {0}};

static void appearance_changed(GFileMonitor *monitor, GFile *file, GFile *other, GFileMonitorEvent event, gpointer user_data) {
    gchar *name = g_file_get_basename(file);
    gchar *other_name = other ? g_file_get_basename(other) : NULL;
    gboolean ours = g_strcmp0(name, APPEARANCE_FILE) == 0 || g_strcmp0(other_name, APPEARANCE_FILE) == 0;
    g_free(name);
    g_free(other_name);
    if (!ours) return;
    Appearance a = appearance_read();
    if (a.scheme != last_appearance.scheme)
        g_dbus_connection_emit_signal(bus, NULL, OBJECT_PATH, "org.freedesktop.portal.Settings", "SettingChanged",
                                     g_variant_new("(ssv)", APPEARANCE, "color-scheme", appearance_value(a, "color-scheme")), NULL);
    if (a.red != last_appearance.red || a.green != last_appearance.green || a.blue != last_appearance.blue)
        g_dbus_connection_emit_signal(bus, NULL, OBJECT_PATH, "org.freedesktop.portal.Settings", "SettingChanged",
                                     g_variant_new("(ssv)", APPEARANCE, "accent-color", appearance_value(a, "accent-color")), NULL);
    if (a.contrast != last_appearance.contrast)
        g_dbus_connection_emit_signal(bus, NULL, OBJECT_PATH, "org.freedesktop.portal.Settings", "SettingChanged",
                                     g_variant_new("(ssv)", APPEARANCE, "contrast", appearance_value(a, "contrast")), NULL);
    if (a.reduced_motion != last_appearance.reduced_motion)
        g_dbus_connection_emit_signal(bus, NULL, OBJECT_PATH, "org.freedesktop.portal.Settings", "SettingChanged",
                                     g_variant_new("(ssv)", APPEARANCE, "reduced-motion", appearance_value(a, "reduced-motion")), NULL);
    last_appearance = a;
}

gboolean portal_settings_start(GDBusConnection *connection, const gchar *runtime, GError **error) {
    bus = connection;
    runtime_dir = runtime;
    GDBusNodeInfo *info = g_dbus_node_info_new_for_xml(settings_xml, error);
    if (!info) return FALSE;
    guint registration = g_dbus_connection_register_object(bus, OBJECT_PATH, info->interfaces[0], &settings_vtable, NULL, NULL, error);
    g_dbus_node_info_unref(info);
    if (!registration) return FALSE;
    /* Monitor the containing directory: atomic rename replaces the inode. */
    GFile *dir = g_file_new_for_path(runtime_dir);
    GError *monitor_error = NULL;
    appearance_monitor = g_file_monitor_directory(dir, G_FILE_MONITOR_WATCH_MOVES, NULL, &monitor_error);
    g_object_unref(dir);
    if (appearance_monitor) g_signal_connect(appearance_monitor, "changed", G_CALLBACK(appearance_changed), NULL);
    else {
        g_printerr("omalauncher-portal: appearance monitor: %s\n", monitor_error->message);
        g_clear_error(&monitor_error);
    }
    /* Install and connect the watch before sampling, still before bus-name
     * ownership. Otherwise a startup replacement can leave a stale baseline
     * that suppresses the next real transition back to the sampled value. */
    last_appearance = appearance_read();
    return TRUE;
}
