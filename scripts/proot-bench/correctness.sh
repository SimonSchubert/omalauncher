#!/bin/sh
# Guest-side behavior checks; run under two proot variants and diff the output (see check.sh).
# Each line is "name: result"; nothing here may depend on timing or on the proot build.
cd /root
t() { printf '%s: ' "$1"; shift; ( "$@" ) 2>&1 | tr '\n' ' '; echo; }

t nproc nproc
t nproc-taskset taskset -c 2 nproc
t nproc-after-busy sh -c '/usr/local/bin/pbench stat 2000 /usr/lib/libc.so.6 >/dev/null; nproc'
t python-affinity python -c 'import os; print(len(os.sched_getaffinity(0)))'
t node-parallelism node -e 'console.log(require("os").availableParallelism())'
t taskset-sticks sh -c 'taskset -c 1,2 sh -c "for i in \$(seq 50); do /usr/bin/true; done; taskset -pc \$\$ | sed \"s/.*: //\""'
t python-setaffinity python -c 'import os; os.sched_setaffinity(0, {3}); [os.stat("/") for _ in range(500)]; print(sorted(os.sched_getaffinity(0)))'
t uid id -u
t whoami whoami
t files sh -c 'rm -rf /tmp/c && mkdir -p /tmp/c/a/b && echo hi > /tmp/c/a/b/f && ln /tmp/c/a/b/f /tmp/c/hard && ln -s a/b/f /tmp/c/soft && mv /tmp/c/a /tmp/c/z && cat /tmp/c/hard /tmp/c/z/b/f && readlink /tmp/c/soft && stat -c "%h %s" /tmp/c/hard && rm -rf /tmp/c && echo done'
t cwd sh -c 'cd /usr/share/zoneinfo/Europe && pwd && cd .. && pwd -P'
t proc-self sh -c 'readlink /proc/self/exe; readlink /proc/self/cwd'
t pipes sh -c 'seq 100000 | sort -n | tail -1'
t jobs sh -c '(for i in 8 7 6 5 4 3 2 1; do (sleep 0.$i; echo $i) & done; wait) | sort -n'
t timeout sh -c 'timeout 0.3 sleep 5; echo $?'
t signals sh -c 'sleep 5 & p=$!; kill -TERM $p; wait $p; echo $?'
t threads python -c 'import threading; r=[]; ts=[threading.Thread(target=lambda i=i: r.append(sum(range(200000))+i)) for i in range(8)]; [t.start() for t in ts]; [t.join() for t in ts]; print(len(r), min(r))'
t parallel-cpu sh -c 'for i in 1 2 3 4; do dd if=/dev/zero bs=1M count=50 2>/dev/null | md5sum & done; wait'
t many-procs sh -c 'n=0; for i in $(seq 300); do n=$((n + $(echo 1))); done; echo $n'
t git sh -c 'cd /root/repo && git status --porcelain | wc -l && git log --oneline | wc -l'
t pacman sh -c 'pacman -Q | wc -l'
t node node -e 'const fs=require("fs"); console.log(fs.readdirSync("/usr/lib/node_modules/npm").length)'
t npm npm --version
