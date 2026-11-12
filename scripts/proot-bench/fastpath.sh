#!/usr/bin/env bash
# Builds the fast lane library (patches/proot/fastpath) and pushes it into the bench rootfs, where
# a variant's guestenv file preloads it: LD_PRELOAD=/usr/local/lib/libfastpath.so
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_HOME:-$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties")}"
TC="$(echo "$SDK"/ndk/*/toolchains/llvm/prebuilt/*/bin | tr ' ' '\n' | tail -1)"
mkdir -p "$ROOT/build/proot-bench/fp"
build() {
  "$TC/clang" --target=aarch64-linux-gnu -O2 -fPIC -shared -nostdlib -ffreestanding -fvisibility=hidden \
    -mno-outline-atomics -fno-stack-protector -Wall -Wextra -fuse-ld=lld -Wl,-z,now -Wl,--build-id=none \
    "$@" "$ROOT/patches/proot/fastpath/fastpath.c"
}
build -o "$ROOT/build/proot-bench/fp/libfastpath.so"
# With counters, for fuzz.c: preload this one to see how many calls the library answered.
build -DFASTPATH_STATS -o "$ROOT/build/proot-bench/fp/libfastpath-stats.so"
adb shell mkdir -p /data/local/tmp/pb/rootfs/usr/local/lib
adb push "$ROOT"/build/proot-bench/fp/libfastpath*.so /data/local/tmp/pb/rootfs/usr/local/lib/ >/dev/null
adb shell chmod 755 /data/local/tmp/pb/rootfs/usr/local/lib/libfastpath.so /data/local/tmp/pb/rootfs/usr/local/lib/libfastpath-stats.so
echo "libfastpath.so ready"
