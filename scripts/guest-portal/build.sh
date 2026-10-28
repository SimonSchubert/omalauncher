#!/bin/sh
# Builds the guest portal, GTK3 module/dialogs and Android CUPS-PDF bridge into
# APK assets. Run in aarch64 Arch Linux (the launcher's Linux works) with gcc,
# pkgconf, glib2 and gtk3. No host package installation here.
set -eu
here=$(realpath "$(dirname -- "$0")/../..")
# Relative source paths keep assertion strings free of the builder's home path.
cd "$here"
src=guest/omalauncher-portal
out="$here/app/src/main/assets/guest-portal"
mkdir -p "$out"
gcc -O2 -Wall -o "$out/omalauncher-portal" "$src/omalauncher-portal.c" "$src/portal-settings.c" "$src/portal-actions.c" "$src/portal-status.c" "$src/portal-transport.c" $(pkg-config --cflags --libs gio-2.0 gio-unix-2.0)
gcc -O2 -Wall -shared -fPIC -o "$out/libomalauncher-filechooser.so" "$src/filechooser-module.c" $(pkg-config --cflags --libs gtk+-3.0)

gcc -O2 -Wall -o "$out/omalauncher-print-dialog" "$src/portal-print-dialog.c" $(pkg-config --cflags --libs gtk+-unix-print-3.0)
gcc -O2 -Wall -o "$out/omalauncher-print-android" "$src/portal-print-forward.c" "$src/portal-transport.c" $(pkg-config --cflags --libs gio-2.0 gio-unix-2.0)
gcc -O2 -Wall -o "$out/omalauncher-cups-backend" "$src/portal-cups-backend.c"
for name in omalauncher-portal libomalauncher-filechooser.so omalauncher-print-dialog omalauncher-print-android omalauncher-cups-backend; do
    strip --strip-unneeded "$out/$name"
    sha256sum "$out/$name"
done
