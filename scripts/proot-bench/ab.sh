#!/bin/bash
# Agent startup benchmark, run in an app terminal:
#   curl -s localhost:8765/ab.sh | bash -s <label> [runs]
# Starts each agent's TUI in this terminal, stamps when its prompt is drawn, quits it.
# Sends nothing to the agents: no tokens are used.
L=${1:-run}
N=${2:-6}
D=$(mktemp -d)
R=$D/results
declare -A MARK=([claude]='shortcuts' [grok]='approv')

ready_ms() { # agent log t0
  local agent=$1 log=$2 t0=$3
  for _ in $(seq 300); do
    if sed 's/\x1b\[[0-9;?]*[a-zA-Z]//g' "$log" 2>/dev/null | grep -q "${MARK[$agent]}"; then
      echo $(( ($(date +%s%N) - t0) / 1000000 )); return
    fi
    sleep 0.1
  done
  echo timeout
}

{
  echo "label $L"
  for agent in claude grok; do
    command -v $agent >/dev/null || { echo "$agent missing"; continue; }
    echo "${agent}_version_ms $( s=$(date +%s%N); $agent --version </dev/null >/dev/null 2>&1; echo $(( ($(date +%s%N) - s) / 1000000 )) )"
  done
} > $R

for agent in claude grok; do
  command -v $agent >/dev/null || continue
  times=()
  for i in $(seq $N); do
    log=$D/$agent.$i.log
    t0=$(date +%s%N)
    (
      ms=$(ready_ms $agent $log $t0)
      echo "$ms" > $D/$agent.$i.ms
      sleep 1
      pkill -TERM -P $(pgrep -n -f "script -qfc $agent") 2>/dev/null
      sleep 2
      pkill -KILL -f "script -qfc $agent" 2>/dev/null
    ) &
    script -qfc "$agent" "$log" </dev/tty
    wait
    stty sane </dev/tty; printf '\033[?1049l\033[?25h\033[0m'; clear
    times+=("$(cat $D/$agent.$i.ms)")
  done
  echo "${agent}_ready_ms ${times[*]}" >> $R
  # the first run warms caches: median of the rest
  echo "${agent}_ready_median_ms $(printf '%s\n' "${times[@]:1}" | grep -v timeout | sort -n | awk '{a[NR]=$1} END {print a[int((NR+1)/2)]}')" >> $R
  sed 's/\x1b\[[0-9;?]*[a-zA-Z]//g' $D/$agent.1.log | tr -cd '[:print:]\n' | tail -c 600 > $D/$agent.sample
done

cat $R
cat $R $D/*.sample 2>/dev/null | curl -s -X POST --data-binary @- localhost:8765/agents-$L >/dev/null && echo "sent to the Mac"
rm -rf $D
