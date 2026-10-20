#!/usr/bin/env bash
# Read-only diagnostics for a connected phone after reproducing a LinuxApp issue.
set -euo pipefail

case_id="${1:-manual}"
if [[ ! "$case_id" =~ ^[A-Za-z0-9_-]+$ ]]; then
  echo 'Use a short case ID containing letters, digits, _ or -.' >&2
  exit 2
fi
adb_bin="${ADB_BIN:-adb}"
command -v "$adb_bin" >/dev/null || { echo 'Install Android platform-tools and put adb on PATH.' >&2; exit 1; }
"$adb_bin" get-state >/dev/null
package=dev.omalauncher.embedded
installed_path="$("$adb_bin" shell pm path "$package" | tr -d '\r')"
[[ "$installed_path" == package:* ]] || { echo "Install $package before collecting its diagnostics." >&2; exit 1; }

report_dir="${LINUXAPP_DIAGNOSTICS_DIR:-diagnostics}/linuxapp-${case_id}-$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir -p "$report_dir"
{
  printf 'case=%s\npackage=%s\n' "$case_id" "$package"
  for property in ro.product.manufacturer ro.product.model ro.build.version.release ro.build.version.sdk ro.product.cpu.abilist; do
    printf '%s=' "$property"
    "$adb_bin" shell getprop "$property" | tr -d '\r'
  done
} > "$report_dir/device.txt"
"$adb_bin" shell dumpsys package "$package" | sed -nE '/versionCode=|versionName=|firstInstallTime=|lastUpdateTime=/p' > "$report_dir/package.txt"
pid="$("$adb_bin" shell pidof -s "$package" 2>/dev/null | tr -d '\r' || true)"
if [[ "$pid" =~ ^[0-9]+$ ]]; then
  "$adb_bin" logcat -d -v threadtime --pid="$pid" > "$report_dir/app.log"
  "$adb_bin" logcat -d -b crash -v threadtime --pid="$pid" > "$report_dir/crash.log"
  printf 'pid=%s\n' "$pid" >> "$report_dir/device.txt"
else
  printf 'The app is not running. Start it, reproduce the case, and collect again.\n' > "$report_dir/app-not-running.txt"
fi
printf 'Diagnostics saved in %s\n' "$report_dir"
printf 'Add the case result and a screen recording or screenshot to this directory.\n'
