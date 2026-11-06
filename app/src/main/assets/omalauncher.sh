#!/bin/bash
# omalauncher: the phone's location, calendar, AirPods and battery as JSON, and
# notifications and Android's share sheet, for agents and scripts (see
# PhoneBridge.kt). Only what the launcher was already allowed; nothing here
# asks. Drops a request into the launcher's inbox and waits.
# /usr/local/bin/phone, its name until 0.20, runs this too.
set -u
inbox=/run/user/0/phone

usage() {
  cat >&2 <<USAGE
usage: omalauncher location       {lat, lon, accuracy (metres), time}, approximate
       omalauncher calendar [days]
                                  the events still to come from today on, 14 days
                                  unless given: [{id, eventId, title, start, end,
                                  allDay, calendar, color, location}]
       omalauncher airpods        the connected AirPods: batteries, mode, settings
       omalauncher airpods <control> [value]
                                  off, anc, transparency, adaptive, next;
                                  conversation, oneBud, earDetection [on|off];
                                  adaptiveLevel <0-100>
       omalauncher battery        {level, charging, status, plugged, saver,
                                  temp, voltage, health, technology, cycles}
       omalauncher notify <title> [text]
                                  a notification on the phone (tapped, it opens
                                  the home screen); prints nothing
       omalauncher share <file>...
                                  Android's share sheet for the files, for the
                                  user to pick an app; prints nothing
       omalauncher version        {version, versionCode, commands}

Times are Unix seconds. Prints JSON and exits 0, or says why not on stderr:
  1  nothing to say (no fix yet, no AirPods connected, Bluetooth off...)
  2  wrong arguments
  3  the launcher isn't allowed it: a widget showing it asks, or Android's
     settings for the app (notifications: the launcher's settings, permissions)
  4  the launcher isn't running or didn't answer
USAGE
  exit 2
}

[ $# -ge 1 ] || usage
case $1 in
  location | battery | version) [ $# -eq 1 ] || usage ;;
  --version) set -- version ;;
  calendar) [ $# -le 2 ] || usage ;;
  airpods) [ $# -le 3 ] || usage ;;
  notify) [ $# -ge 2 ] && [ $# -le 3 ] && [ -n "$2" ] || usage ;;
  share)
    [ $# -ge 2 ] || usage
    # The launcher doesn't know where this runs: paths go as absolute ones.
    args=(share)
    for f in "${@:2}"; do
      if [ ! -f "$f" ]; then
        echo "omalauncher: share: $f isn't a file" >&2
        exit 2
      fi
      case $f in
        /*) args+=("$f") ;;
        *) args+=("$PWD/$f") ;;
      esac
    done
    set -- "${args[@]}"
    ;;
  -h | --help | help) usage ;;
  *) usage ;;
esac
if [ ! -d "$inbox" ]; then
  echo "omalauncher: the launcher isn't listening; is it running?" >&2
  exit 4
fi

# Waiting in bash's own sleep, where it has it, rather than starting one 200
# times: each start is slow under proot.
enable -f /usr/lib/bash/sleep sleep 2>/dev/null

id="${EPOCHREALTIME/./}-$$"
# Each ended by a NUL: a notification's text can span lines.
printf '%s\0' "$@" >"$inbox/.$id.tmp"
mv "$inbox/.$id.tmp" "$inbox/$id.req"

# AirPods can take a while to link up.
for ((i = 0; i < 200; i++)); do
  if [ -f "$inbox/$id.done" ]; then
    reply=
    IFS= read -r -d '' reply <"$inbox/$id.done"
    reply=${reply%$'\n'}
    rm -f "$inbox/$id.done"
    status=${reply%%$'\n'*}
    case $status in
      ok)
        [ "$reply" = ok ] || printf '%s\n' "${reply#*$'\n'}"
        exit 0
        ;;
      usage:*)
        echo "omalauncher: ${reply#usage: }" >&2
        exit 2
        ;;
      denied:*)
        echo "omalauncher: ${reply#denied: }" >&2
        exit 3
        ;;
      *)
        echo "omalauncher: ${reply#error: }" >&2
        exit 1
        ;;
    esac
  fi
  sleep 0.1
done
rm -f "$inbox/$id.req"
echo "omalauncher: the launcher didn't answer; is it running?" >&2
exit 4
