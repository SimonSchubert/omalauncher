#!/bin/sh
# In-app proot benchmark: curl -s localhost:8765/b.sh | sh -s <label>
L=${1:-run}
D=$(mktemp -d)
curl -s localhost:8765/pbench -o $D/pbench && chmod +x $D/pbench
ms() { s=$(date +%s%N); "$@" </dev/null >/dev/null 2>&1; e=$(date +%s%N); echo $(( (e - s) / 1000000 )); }
med() { sort -n | awk '{a[NR]=$1} END {print a[int((NR+1)/2)]}'; }
R=$D/results
{
  echo "label $L"
  echo "nproc $(nproc)"
  for c in "stat 3000 /usr/lib/libc.so.6" "getcwd 3000" "exec 50 /usr/bin/true"; do
    echo "pbench-${c%% *}_ns $(for i in 1 2 3 4 5; do $D/pbench $c; done | med)"
  done
  echo "find_usr_ms $(for i in 1 2 3 4 5; do ms sh -c 'find /usr | wc -l'; done | med)"
  echo "ls_lR_ms $(for i in 1 2 3; do ms sh -c 'ls -lR /usr/lib | wc -l'; done | med)"
  echo "sh_loop_ms $(for i in 1 2 3 4 5; do ms sh -c 'for i in $(seq 200); do /usr/bin/true; done'; done | med)"
  command -v node >/dev/null && echo "node_ms $(for i in 1 2 3 4 5; do ms node -e 0; done | med)"
  command -v npm >/dev/null && echo "npm_ms $(for i in 1 2 3; do ms npm --version; done | med)"
  command -v python >/dev/null && echo "python_ms $(for i in 1 2 3 4 5; do ms python -c 'import json, email.parser, asyncio'; done | med)"
  command -v claude >/dev/null && echo "claude_version_ms $(for i in 1 2 3; do ms claude --version; done | med)"
  echo "pacman_Q_ms $(for i in 1 2 3; do ms pacman -Q; done | med)"
  echo "cpu_par4_ms $(for i in 1 2 3; do ms sh -c 'for i in 1 2 3 4; do dd if=/dev/zero bs=1M count=200 2>/dev/null | md5sum & done; wait'; done | med)"
} | tee $R
curl -s -X POST --data-binary @$R localhost:8765/results-$L >/dev/null && echo "sent to the Mac"
rm -rf $D
