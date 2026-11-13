#!/usr/bin/env python3
"""A/B benchmark of proot variants on a device (see device.sh).

bench.py --variants base,new [--cases stat,find,...] [--rounds 9] [--out results.csv]

Each round runs every case under every variant in a fresh random order, so thermal drift and
background noise hit all variants alike. The report gives each variant's median per case and,
against the first variant, the speedup with a bootstrap 95% confidence interval; a speedup counts
as proven only when the whole interval clears 1.0. Outputs (hashes) must match across variants,
or the case is marked as a correctness failure.
"""
import argparse, csv, random, statistics, subprocess, sys

ALL = ["getpid", "stat", "stat-l2s", "stat-deep", "stat-link", "stat-miss", "stat-nodir", "stat-bind", "stat-rel", "stat-up", "lstat", "statx", "access", "fstat", "open", "open-plain", "open-miss", "getcwd", "readlink", "exec", "stat-par4", "stat-par8",
       "find", "ls-lR", "pacman-Qk", "sh-loop", "tar", "find-par4",
       "node", "npm", "git-status", "python", "cpu-par4"]
MACRO = {"find", "ls-lR", "pacman-Qk", "sh-loop", "tar", "find-par4", "node", "npm", "git-status",
         "python", "cpu-par4"}


def run(variant, case):
    out = subprocess.run(["adb", "shell", f"sh /data/local/tmp/pb/device.sh {variant} {case}"],
                         capture_output=True, text=True)
    parts = out.stdout.split()
    if out.returncode or len(parts) != 3:
        sys.exit(f"{variant}/{case} failed: {out.stdout} {out.stderr}")
    ms, ns, h = parts
    # The pbench cases report their own per-op time, free of proot's startup.
    return (float(ns) if ns != "-" else float(ms)), h


def ci_ratio(base, new, n=4000):
    rs = sorted(statistics.median(random.choices(base, k=len(base))) /
                statistics.median(random.choices(new, k=len(new))) for _ in range(n))
    return rs[int(n * .025)], rs[int(n * .975)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--variants", required=True)
    ap.add_argument("--cases", default=",".join(ALL))
    ap.add_argument("--rounds", type=int, default=9)
    ap.add_argument("--warmup", type=int, default=1)
    ap.add_argument("--out")
    a = ap.parse_args()
    variants, cases = a.variants.split(","), a.cases.split(",")
    data = {(v, c): [] for v in variants for c in cases}
    hashes = {(v, c): set() for v in variants for c in cases}
    rows = []
    for r in range(a.warmup + a.rounds):
        jobs = [(v, c) for v in variants for c in cases]
        random.shuffle(jobs)
        for v, c in jobs:
            val, h = run(v, c)
            if r >= a.warmup:
                data[v, c].append(val)
                hashes[v, c].add(h)
                rows.append((r, v, c, val, h))
        print(f"round {r + 1}/{a.warmup + a.rounds} done", file=sys.stderr)
    if a.out:
        with open(a.out, "w") as f:
            csv.writer(f).writerows([("round", "variant", "case", "value", "hash")] + rows)

    base = variants[0]
    print(f"\n{'case':<11} {'unit':<6}" + "".join(f"{v:>14}" for v in variants) +
          "".join(f"  {v + ' vs ' + base:>28}" for v in variants[1:]))
    for c in cases:
        unit = "ms" if c in MACRO else "ns/op"
        line = f"{c:<11} {unit:<6}" + "".join(f"{statistics.median(data[v, c]):>14.0f}" for v in variants)
        for v in variants[1:]:
            sp = statistics.median(data[base, c]) / statistics.median(data[v, c])
            lo, hi = ci_ratio(data[base, c], data[v, c])
            verdict = "faster" if lo > 1 else "slower" if hi < 1 else "same"
            line += f"  {sp:5.2f}x [{lo:4.2f}-{hi:4.2f}] {verdict:>6}"
        same = len(set().union(*(hashes[v, c] for v in variants))) == 1
        print(line + ("" if same else "  OUTPUT DIFFERS"))


main()
