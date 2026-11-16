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

## The fast lane

patches/proot/0003 and patches/proot/fastpath/fastpath.c: a library preloaded into guest programs
answers libc calls itself, without stopping in proot, for paths that stay inside the rootfs:
absolute ones, ones relative to the working directory or a dirfd, through symlinks and "..".
A variant uses it when its `guestenv` file holds `LD_PRELOAD=/usr/local/lib/libfastpath.so`.

In the app: scripts/fetch-proot.sh builds the library into jniLibs next to proot, `Proot.managed()`
copies it to /usr/local/lib/libfastpath.so in the guest on every start, and `Proot.command` sets
LD_PRELOAD once it is there. The "fast system calls" switch on the Linux screen turns it off along
with the CPU pinning (no LD_PRELOAD, PROOT_FASTPATH=off).

The calls: stat, lstat, fstatat, statx (also as `syscall(SYS_statx, ...)`, which is how node
calls it), access and faccessat without flags, readlink and readlinkat, open and openat of a
file or directory for reading, fstat, close of a file or directory of the rootfs'
filesystem, realpath (compilers call it for every file, and libc's own asks proot about every
component), getcwd, and getxattr, listxattr and their l forms (`ls -l` asks llistxattr per file). Everything else, and every case the library is not sure about, is made as an
ordinary syscall. For open and close that is libc's own function (found with dlsym), because
those two are cancellation points and what is left to proot may block.

Not reached: calls libc makes inside itself (fopen, opendir and closedir, the fstat in
fdopendir), ld.so loading libraries, static programs, and `__open_2` and its kin, which a
program built with _FORTIFY_SOURCE calls for an open whose flags are not a constant.

```sh
scripts/proot-bench/variant.sh fp path/to/patched/proot/tree     # proot with patch 0003
scripts/proot-bench/fastpath.sh                                  # build + push the library
adb shell 'echo LD_PRELOAD=/usr/local/lib/libfastpath.so > /data/local/tmp/pb/v/fp/guestenv'
scripts/proot-bench/statdiff.sh main fp      # stat and lstat of ~36,000 paths must not differ
```

The pbench cases need `pbench-dyn` in the guest (`gcc -O2 pbench.c`, pacman -S gcc): the static
pbench ignores LD_PRELOAD.

What the library remembers goes stale: directories without a symlink on the way when one of them
becomes a symlink, the fake ids, and the working directory, which only proot knows (it emulates
chdir, the kernel's never changes). proot counts such changes in the file `<rootfs>.fastpath` (or
`PROOT_FASTPATH_STATE`), which every guest process maps. So every proot on one rootfs has to be
a patched one: an unpatched proot changes the tree without counting.

### The differential fuzzer

`fuzz.c` asks each question twice in one guest process, through libc (the library) and as a bare
syscall (proot), and reports any difference. A question is one of the calls above about a path
(for open: is it the same file, opened the same way, and does close close it). It needs gcc in the guest and a variant whose
`guestenv` preloads `libfastpath-stats.so` (built by fastpath.sh, the library with counters), so
that the summary tells how many questions the library answered itself:

```sh
adb shell 'cd /data/local/tmp/pb && cp -r v/fp v/fps &&
  echo LD_PRELOAD=/usr/local/lib/libfastpath-stats.so > v/fps/guestenv'
scripts/proot-bench/fuzz.sh fps run 1 100 30000   # seeds 1..100: a tree, questions, changes
scripts/proot-bench/fuzz.sh fps ids 1 10 20000    # after setresgid/setresuid, in a chroot, below a mount
scripts/proot-bench/fuzz.sh fps threads 1 2 3000  # four threads ask while the tree changes
scripts/proot-bench/fuzz-other.sh fps fp          # the tree changes under a second proot
scripts/proot-bench/fuzz-other.sh fps main        # ... an unpatched one: this one must differ
```

The fuzzer asks the two sides one after the other, so it cannot see what only shows while no
syscall reaches proot. For that, sweep a real rootfs in one long-lived process with the library
and again without, and compare the two logs (every path, asked every way, several questions in a
row about the same file).

A seed of 30,000 questions takes a few seconds. The tree lives in /root/fz of the bench rootfs.
Its symlinks point all over the rootfs, so every change the fuzzer makes is checked to stay in
the tree first (`inside()`); keep that when adding kinds of change.

What proot does that the kernel doesn't, found this way (the library hands all of these to proot):

- As the guest's root, proot gives a directory or file its owner's missing permissions while it
  walks through it, for the length of the syscall. A path that ends on such a directory after ".."
  or ".", or on such a file through a symlink, shows the changed mode (and a new ctime each time).
- A component proot can't lstat is ENOENT to it, not EACCES, and it never asks the kernel about
  what ".." drops.
- A file or directory whose name starts with `.l2s.` is taken for the file behind a faked hard
  link: its link count is the last four characters of the name.
- Names of 255 bytes can be made in the guest but not removed again (rm says ENAMETOOLONG).
- statx is not among the calls whose last component proot leaves alone: a file whose owner lacks
  read or write permission shows up with both (`ls -l` and `stat` print 644 for a 444 file, while
  python's os.stat says 444), and its ctime changes on every statx.
- readlink of a symlink into the rootfs' host path, which proot shows as the guest path, returns
  one more than the length when the buffer has room for exactly the path and a NUL.
- The kernel turns down bad arguments (a readlink size of 0, an access mode above 7, a reserved
  statx mask bit) before it looks at the path; proot has walked the directories by then, so a
  missing directory wins over the bad argument. The library leaves such calls to proot.
- The permissions proot forces (first point) stay until it next hears from that tracee, which
  under seccomp is its next intercepted syscall. Without the library that is the very next path
  call; with it, an fstat right after opening a 444 file showed 644, and so did every stat until
  something reached proot (found by sweeping a real rootfs, 2026-11-12: the fuzzer asked proot
  right after the library every time, which restored the mode). Patch 0003 now stops such a
  syscall at its exit too and restores there, and fuzz.c's open question uses the library's fstat.
- fstat of a descriptor that is open on a symlink itself (O_PATH | O_NOFOLLOW) fails with EPERM
  under proot. The library leaves it to proot.
- libc's realpath is not proot's walk: it wants a directory where a symlink's target ends in a
  slash or a dot, and it takes the working directory by its name, without following a symlink
  that has taken the place of a directory in it since. The library answers realpath only when
  neither is in play. For a path that is missing, libc leaves the name of what is missing in the
  caller's buffer, but nothing when the working directory itself is gone.
- proot renames its idea of the working directory along with a directory above it, but only in
  the process that made the rename, and only for rename and renameat, not renameat2.

Not the fast lane's: `strace -f` in the guest hangs about once in 30 runs (the strace step of
edge-real), with the shipped proot just as often.

### Results (Pixel 10, 2026-11-12, bench.py main,fp, 5 rounds)

Per call, in microseconds, shipped proot → fast lane: stat 34 → 3.6, statx 39 → 6.3, access
26 → 6.2, fstat 26 → 6.1, readlink 28 → 7.3, open + close of a plain file 38 → 11, open of a
missing file 29 → 3.2. Workloads: pacman -Qk 4.4x, ls -lR 1.8x, find 1.55x, git status 1.30x,
npm --version 1.26x, python imports 1.07x; node -e 0, tar and the sh loop the same.

Slower: exec by 9% (about 130 us per exec: ld.so loads one more library through proot), and
stat or open of a faked hard link by 4% (the library looks, then hands it to proot). bind paths,
getcwd and anything that writes go through proot as before.

More tools from the measurements behind the design:

- `nulltrace.c` (NDK clang -O2 -static, run as the shell user): what a trace stop costs with a
  tracer that does nothing, the floor under proot's own work.
- `mix.sh` + `libccount.c` (guest, need strace and gcc) and `mix.py` (host): per workload, which
  syscalls proot traps and how many of them a preloaded library sees as libc calls.

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
