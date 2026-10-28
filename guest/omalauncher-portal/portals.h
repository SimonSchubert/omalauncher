#pragma once
#include <gio/gio.h>
#define PORTAL_BUS_NAME "org.freedesktop.portal.Desktop"
#define PORTAL_OBJECT_PATH "/org/freedesktop/portal/desktop"
gboolean portal_settings_start(GDBusConnection *connection, const gchar *runtime, GError **error);
gboolean portal_actions_start(GDBusConnection *connection, const gchar *runtime, GError **error);
gboolean portal_status_start(GDBusConnection *connection, const gchar *runtime, GError **error);
gint portal_transport_connect(const gchar *runtime, GError **error);
gboolean portal_transport_send(gint fd, const gchar *packet, gsize length, const gint *fds, guint n_fds, GError **error);
void portal_packet_field(GString *packet, const gchar *key, const gchar *value);
