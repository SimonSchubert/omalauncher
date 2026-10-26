#!/bin/bash
# omadroid: sets a state of a running home-screen widget, or refreshes it,
# from scripts, cron jobs and hooks (see WidgetEngine.kt receive()). Drops a
# request into the widgets' .inbox and waits for the launcher's answer.
set -u
dir="$HOME/.config/omadroid/widgets"

usage() {
  cat >&2 <<USAGE
usage: owl-send <widget.owl> set <state> <value>
       owl-send <widget.owl> set <state> -      (the value from stdin)
       owl-send <widget.owl> refresh

set changes a state of the running widget, as a tap on it could. The value
is JSON when it parses as JSON (42, true, "text", [1, 2], {"a": 1}), else
plain text. refresh re-runs the widget's sources now. Exits 1 if the widget
isn't running or has no such state.
USAGE
  exit 2
}

[ $# -ge 2 ] || usage
name=$(basename -- "$1")
[[ $name == *.owl ]] || name="$name.owl"
cmd=$2
case $cmd in
  set)
    [ $# -eq 4 ] || usage
    state=$3
    value=$4
    [ "$value" = - ] && value=$(cat)
    ;;
  refresh) [ $# -eq 2 ] || usage ;;
  *) usage ;;
esac
if [ ! -f "$dir/$name" ]; then
  echo "$name: no such widget in $dir" >&2
  exit 1
fi

inbox="$dir/.inbox"
mkdir -p "$inbox"
id="$(date +%s%N)-$$"
{
  printf '%s\n%s' "$name" "$cmd"
  [ "$cmd" = set ] && printf '\n%s\n%s' "$state" "$value"
} >"$inbox/.$id.tmp"
mv "$inbox/.$id.tmp" "$inbox/$id.req"

for ((i = 0; i < 50; i++)); do
  if [ -f "$inbox/$id.done" ]; then
    reply=$(cat "$inbox/$id.done")
    rm -f "$inbox/$id.done"
    [ "$reply" = ok ] && exit 0
    echo "$name: ${reply#error: }" >&2
    exit 1
  fi
  sleep 0.1
done
rm -f "$inbox/$id.req"
echo "$name: the launcher didn't answer; is it running?" >&2
exit 1
