#!/bin/sh
# Guest-side: the syscall mix of real workloads, and how much of it goes through libc's path
# functions (libccount.so). Run once per workload: mix.sh <name>; results in /tmp/mix/<name>.*
# Needs strace and gcc in the guest.
mkdir -p /tmp/mix; cd /root
[ -f /usr/local/lib/libccount.so ] || gcc -O2 -shared -fPIC /usr/local/src/libccount.c -o /usr/local/lib/libccount.so || exit 1
case $1 in
  find)       CMD='find /usr > /dev/null' ;;
  ls-lR)      CMD='ls -lR /usr/lib > /dev/null' ;;
  git-status) CMD='cd /root/repo && git status --porcelain > /dev/null' ;;
  node)       CMD='node -e 0' ;;
  npm)        CMD='npm --version > /dev/null' ;;
  python)     CMD='python -c "import json, email.parser, asyncio, http.client, argparse"' ;;
  pacman-Qk)  CMD='pacman -Qk > /dev/null 2>&1' ;;
  claude)     CMD='claude --version > /dev/null' ;;
  grok)       CMD='grok --version > /dev/null' ;;
  sh-loop)    CMD='for i in $(seq 50); do /usr/bin/true; done' ;;
  gcc)        CMD='echo "int main(){return 0;}" > /tmp/mix/t.c && gcc -O2 /tmp/mix/t.c -o /tmp/mix/t' ;;
  *) echo "unknown $1"; exit 2 ;;
esac
rm -f /tmp/mix/$1.*
strace -f -c -o /tmp/mix/$1.strace sh -c "$CMD"
LIBCCOUNT_OUT=/tmp/mix/$1.libc LD_PRELOAD=/usr/local/lib/libccount.so sh -c "$CMD"
echo "$1 done"
