#!/bin/bash
# omalauncher: the phone's location, calendar and AirPods as JSON, and
# notifications, for agents and scripts (see PhoneBridge.kt). Only what the launcher was already allowed;
# nothing here asks. Drops a request into the launcher's inbox and waits.
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
       omalauncher notify <title> [text]
                                  a notification on the phone (tapped, it opens
                                  the home screen); prints nothing
       omalauncher airpods        the connected AirPods: batteries, mode, settings
       omalauncher airpods <control> [value]
                                  off, anc, transparency, adaptive, next;
                                  conversation, oneBud, earDetection [on|off];
                                  adaptiveLevel <0-100>

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
  location) [ $# -eq 1 ] || usage ;;
  calendar) [ $# -le 2 ] || usage ;;
  airpods) [ $# -le 3 ] || usage ;;
  notify) [ $# -ge 2 ] && [ $# -le 3 ] && [ -n "$2" ] || usage ;;
  -h | --help | help) usage ;;
  *) usage ;;
esac
if [ ! -d "$inbox" ]; then
  echo "omalauncher: the launcher isn't listening; is it running?" >&2
  exit 4
fi

id="$(date +%s%N)-$$"
# Each ended by a NUL: a notification's text can span lines.
printf '%s\0' "$@" >"$inbox/.$id.tmp"
mv "$inbox/.$id.tmp" "$inbox/$id.req"

# AirPods can take a while to link up.
for ((i = 0; i < 200; i++)); do
  if [ -f "$inbox/$id.done" ]; then
    reply=$(cat "$inbox/$id.done")
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
