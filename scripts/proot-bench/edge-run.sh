#!/system/bin/sh
B=/data/local/tmp/pb; V=$B/v/$1; shift
env $(cat $V/env 2>/dev/null) LD_LIBRARY_PATH=$V PROOT_LOADER=$V/libproot-loader.so PROOT_TMP_DIR=$B/tmp timeout -s KILL 590 $V/libproot.so --kill-on-exit --link2symlink -0 -r $B/rootfs -b /dev -b /proc -b /sys -w /root /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 TERM=xterm-256color PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin "$@" 2>&1
echo "[proot rc=$?]"
