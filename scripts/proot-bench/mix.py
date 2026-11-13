#!/usr/bin/env python3
"""Reads mix.sh's results: per workload, how many syscalls proot traps, which, and how many of
them a preloaded library would see as libc calls.   mix.py <dir with *.strace, *.libc> <proot src/>"""
import collections, pathlib, re, sys

d, src = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
trapped = set()
for f in ["syscall/seccomp.c", "extension/fake_id0/fake_id0.c", "extension/link2symlink/link2symlink.c",
          "extension/ashmem_memfd/ashmem_memfd.c", "extension/mountinfo/mountinfo.c"]:
    for block in re.findall(r"FilteredSysnum \w+\[\] = \{(.*?)\};", (src / f).read_text(), re.S):
        trapped |= set(re.findall(r"PR_(\w+)", block))
trapped -= {"void"}
# libc function -> the syscall it ends in on arm64
LIBC = {"stat": "newfstatat", "lstat": "newfstatat", "fstatat": "newfstatat", "fstat": "fstat",
        "statx": "statx", "open": "openat", "openat": "openat", "fopen": "openat", "opendir": "openat",
        "access": "faccessat", "faccessat": "faccessat", "readlink": "readlinkat",
        "readlinkat": "readlinkat", "getcwd": "getcwd", "close": "close", "ioctl": "ioctl",
        "geteuid": "geteuid", "getuid": "getuid", "getegid": "getegid", "getgid": "getgid",
        "chdir": "chdir", "execve": "execve"}
PATHS = ["newfstatat", "statx", "openat", "faccessat", "faccessat2", "readlinkat"]
for st in sorted(d.glob("*.strace")):
    sysc = collections.Counter()
    for line in st.read_text().splitlines():
        m = re.match(r"\s*[\d.]+\s+[\d.]+\s+\d+\s+(\d+)\s+(?:\d+\s+)?(\w+)$", line)
        if m and m.group(2) != "total":
            sysc[m.group(2)] += int(m.group(1))
    libc = collections.Counter()
    lf = st.with_suffix(".libc")
    for line in lf.read_text().splitlines() if lf.exists() else []:
        n, c = line.split()
        libc[LIBC[n]] += int(c) if n != "realpath" else 0
    total, trap = sum(sysc.values()), sum(c for s, c in sysc.items() if s in trapped)
    path = sum(sysc[s] for s in PATHS)
    via = sum(min(libc[s], sysc[s]) for s in PATHS)
    print(f"\n{st.stem}: {total} syscalls, {trap} trapped ({100 * trap // max(total, 1)}%), "
          f"path calls {path} ({100 * path // max(trap, 1)}% of trapped), "
          f"of those through libc symbols {via} ({100 * via // max(path, 1)}%)")
    for s, c in sysc.most_common():
        if s in trapped and c * 50 >= trap:
            print(f"  {s:<14}{c:>8} {100 * c // trap:>3}%   libc calls {libc.get(s, 0)}")
