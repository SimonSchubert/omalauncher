#!/bin/sh
# real parallel workloads; prints "T name ms rc" and sanity output
t(){ n=$1; shift; s=$(date +%s%N); o=$("$@" 2>&1 </dev/null); c=$?; e=$(date +%s%N); echo "T $n ms=$(( (e-s)/1000000 ))"; echo "$n rc=$c $(echo "$o" | tail -1 | cut -c1-60)"; }
D=/tmp/edge-real; rm -rf $D; mkdir -p $D/src; cd $D/src
for i in $(seq 48); do cat > f$i.c <<X
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
struct s$i { int a[100]; double d; };
double fn$i(int n){ struct s$i v; memset(&v,0,sizeof v); for(int k=0;k<n;k++){ v.d+=sqrt(k)*k; v.a[k%100]+=k; } return v.d; }
X
done
cat > Makefile <<'X'
SRC := $(wildcard f*.c)
OBJ := $(SRC:.c=.o)
all: $(OBJ)
%.o: %.c
	gcc -O2 -c $< -o $@
X
t make-j8 sh -c 'make -j8 --jobserver-style=pipe >/dev/null && ls *.o | wc -l'
cat > $D/py.py <<'X'
import os, hashlib, time
ps=[]
for i in range(8):
    p=os.fork()
    if p==0:
        for k in range(300): os.stat("/usr/lib/libc.so.6")
        time.sleep(0.3)
        hashlib.sha256(b"x"*160000000).hexdigest()
        os._exit(0)
    ps.append(p)
print(sum(os.waitpid(p,0)[1]==0 for p in ps))
X
t python-pool python $D/py.py
cat > $D/n.js <<'X'
const {Worker,isMainThread,parentPort}=require('worker_threads');const fs=require('fs');
if(isMainThread){let d=0;for(let i=0;i<8;i++){const w=new Worker(__filename);w.on('message',()=>{if(++d==8){console.log('done',d);process.exit(0)}})}}
else{for(let i=0;i<300;i++)fs.statSync('/usr/lib/libc.so.6');setTimeout(()=>{let x=0;for(let i=0;i<4e8;i++)x+=i%7;parentPort.postMessage(x)},300)}
X
t node-workers node $D/n.js
t xz-T8 sh -c 'dd if=/dev/urandom bs=1M count=64 2>/dev/null | xz -T8 -3 | wc -c | cut -c1'
t sort-par sh -c 'seq 2000000 | sort -R --parallel=8 | wc -l'
t strace sh -c "strace -f -o $D/s sh -c 'ls / >/dev/null; true'; echo strace=\$?; grep -c execve $D/s | cut -c1"
cat > $D/g.c <<X
int main(){volatile int a=1;return a-1;}
X
gcc -g -o $D/g $D/g.c
t gdb sh -c "gdb -q -batch -ex 'break main' -ex run -ex bt -ex kill $D/g 2>&1 | grep -c '#0'"
rm -rf $D
