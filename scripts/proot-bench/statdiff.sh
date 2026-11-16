#!/usr/bin/env bash
# Runs statdiff.py in the device guest under each named variant and diffs the outputs against the
# first one.   statdiff.sh main fp
set -euo pipefail
cd "$(dirname "$0")"
adb push statdiff.py /data/local/tmp/pb/rootfs/usr/local/bin/statdiff >/dev/null
OUT=$(mktemp -d)
for v in "$@"; do
  adb shell "cd /data/local/tmp/pb && sh edge-run.sh $v python /usr/local/bin/statdiff" >"$OUT/$v" 2>&1
  echo "$v: $(wc -l <"$OUT/$v") lines, $(tail -1 "$OUT/$v")"
done
first=$1; shift
for v in "$@"; do
  if diff "$OUT/$first" "$OUT/$v" >/dev/null; then echo "== $v: identical to $first"
  else echo "== $v differs from $first in $(diff "$OUT/$first" "$OUT/$v" | grep -c '^>') lines:"; diff "$OUT/$first" "$OUT/$v" | head -40 || true; fi
done
