#!/usr/bin/env python
"""Guest-side: prints what stat and lstat report for every path under the given roots and for a
set of awkward spellings, one line each. Run it under two variants and diff (statdiff.sh): the
fast lane library must not change a single field.   statdiff.py [root ...]"""
import os, sys

def show(path):
    out = [path]
    for fn in (os.stat, os.lstat):
        try:
            s = fn(path)
            if s.st_dev < 256:  # /proc and the like: inodes and times change from run to run
                out.append("%o %d %d" % (s.st_mode, s.st_uid, s.st_gid))
                continue
            out.append("%o %d %d %d %d %d %d %d %d" % (s.st_mode, s.st_uid, s.st_gid, s.st_size, s.st_nlink,
                                                    s.st_ino, s.st_dev, s.st_mtime_ns, s.st_blocks))
        except OSError as e:
            out.append("E%d" % e.errno)
    print(" | ".join(out))

roots = sys.argv[1:] or ["/usr/lib", "/usr/share/zoneinfo", "/etc", "/usr/bin", "/root", "/var"]
for root in roots:
    for d, dirs, files in os.walk(root):
        dirs.sort()
        show(d)
        for f in sorted(files):
            show(os.path.join(d, f))

os.makedirs("/tmp/sd/real/sub", exist_ok=True)
for name, target in [("rel", "real"), ("abs", "/tmp/sd/real"), ("up", "../sd/real"), ("out", "/proc"),
                     ("dangling", "nowhere"), ("absfile", "/usr/lib/libc.so.6"), ("loop", "loop")]:
    if not os.path.lexists("/usr/local/share/sd-" + name):
        os.symlink(target if name != "rel" else "man", "/usr/local/share/sd-" + name)
if not os.path.exists("/usr/local/share/sd-file"):
    open("/usr/local/share/sd-file", "w").close()
if not os.path.exists("/usr/local/share/sd-hard"):
    os.link("/usr/local/share/sd-file", "/usr/local/share/sd-hard")
L = "/usr/local/share/sd-"
for p in ["/", "//", "/.", "/..", "/usr", "/usr/", "//usr//lib//libc.so.6", "/usr/./lib/./libc.so.6",
          "/usr/lib/../lib/libc.so.6", "/usr/lib/libc.so.6/", "/usr/lib/libc.so.6/x", "/usr/lib/.", "/usr/lib/..",
          "/usr/nothing", "/usr/nothing/x", "/nothing/x/y", "/bin", "/bin/sh", "/lib/libc.so.6", "/sbin/../bin/sh",
          "/proc", "/proc/self", "/proc/self/stat", "/proc/nothing", "/dev", "/dev/null", "/sys", "/sys/class",
          "/tmp", "/tmp/sd", "/tmp/sd/real/sub", "/root", "/root/repo/.git/HEAD", "/etc/mtab", "/etc/resolv.conf",
          L + "rel", L + "rel/man1", L + "abs", L + "abs/sub", L + "up/sub", L + "out", L + "out/self/stat",
          L + "out/nothing", L + "dangling", L + "dangling/x", L + "absfile", L + "absfile/x", L + "loop",
          L + "loop/x", L + "file", L + "hard", L + "file/x", "/usr/share/zoneinfo/Europe/Berlin",
          "/usr/share/zoneinfo/UTC", "/usr/local/share/.l2s.sd-file0001", "/usr/lib/" + "a" * 200,
          "/usr/lib/" + "a" * 201, "/usr/lib/" + "a" * 240, "/usr/lib/" + "a" * 255, "/usr/lib/" + "a" * 256, "/" + "a/" * 2100, "", "usr", ".", "..", "repo", "./repo/.git", "../usr/lib"]:
    show(p)
