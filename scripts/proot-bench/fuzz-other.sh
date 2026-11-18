#!/usr/bin/env bash
# The fast lane and a change made by another proot on the same rootfs: one guest asks about a file
# below a directory over and over (fuzz poll), while a second proot turns that directory into a
# symlink and back.   fuzz-other.sh <asking variant> <changing variant> [seconds]
#   fuzz-other.sh fps fp      must print 0 differences
#   fuzz-other.sh fps main    a proot without patch 0003 doesn't count its changes: differences
set -euo pipefail
cd "$(dirname "$0")"
ASK=$1 CHANGE=$2 SECONDS_=${3:-8}
B=/data/local/tmp/pb
adb shell "cd $B && sh edge-run.sh $CHANGE /bin/sh -c 'rm -rf /root/fzx; mkdir -p /root/fzx/d /root/fzx/other; echo a > /root/fzx/d/file; echo bb > /root/fzx/other/file'" >/dev/null
adb shell "cd $B && sh edge-run.sh $ASK fuzz poll /root/fzx/d/file $SECONDS_" | grep -E -A2 'DIFF|questions:|^library' | cut -c1-200 | tail -8 &
adb shell "cd $B && sh edge-run.sh $CHANGE /bin/sh -c 'cd /root/fzx; for i in \$(seq $((SECONDS_ * 4))); do mv d keep; ln -s /root/fzx/other d; sleep 0.1; rm d; mv keep d; sleep 0.1; done'" >/dev/null
wait
