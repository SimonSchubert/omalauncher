#!/system/bin/sh
# Runs one benchmark case once under one proot variant, on the device as the adb shell user.
# device.sh <variant> <case>   prints "<wall ms> <pbench ns/op or -> <md5 of output>"
# A variant is a directory v/<name> with libproot.so, libproot-loader.so, libtalloc.so and
# libandroid-shmem.so, plus optional files: env (extra environment for proot, one VAR=value per
# line), guestenv (the same for the guest, such as LD_PRELOAD) and wrap (a command prefix such as
# "taskset 80").
B=/data/local/tmp/pb
V=$B/v/$1
CASE=$2

guest() {
  # Mirrors Proot.command() minus the app's own binds.
  env $(cat "$V/env" 2>/dev/null) \
    LD_LIBRARY_PATH="$V" PROOT_LOADER="$V/libproot-loader.so" PROOT_TMP_DIR="$B/tmp" \
    $(cat "$V/wrap" 2>/dev/null) "$V/libproot.so" --kill-on-exit --link2symlink -0 -r "$B/rootfs" \
    -b /dev -b /proc -b /sys -w /root \
    /usr/bin/env -i $(cat "$V/guestenv" 2>/dev/null) HOME=/root USER=root LANG=C.UTF-8 TERM=xterm-256color \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin /bin/sh -c "$1"
}

# pbench built in the guest (gcc -O2 pbench.c), so a preloaded library applies to it.
P=/usr/local/bin/pbench-dyn
ZONE=/usr/share/zoneinfo/Europe/Berlin
case $CASE in
  getpid)      CMD="$P getpid 200000" ;;
  stat)        CMD="$P stat 5000 /usr/lib/libc.so.6" ;;
  stat-l2s)    CMD="$P stat 5000 $ZONE" ;;
  stat-deep)   CMD="$P stat 5000 /usr/lib/node_modules/npm/node_modules/@gar/promise-retry/lib/index.js" ;;
  stat-link)   CMD="$P stat 5000 /bin/sh" ;;
  stat-miss)   CMD="$P nostat 5000 /usr/lib/nothing.so" ;;
  stat-nodir)  CMD="$P nostat 5000 /usr/lib/python3/site-packages/nothing.py" ;;
  stat-bind)   CMD="$P stat 5000 /proc/self/stat" ;;
  stat-rel)    CMD="cd /usr/lib && $P stat 5000 libc.so.6" ;;
  stat-up)     CMD="cd /usr/lib && $P stat 5000 ../lib/libc.so.6" ;;
  lstat)       CMD="$P lstat 5000 /usr/lib/libc.so.6" ;;
  statx)       CMD="$P statx 5000 /usr/lib/libc.so.6" ;;
  access)      CMD="$P access 5000 /usr/lib/libc.so.6" ;;
  fstat)       CMD="$P fstat 5000 /usr/lib/libc.so.6" ;;
  open)        CMD="$P open 5000 $ZONE" ;;
  open-plain)  CMD="$P open 5000 /usr/lib/libc.so.6" ;;
  open-miss)   CMD="$P noopen 5000 /usr/lib/nothing.so" ;;
  getcwd)      CMD="$P getcwd 5000" ;;
  readlink)    CMD="$P readlink 5000 /bin" ;;
  exec)        CMD="$P exec 100 /usr/bin/true" ;;
  stat-par4)   CMD="$P stat 2000 /usr/lib/libc.so.6 4" ;;
  stat-par8)   CMD="$P stat 1000 /usr/lib/libc.so.6 8" ;;
  find)        CMD="find /usr | wc -l" ;;
  ls-lR)       CMD="ls -lR /usr/lib | wc -l" ;;
  pacman-Qk)   CMD="pacman -Qk 2>&1 | tail -1" ;;
  sh-loop)     CMD="for i in \$(seq 200); do /usr/bin/true; done; echo ok" ;;
  tar)         CMD="tar cf - /usr/share/locale 2>/dev/null | md5sum" ;;
  node)        CMD="for i in 1 2 3; do node -e 0; done; echo ok" ;;
  npm)         CMD="npm --version" ;;
  git-status)  CMD="cd /root/repo && git status --porcelain | wc -l" ;;
  python)      CMD="python -c 'import json, email.parser, asyncio, http.client, argparse; print(1)'" ;;
  cpu-par4)    CMD="for i in 1 2 3 4; do dd if=/dev/zero bs=1M count=200 2>/dev/null | md5sum & done; wait" ;;
  find-par4)   CMD="for d in lib share include bin; do find /usr/\$d | wc -l & done; wait" ;;
  *) echo "unknown case $CASE" >&2; exit 2 ;;
esac

T0=$(date +%s%N)
OUT=$(guest "$CMD" 2>&1)
RC=$?
T1=$(date +%s%N)
[ $RC = 0 ] || { echo "FAIL rc=$RC: $OUT" >&2; exit 1; }
case $CASE in
  getpid|stat*|lstat|access|fstat|open*|getcwd|readlink|exec) NS=$OUT; HASH=- ;;
  *) NS=-; HASH=$(echo "$OUT" | sort | md5sum | cut -c1-8) ;;
esac
echo "$(awk "BEGIN { printf \"%d\", ($T1 - $T0) / 1000000 }") $NS $HASH"
