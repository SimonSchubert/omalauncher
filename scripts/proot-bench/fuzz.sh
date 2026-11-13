#!/usr/bin/env bash
# Runs fuzz.c in the device guest: the fast lane library against proot itself, question by question.
#   fuzz.sh <variant> <mode> <first seed> <last seed> <questions per seed>
#   fuzz.sh fps run 1 20 100000
# The variant's guestenv should preload libfastpath-stats.so (fastpath.sh builds it), so the summary
# tells how many questions the library answered itself. Prints one summary per seed and every
# "DIFF"; fails when there was one.
set -euo pipefail
cd "$(dirname "$0")"
V=$1 MODE=$2 FIRST=$3 LAST=$4 N=$5
B=/data/local/tmp/pb
adb push fuzz.c $B/rootfs/root/fuzz.c >/dev/null
adb shell "cd $B && sh edge-run.sh main gcc -O2 -Wall -pthread /root/fuzz.c -o /usr/local/bin/fuzz" | tee /dev/stderr | grep -q '^\[proot rc=0\]'
bad=0
for seed in $(seq "$FIRST" "$LAST"); do
  # From outside: the tree has directories without permissions and names proot can't remove.
  adb shell "chmod -R u+rwx $B/rootfs/root/fz 2>/dev/null; rm -rf $B/rootfs/root/fz"
  out=$(adb shell "cd $B && sh edge-run.sh $V fuzz $MODE $seed $N $B/rootfs" 2>&1) || true
  echo "$out" | grep -E -A3 '^DIFF|^step [0-9]: [^o]' | cut -c1-300 | head -40 || true
  echo "seed $seed: $(echo "$out" | grep -E 'questions:|^library|proot rc' | tr '\n' ' ')"
  echo "$out" | grep -q 'proot rc=0' || bad=1
done
exit $bad
