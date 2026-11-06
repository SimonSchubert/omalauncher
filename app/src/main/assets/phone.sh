#!/bin/bash
# omalauncher: the phone's location, calendar and AirPods as JSON, for agents
# and scripts (see PhoneBridge.kt). Only what the launcher was already allowed;
# nothing here asks. `phone media` puts what a Linux player plays in Android's
# media controls (LinuxMedia.kt). Drops a request into the launcher's inbox and
# waits.
set -u
inbox=/run/user/0/phone

usage() {
  cat >&2 <<USAGE
usage: phone location             {lat, lon, accuracy (metres), time}, approximate
       phone calendar [days]      the events still to come from today on, 14 days
                                  unless given: [{id, eventId, title, start, end,
                                  allDay, calendar, color, location}]
       phone airpods              the connected AirPods: batteries, mode, settings
       phone airpods <control> [value]
                                  off, anc, transparency, adaptive, next;
                                  conversation, oneBud, earDetection [on|off];
                                  adaptiveLevel <0-100>
       phone media set '<json>'   show what a Linux player plays in Android's
                                  media controls (lock screen, notification,
                                  car over Bluetooth, headset buttons):
                                  {title, artist, album, art (https URL or file),
                                  duration, position (seconds), playing, speed,
                                  command, actions}; without a title it changes
                                  only the fields given. A press runs
                                  "<command> play|pause|next|previous|stop" or
                                  "<command> seek <seconds>" in Linux; actions
                                  limits them (default all but stop)
       phone media clear          take it away again
       phone media                what's set

Times are Unix seconds. Prints JSON; exits 1 with a reason on stderr when the
launcher isn't allowed it (a widget showing it asks, or Android's settings),
or has nothing to say.
USAGE
  exit 2
}

[ $# -ge 1 ] || usage
case $1 in
  location) [ $# -eq 1 ] || usage ;;
  calendar) [ $# -le 2 ] || usage ;;
  airpods) [ $# -le 3 ] || usage ;;
  media)
    [ $# -le 3 ] || usage
    # One request line per argument: a JSON object written over several lines goes on one.
    if [ $# -eq 3 ]; then set -- "$1" "$2" "${3//$'\n'/ }"; fi
    ;;
  -h | --help | help) usage ;;
  *) usage ;;
esac
if [ ! -d "$inbox" ]; then
  echo "phone: the launcher isn't listening; is it running?" >&2
  exit 1
fi

id="$(date +%s%N)-$$"
printf '%s\n' "$@" >"$inbox/.$id.tmp"
mv "$inbox/.$id.tmp" "$inbox/$id.req"

# AirPods can take a while to link up.
for ((i = 0; i < 200; i++)); do
  if [ -f "$inbox/$id.done" ]; then
    reply=$(cat "$inbox/$id.done")
    rm -f "$inbox/$id.done"
    if [ "${reply%%$'\n'*}" = ok ]; then
      printf '%s\n' "${reply#*$'\n'}"
      exit 0
    fi
    echo "phone: ${reply#error: }" >&2
    exit 1
  fi
  sleep 0.1
done
rm -f "$inbox/$id.req"
echo "phone: the launcher didn't answer; is it running?" >&2
exit 1
