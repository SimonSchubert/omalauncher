#!/usr/bin/env bash
# Builds a proot variant and pushes it to the device as v/<name> for device.sh.
# variant.sh <name> [source tree]
# With no source tree it builds what the app ships (patches/proot). Extra files for the variant
# (env, wrap) can be added afterwards with adb push.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
NAME=$1
OUT="$ROOT/build/proot-bench/v/$NAME"
rm -rf "$OUT"
OUT="$OUT" PROOT_SRC="${2:-}" "$ROOT/scripts/fetch-proot.sh" >/dev/null
adb shell "rm -rf /data/local/tmp/pb/v/$NAME && mkdir -p /data/local/tmp/pb/v/$NAME"
adb push "$OUT"/* "/data/local/tmp/pb/v/$NAME/" >/dev/null
adb shell "chmod 755 /data/local/tmp/pb/v/$NAME/*"
echo "v/$NAME ready"
