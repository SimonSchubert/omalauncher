/* Power Profile Monitor v1 (org.freedesktop.portal.PowerProfileMonitor) and
 * Network Monitor v2 (org.freedesktop.portal.NetworkMonitor), read from the
 * atomic snapshot Android publishes beside the appearance file.
 * Contracts: https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.PowerProfileMonitor.html
 *            https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.NetworkMonitor.html
 *
 * Exact snapshot lines (any order, each "key=value"):
 *   power-saver=0|1
 *   available=0|1
 *   metered=0|1
 *   connectivity=1|2|3|4   (XDG: 1 local-only, 2 limited, 3 captive, 4 full)
 * A field that is missing, malformed or the literal "unknown" is unknown,
 * never a fabricated offline/unmetered/non-power-saving claim. power-saver
 * is tracked independently; the network triplet (available/metered/
 * connectivity) is valid only when all three parse. GetAvailable/GetMetered/
 * GetConnectivity and the power-saver-enabled property return a D-Bus error
 * rather than invented data when their value is unknown.
 *
 * NetworkMonitor deliberately advertises version 2 only: GetStatus and
 * CanReach are not implemented. A modern GLib org.freedesktop.portal.Desktop
 * proxy always tries GetStatus first and falls back to the version-2 getters
 * on UNKNOWN_METHOD (see gio/gnetworkmonitorportal.c, update_properties() /
 * got_status()), so this is fully compatible with that frontend without a
 * real reachability implementation.
 *
 * Built alongside omalauncher-portal.c; shares its session bus and runtime
 * directory, the same way portal-settings.c does for appearance. */
#include "portals.h"
#include <fcntl.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#define OBJECT_PATH PORTAL_OBJECT_PATH
#define STATUS_FILE "omalauncher-system-status"
#define POWER_IFACE "org.freedesktop.portal.PowerProfileMonitor"
#define NETWORK_IFACE "org.freedesktop.portal.NetworkMonitor"

static const gchar *runtime_dir;
static GDBusConnection *bus;
static GFileMonitor *status_monitor;

typedef struct {
    gboolean power_known, power_saver;
    gboolean network_known, available, metered;
    guint32 connectivity;
} Status;
static Status last_status;

static const gchar power_xml[] =
    "<node><interface name='org.freedesktop.portal.PowerProfileMonitor'>"
    "<property name='power-saver-enabled' type='b' access='read'/>"
    "<property name='version' type='u' access='read'/>"
    "</interface></node>";

static const gchar network_xml[] =
    "<node><interface name='org.freedesktop.portal.NetworkMonitor'>"
    "<method name='GetAvailable'><arg type='b' direction='out'/></method>"
    "<method name='GetMetered'><arg type='b' direction='out'/></method>"
    "<method name='GetConnectivity'><arg type='u' direction='out'/></method>"
    "<signal name='changed'/>"
    "<property name='version' type='u' access='read'/>"
    "</interface></node>";

static gboolean parse_bool01(const gchar *value, gboolean *out) {
    if (g_strcmp0(value, "0") == 0) { *out = FALSE; return TRUE; }
    if (g_strcmp0(value, "1") == 0) { *out = TRUE; return TRUE; }
    return FALSE;
}
static gboolean parse_connectivity(const gchar *value, guint32 *out) {
    if (!value || strlen(value) != 1 || value[0] < '1' || value[0] > '4') return FALSE;
    *out = (guint32)(value[0] - '0');
    return TRUE;
}

/* Bounded, no-follow, regular-file-only read of the atomically replaced
 * snapshot. Missing, non-regular (including a symlink destination), empty
 * or oversized data all come back fully unknown rather than partially or
 * speculatively parsed. */
static Status status_read(void) {
    Status s = {0};
    gchar *path = g_build_filename(runtime_dir, STATUS_FILE, NULL);
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK);
    g_free(path);
    if (fd < 0) return s;
    struct stat st;
    gchar data[512] = {0};
    ssize_t n = -1;
    if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0 && st.st_size < (off_t) sizeof(data))
        n = read(fd, data, sizeof(data) - 1);
    close(fd);
    if (n < 0) return s;

    gboolean have_power = FALSE, power_valid = FALSE, power_saver = FALSE;
    gboolean have_avail = FALSE, avail_valid = FALSE, avail = FALSE;
    gboolean have_metered = FALSE, metered_valid = FALSE, metered = FALSE;
    gboolean have_conn = FALSE, conn_valid = FALSE;
    guint32 conn = 0;

    gchar **lines = g_strsplit(data, "\n", -1);
    for (gchar **l = lines; *l; l++) {
        gchar *eq = strchr(*l, '=');
        if (!eq) continue;
        *eq = 0;
        const gchar *key = *l, *value = eq + 1;
        if (g_str_equal(key, "power-saver")) { have_power = TRUE; power_valid = parse_bool01(value, &power_saver); }
        else if (g_str_equal(key, "available")) { have_avail = TRUE; avail_valid = parse_bool01(value, &avail); }
        else if (g_str_equal(key, "metered")) { have_metered = TRUE; metered_valid = parse_bool01(value, &metered); }
        else if (g_str_equal(key, "connectivity")) { have_conn = TRUE; conn_valid = parse_connectivity(value, &conn); }
    }
    g_strfreev(lines);

    s.power_known = have_power && power_valid;
    s.power_saver = s.power_known && power_saver;
    /* Power may be known while the network is not, and vice versa: the two
     * are independent fields, but available/metered/connectivity together
     * form one network snapshot that is only as good as its worst field. */
    s.network_known = have_avail && avail_valid && have_metered && metered_valid && have_conn && conn_valid;
    if (s.network_known) { s.available = avail; s.metered = metered; s.connectivity = conn; }
    return s;
}

static GVariant *power_property(GDBusConnection *conn, const gchar *sender, const gchar *path, const gchar *iface,
                                const gchar *name, GError **error, gpointer user_data) {
    if (g_str_equal(name, "version")) return g_variant_new_uint32(1);
    Status s = status_read();
    if (!s.power_known) {
        g_set_error_literal(error, G_DBUS_ERROR, G_DBUS_ERROR_FAILED, "Power profile not available");
        return NULL;
    }
    return g_variant_new_boolean(s.power_saver);
}
static const GDBusInterfaceVTable power_vtable = {NULL, power_property, NULL, {0}};

static void network_call(GDBusConnection *conn, const gchar *sender, const gchar *path, const gchar *iface, const gchar *method,
                         GVariant *params, GDBusMethodInvocation *invocation, gpointer user_data) {
    Status s = status_read();
    if (!s.network_known) {
        g_dbus_method_invocation_return_dbus_error(invocation, "org.freedesktop.portal.Error.Failed", "Network status not available");
        return;
    }
    if (g_str_equal(method, "GetAvailable")) g_dbus_method_invocation_return_value(invocation, g_variant_new("(b)", s.available));
    else if (g_str_equal(method, "GetMetered")) g_dbus_method_invocation_return_value(invocation, g_variant_new("(b)", s.metered));
    else if (g_str_equal(method, "GetConnectivity")) g_dbus_method_invocation_return_value(invocation, g_variant_new("(u)", s.connectivity));
    else g_dbus_method_invocation_return_dbus_error(invocation, "org.freedesktop.DBus.Error.UnknownMethod", "Unknown method");
}
static GVariant *network_property(GDBusConnection *conn, const gchar *sender, const gchar *path, const gchar *iface,
                                  const gchar *name, GError **error, gpointer user_data) {
    return g_variant_new_uint32(2);
}
static const GDBusInterfaceVTable network_vtable = {network_call, network_property, NULL, {0}};

static void status_changed(GFileMonitor *monitor, GFile *file, GFile *other, GFileMonitorEvent event, gpointer user_data) {
    gchar *name = g_file_get_basename(file);
    gchar *other_name = other ? g_file_get_basename(other) : NULL;
    gboolean ours = g_strcmp0(name, STATUS_FILE) == 0 || g_strcmp0(other_name, STATUS_FILE) == 0;
    g_free(name);
    g_free(other_name);
    if (!ours) return;

    Status s = status_read();

    /* power-saver-enabled: dedupe an unchanged known value; invalidate
     * (never fabricate false) a previously known value that became
     * unknown; announce a freshly known or genuinely different value. */
    gboolean power_differs = s.power_known != last_status.power_known ||
        (s.power_known && s.power_saver != last_status.power_saver);
    if (power_differs) {
        GVariantBuilder changed;
        g_variant_builder_init(&changed, G_VARIANT_TYPE_VARDICT);
        if (s.power_known) {
            g_variant_builder_add(&changed, "{sv}", "power-saver-enabled", g_variant_new_boolean(s.power_saver));
            const gchar *none[] = {NULL};
            g_dbus_connection_emit_signal(bus, NULL, OBJECT_PATH, "org.freedesktop.DBus.Properties", "PropertiesChanged",
                g_variant_new("(sa{sv}^as)", POWER_IFACE, &changed, none), NULL);
        } else {
            const gchar *invalidated[] = {"power-saver-enabled", NULL};
            g_dbus_connection_emit_signal(bus, NULL, OBJECT_PATH, "org.freedesktop.DBus.Properties", "PropertiesChanged",
                g_variant_new("(sa{sv}^as)", POWER_IFACE, &changed, invalidated), NULL);
        }
    }

    /* NetworkMonitor has one "changed" signal, not per-property ones;
     * dedupe against the whole known/unknown + value tuple so repeated
     * writes of the same snapshot stay silent. */
    gboolean network_differs = s.network_known != last_status.network_known ||
        (s.network_known && (s.available != last_status.available || s.metered != last_status.metered || s.connectivity != last_status.connectivity));
    if (network_differs)
        g_dbus_connection_emit_signal(bus, NULL, OBJECT_PATH, NETWORK_IFACE, "changed", NULL, NULL);

    last_status = s;
}

gboolean portal_status_start(GDBusConnection *connection, const gchar *runtime, GError **error) {
    bus = connection;
    runtime_dir = runtime;

    GDBusNodeInfo *power_info = g_dbus_node_info_new_for_xml(power_xml, error);
    if (!power_info) return FALSE;
    guint power_registration = g_dbus_connection_register_object(bus, OBJECT_PATH, power_info->interfaces[0], &power_vtable, NULL, NULL, error);
    g_dbus_node_info_unref(power_info);
    if (!power_registration) return FALSE;

    GDBusNodeInfo *network_info = g_dbus_node_info_new_for_xml(network_xml, error);
    if (!network_info) return FALSE;
    guint network_registration = g_dbus_connection_register_object(bus, OBJECT_PATH, network_info->interfaces[0], &network_vtable, NULL, NULL, error);
    g_dbus_node_info_unref(network_info);
    if (!network_registration) return FALSE;

    /* Monitor the containing directory: atomic rename replaces the inode. */
    GFile *dir = g_file_new_for_path(runtime_dir);
    GError *monitor_error = NULL;
    status_monitor = g_file_monitor_directory(dir, G_FILE_MONITOR_WATCH_MOVES, NULL, &monitor_error);
    g_object_unref(dir);
    if (status_monitor) g_signal_connect(status_monitor, "changed", G_CALLBACK(status_changed), NULL);
    else {
        g_printerr("omalauncher-portal: status monitor: %s\n", monitor_error->message);
        g_clear_error(&monitor_error);
    }
    /* Install and connect the watch before sampling, still before bus-name
     * ownership, so a startup replacement cannot leave a stale baseline. */
    last_status = status_read();
    return TRUE;
}
