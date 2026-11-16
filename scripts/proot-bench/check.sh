#!/usr/bin/env bash
# Runs correctness.sh in the device guest under each named variant and diffs the outputs against
# the first one.   check.sh base aff2
set -euo pipefail
cd "$(dirname "$0")"
adb push correctness.sh /data/local/tmp/pb/rootfs/usr/local/bin/correctness >/dev/null
adb shell chmod 755 /data/local/tmp/pb/rootfs/usr/local/bin/correctness
OUT=$(mktemp -d)
for v in "$@"; do
  adb shell "cd /data/local/tmp/pb && V=\$PWD/v/$v && env \$(cat \$V/env 2>/dev/null) LD_LIBRARY_PATH=\$V PROOT_LOADER=\$V/libproot-loader.so PROOT_TMP_DIR=\$PWD/tmp \$(cat \$V/wrap 2>/dev/null) \$V/libproot.so --kill-on-exit --link2symlink -0 -r rootfs -b /dev -b /proc -b /sys -w /root /usr/bin/env -i \$(cat \$V/guestenv 2>/dev/null) HOME=/root USER=root LANG=C.UTF-8 PATH=/usr/local/bin:/usr/bin /bin/sh /usr/local/bin/correctness" >"$OUT/$v" 2>&1
done
first=$1; shift
cat "$OUT/$first"
for v in "$@"; do
  if diff "$OUT/$first" "$OUT/$v" >/dev/null; then echo "== $v: identical to $first"
  else echo "== $v differs from $first:"; diff "$OUT/$first" "$OUT/$v" || true; fi
done
