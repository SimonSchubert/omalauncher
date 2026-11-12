# proot benchmarks

A/B benchmarks of proot builds on a real device, run as the adb shell user from
`/data/local/tmp/pb`, so any build can be swapped in without touching the app or its data.

## Setup (once per device)

```sh
export ANDROID_SERIAL=<device>
adb shell mkdir -p /data/local/tmp/pb/rootfs /data/local/tmp/pb/tmp /data/local/tmp/pb/v
adb push ArchLinuxARM-aarch64-latest.tar.gz /data/local/tmp/pb/alarm.tgz
# extract under proot (link2symlink for the hard links), with any proot build, e.g. v/base
adb shell 'cd /data/local/tmp/pb && V=$PWD/v/base && LD_LIBRARY_PATH=$V PROOT_LOADER=$V/libproot-loader.so \
  PROOT_TMP_DIR=$PWD/tmp $V/libproot.so --link2symlink -0 /system/bin/tar xzf alarm.tgz -C rootfs'
# then: resolv.conf, SigLevel = Never, pacman -Sy nodejs npm git python, /root/repo as a git
# copy of /usr/lib/node_modules/npm, and pbench (NDK clang -O2 -static pbench.c) in /usr/local/bin
adb push device.sh /data/local/tmp/pb/
```

## Variants

`v/<name>` holds libproot.so, libproot-loader.so, libtalloc.so and libandroid-shmem.so, plus
optional `env` (extra VAR=value lines) and `wrap` (a command prefix such as `taskset 80`).

```sh
scripts/proot-bench/variant.sh new path/to/patched/proot/tree   # build + push
adb shell 'cd /data/local/tmp/pb && cp -r bin v/base'          # the build the app ships
```

## Running

```sh
scripts/proot-bench/bench.py --variants base,new --rounds 5    # speed, with 95% CIs
scripts/proot-bench/check.sh base new                          # guest behavior must not change
```

`bench.py` interleaves variants in random order every round and reports the median per case and
a bootstrap 95% confidence interval of the speedup; "faster"/"slower" means the whole interval is
on that side of 1.0. Output hashes must match between variants.

## Findings (Pixel 9a, 2026-11-04)

An intercepted syscall cost 150-600 us, ~100x a native one, because tracer and tracee ran on
different CPUs and each stop woke an idle one (455 us exit latency from the big cores' deep idle
state). Same-CPU round trips take ~30 us. patches/proot/0002 keeps syscall-heavy tracees on the
tracer's CPU and releases computing ones; results are in the commit that added it.

## Edge cases

`edge.c` (NDK clang -O2 -static -pthread, as /usr/local/bin/edge in the rootfs) and `edge-real.sh` (as
/usr/local/bin/edge-real; needs gcc, make, strace, gdb, xz, python, node) test what pinning can get wrong.
`edge-run.sh <variant> <command>` runs a guest command under a variant and prints proot's exit code:

```sh
adb shell 'cd /data/local/tmp/pb; sh edge-run.sh new edge pool 8'
```

Modes: pool, mixed, pong (threads that wake to compute or hand off a spin lock), late (is a computing thread
released), storm, killer, affin, threxec. Compare each against the build the app ships: pool and pong should not
be slower. Reading a file in /proc is intercepted, and so can pin the thread that reads it. Guests can't see
this; a mask printed after such a read shows the pin, not what the thread had before. threxec kills the guest
with signal 11 on the shipped proot too, and make's default fifo jobserver fails under proot, so edge-real uses
--jobserver-style=pipe.
