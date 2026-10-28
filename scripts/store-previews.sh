#!/usr/bin/env bash
# Renders every widget and app in the widget store in every bundled Omarchy theme, for the
# store's per-theme previews: widgets/<author>/<name>/previews/<theme>.webp (apps/... for an
# app, whose picture is its view at the phone's size, faces/... for a face, the whole page), plus preview.png in the default theme
# for launchers that don't know about themes.
#
# An app with a screens.txt beside it is drawn on several screens: one line per screen, the
# states to set first (`view = "watch"; openId = ""`), each starting from the screen before;
# `wait = 60` there pauses first, for an API that allows only so many calls a minute.
# The first is its preview as above; the rest go to apps/<author>/<name>/screens/<n>/<theme>.webp.
# A face's other pages (page-2.owl, ...) go the same way, to faces/<author>/<name>/screens/<n>/<theme>.webp.
#
# Needs a debug build on an emulator (adb root) with Linux installed, so widgets
# that run commands show real data, and the packages they require (curl, jq,
# python, sqlite, github-cli). Widgets that need an account show demo data instead; see
# store-previews-demo.sh. The launcher renders each tile off screen
# (WidgetEngine.previewThemes) and takes every theme's colors while it does.
#
#   scripts/store-previews.sh [store repo, default ../omarchy-widgets] [widget, face or app name, or author/name...]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
STORE="${1:-$HERE/../../omarchy-widgets}"
shift || true
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
DEFAULT_THEME=tokyo-night
APP=/data/data/dev.omalauncher/files/widgets
STATE=/data/data/dev.omalauncher/files/widget-state
WORK="$(mktemp -d)"

adb root >/dev/null
adb wait-for-device
owner=$(adb shell stat -c %u:%g "$APP")
label=$(adb shell ls -Zd "$APP" | cut -d' ' -f1)

# Writes under a hidden name the launcher ignores, then renames it, so it sees the file once, complete.
put() {
  adb push "$1" /data/local/tmp/store-preview.owl >/dev/null
  adb shell "cp /data/local/tmp/store-preview.owl $APP/.$2 && chown $owner $APP/.$2 && chmod 600 $APP/.$2 && chcon $label $APP/.$2 && mv $APP/.$2 $APP/$2"
}

# Polls a condition on the device for up to $1 seconds.
wait_for() {
  local secs=$1
  shift
  for _ in $(seq "$secs"); do
    adb shell "$@" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

# The default preview as a 256-color PNG that keeps the tile's translucency (dithered when the
# tile is opaque, so photos don't band), scaled down if
# it's still over the store's 300 KB. Needs Pillow (python3 -m pip install pillow).
small_png() {
  python3 - "$1" "$2" <<'PY'
import os
import sys
from PIL import Image
src, out = sys.argv[1:]
im = Image.open(src).convert("RGBA")
scale = 1.0
while True:
    s = im if scale == 1 else im.resize((round(im.width * scale), round(im.height * scale)), Image.LANCZOS)
    if s.getextrema()[3][0] == 255:
        # opaque, like a photo: dithered, so gradients don't band
        rgb = s.convert("RGB")
        rgb.quantize(palette=rgb.quantize(256, method=Image.Quantize.MEDIANCUT), dither=Image.Dither.FLOYDSTEINBERG).save(out, optimize=True)
    else:
        s.quantize(256, method=Image.Quantize.FASTOCTREE).save(out, optimize=True)
    if os.path.getsize(out) <= 300 * 1024 or scale < 0.5:
        break
    scale *= 0.85
PY
}

# A WebP at quality 80, or lower until it's within the store's 100 KB: busy pictures (halftones, photos) don't fit at 80.
webp() {
  local src=$1 out=$2 q
  shift 2
  for q in 80 70 60 50 40; do
    cwebp -quiet -q "$q" "$@" "$src" -o "$out"
    [ "$(wc -c < "$out")" -le 102400 ] && return 0
  done
  echo "  $(basename "$out") is over 100 KB even at quality 40" >&2
}

# Runs store-previews-demo.sh in the guest (through the debug build's exec intent) and waits for it.
demo() {
  printf 'bash ~/.config/omalauncher/widgets/.demo.sh %s; touch ~/.config/omalauncher/widgets/.demo.done\n' "$1" > "$WORK/run.sh"
  put "$HERE/store-previews-demo.sh" .demo.sh
  put "$WORK/run.sh" .demo-run.sh
  adb shell "rm -f $APP/.demo.done"
  adb shell am start -n dev.omalauncher/.MainActivity --es exec "'bash .config/omalauncher/widgets/.demo-run.sh'" >/dev/null
  wait_for 60 "test -f $APP/.demo.done" || echo "demo $1 didn't finish" >&2
  adb shell "rm -f $APP/.demo.sh $APP/.demo-run.sh $APP/.demo.done"
}
# Saved `persist` state for widgets whose demo data hangs off settings the user makes, written
# where the launcher keeps it (filesDir/widget-state) before the widget loads.
seed() {
  local json
  case $1 in
    google-calendar) json='{"cals":[{"name":"Personal","url":"https://calendar.google.com/calendar/ical/demo-personal/basic.ics","color":0,"on":true},{"name":"Work","url":"https://calendar.google.com/calendar/ical/demo-work/basic.ics","color":2,"on":true}],"days":14}' ;;
    gestures) json='{"apps":["com.android.chrome","com.google.android.youtube","com.google.android.apps.maps","com.android.camera2","com.google.android.apps.messaging","com.google.android.dialer","com.google.android.apps.photos","com.google.android.calendar"],"labels":["Chrome","YouTube","Maps","Camera","Messages","Phone","Photos","Calendar"],"migrated":true}' ;;
    bookmarks) json='{"nextId":4,"links":[{"id":"b3","title":"Omarchy","url":"https://omarchy.org","tag":"linux","auto":true,"desc":"Beautiful, modern and opinionated Linux.","img":""},{"id":"b2","title":"Hacker News","url":"https://news.ycombinator.com","tag":"news","auto":true,"desc":"","img":""},{"id":"b1","title":"Arch Wiki","url":"https://wiki.archlinux.org","tag":"linux","auto":true,"desc":"","img":""},{"id":"b0","title":"Sourdough recipe","url":"https://www.theperfectloaf.com","tag":"cooking","auto":false,"desc":"","img":""}]}' ;;
    # Due in a few minutes and later today, and one already done.
    reminders)
      local now; now=$(date +%s)
      json=$(printf '{"nextId":5,"delay":1800,"items":[{"id":4,"t":"Take the laundry out","at":%s,"fired":false},{"id":3,"t":"Call the dentist","at":%s,"fired":false},{"id":2,"t":"Water the plants","at":%s,"fired":false},{"id":1,"t":"Pay rent","at":%s,"fired":true}]}' $((now + 1500)) $((now + 7800)) $((now + 30600)) $((now - 3600))) ;;
    # NS's API needs a key, read from the environment so it's never in this file: without one,
    # ns keeps its "add your key" preview.
    ns)
      [ -n "${NS_API_KEY:-}" ] || return 0
      json=$(printf '{"apiKey":"%s","station":{"uic":"8400058","name":"Amsterdam Centraal","lat":52.3791,"lng":4.9003},"saved":[],"favs":[{"uic":"8400621","name":"Utrecht Centraal","label":"Utrecht"},{"uic":"8400530","name":"Rotterdam Centraal","label":"Rotterdam"}]}' "$NS_API_KEY") ;;
    life) json='{"birthYear":"1990","lifespan":"80"}' ;;
    # A town in France, so it doesn't fall back to the IP's location.
    carburant) json='{"city":"Lyon"}' ;;
    # A made-up key: the demo's python3 answers lastfm's helper without calling Last.fm.
    lastfm) json='{"username":"demo","apiKey":"demo"}' ;;
    # A public landmark's postcode (the Empire State Building), so the passes show.
    iss) json='{"country":"US","zip":"10118"}' ;;
    # A few days into practice: a streak, a level and two sessions done.
    yoga) json=$(printf '{"done":[1,3],"streak":4,"lastDay":"%s","xp":250,"pace":1,"voice":true}' "$(date +%F)") ;;
    # Mail kept from earlier notifications, the newest a few minutes old, one with an attachment.
    outlook)
      local now; now=$(date +%s)
      json=$(printf '{"kept":[{"key":"demo-6","sender":"Jonas Weber","subject":"Re: Q4 planning deck","body":"Looks good to me. I left two comments on the budget slide, can we go over them tomorrow morning?","time":%s,"clip":false},{"key":"demo-5","sender":"Lena Fischer","subject":"Flat viewing on Saturday","body":"The landlord confirmed 11:00. I will bring the documents, see you at the door.","time":%s,"clip":false},{"key":"demo-4","sender":"Marco Rossi","subject":"Invoice October","body":"Hi, please find the invoice for October attached. Payment terms as usual.","time":%s,"clip":true},{"key":"demo-3","sender":"IT Service Desk","subject":"Your password expires in 5 days","body":"Change it any time from the self-service portal to keep access to your mailbox.","time":%s,"clip":false},{"key":"demo-2","sender":"Sarah Klein","subject":"Lunch on Friday?","body":"There is a new ramen place near the office, want to try it?","time":%s,"clip":false},{"key":"demo-1","sender":"Berlin Tech Meetup","subject":"November meetup: talks and pizza","body":"Three short talks on Rust, home automation and open data. Doors open at 18:30.","time":%s,"clip":false}]}' $((now - 240)) $((now - 1620)) $((now - 6300)) $((now - 11000)) $((now - 19800)) $((now - 93600))) ;;
    # Two public dark-sky sites, so the arrows show; the charts themselves are live.
    clear-sky) json='{"sites":["KittAZ","BrycCNPUT"],"sel":0}' ;;
    # A house, a tree and a cloud in chalk, with the sun, two flowers and a bee stamped on.
    tafel) json='{"marks":[{"c":5,"p":[[30,890],[180,875],[330,895],[480,878],[630,894],[780,876],[970,890]]},{"c":0,"p":[[120,885],[120,560],[400,560],[400,885]]},{"c":4,"p":[[90,585],[260,330],[430,585]]},{"c":4,"p":[[340,455],[340,375],[380,375],[380,515]]},{"c":0,"p":[[360,340],[338,300],[372,262],[348,222],[382,186]]},{"c":1,"p":[[230,885],[230,720],[300,720],[300,885]]},{"c":3,"p":[[150,620],[205,620],[205,680],[150,680],[150,620]]},{"c":3,"p":[[320,620],[375,620],[375,680],[320,680],[320,620]]},{"c":4,"p":[[640,885],[640,645]]},{"c":5,"p":[[640,650],[575,640],[540,580],[560,505],[615,465],[680,470],[730,520],[735,590],[700,640],[640,650]]},{"c":0,"p":[[470,230],[500,180],[560,175],[590,130],[660,140],[690,190],[730,210],[710,262],[490,265],[470,230]]},{"c":0,"p":[[780,330],[805,305],[830,330],[855,305],[880,330]]}],"stamps":[{"c":1,"k":0,"x":880,"y":150},{"c":2,"k":8,"x":820,"y":790},{"c":1,"k":8,"x":930,"y":800},{"c":1,"k":9,"x":850,"y":560}],"chalk":1}' ;;
    # android.apps keys (the launcher activity's short component name), all on a stock emulator.
    folder) json='{"title":"daily","picks":["com.android.chrome/com.google.android.apps.chrome.Main","com.google.android.gm/.ConversationListActivityGmail","com.google.android.apps.maps/com.google.android.maps.MapsActivity","com.google.android.apps.photos/.home.HomeActivity","com.google.android.calendar/com.android.calendar.AllInOneActivity","com.android.camera2/com.android.camera.CameraLauncher","com.google.android.deskclock/com.android.deskclock.DeskClock","com.google.android.apps.messaging/.ui.ConversationListActivity"]}' ;;
    # A Berlin station picked; the demo script makes it look like it's playing.
    radio) json='{"cc":"DE","ccName":"Germany","station":{"id":"9f899cb2-8dd7-11e8-aa66-52543be04c81","name":"Radioeins","url":"http://d131.rndfnk.com/ard/rbb/radioeins/live/mp3/128/stream.mp3","tags":"","kbps":128,"cc":"DE"}}' ;;
    # A photo already set; the demo script downloads it.
    photo) json='{"activePhoto":"~/.cache/omalauncher-photos/downloads/photo_demo.jpg"}' ;;
    # A week in, with daily goals: today part way to all three, with its taps there to undo.
    vice-tracker)
      json=$(python3 -c '
import datetime, json, sys
today = datetime.date.fromisoformat(sys.argv[1].strip())
def day(n, c, w, b):
    d = today - datetime.timedelta(n)
    return {"date": d.isoformat(), "dn": d.toordinal() - 719163, "cigarette": c, "whiskey": w, "beer": b}
days = [day(6, 5, 10, 2), day(5, 4, 5, 1), day(4, 5, 0, 3), day(3, 2, 5, 0), day(2, 7, 15, 2), day(1, 4, 5, 1), day(0, 3, 5, 1)]
taps = [("cigarette", 1), ("cigarette", 1), ("whiskey", 5), ("cigarette", 1), ("beer", 1)]
print(json.dumps({"days": days, "entries": [{"date": today.isoformat(), "v": v, "a": a} for v, a in taps],
                  "goals": {"cigarette": 5, "whiskey": 10, "beer": 2}, "pour": 5}))' "$(adb shell date +%F)") ;;
    # The shell profile renamed and set to greet with fastfetch (the demo script's), plus two more:
    # activeId always starts at the default profile's id, so the seed edits the "shell" one.
    terminal-profiles) json='{"profiles":[{"id":"shell","name":"fastfetch","command":"fastfetch; exec bash -l"},{"id":"p1","name":"btop","command":"btop"},{"id":"p2","name":"python","command":"python3"}],"defaultId":"shell","seq":3}' ;;
    # Two Private Space apps on the tile; the demo's adb lists them.
    private-space) json='{"shortcuts":["com.whatsapp","com.instagram.android"]}' ;;
    # A made-up tailnet host; the demo's ssh answers its collector command.
    server-pulse) json='{"host":"homelab.tailnet.ts.net","lanHost":"","user":"serverpulse","port":"22","displayName":"homelab","language":"en","trafficLimit":"150","motionEnabled":true}' ;;
    # A made-up nostr account (a hex key) that the demo's websocat answers for; the last day is new.
    nostr-inbox)
      local now; now=$(date +%s)
      json=$(printf '{"who":"009ce97e22b376c406740f9b842f780ca2e92c2093a5f11d12f43f6150d8e6a1","seen":%s,"alerts":true,"notifiedAt":%s}' $((now - 86400)) "$now") ;;
    # A stop in Berlin's Mitte with trams and buses every few minutes, any line, four minutes away.
    next-bus) json='{"stopId":"de-VBB_de:11000:900100503::2","stopName":"Berlin, Invalidenpark","route":"","dir":"","walk":4}' ;;
    # A game under way: three spades, two hearts and a diamond up, runs on most columns.
    solitaire) json='{"stock":[7,4,51,23,11,49,34],"waste":[32,22,8,31],"found":[3,2,1,0],"tab":[[25,50,36,9],[45,46],[18,33,37,10],[21,48,12,19,5],[16,39,3,27,35],[17,28,40,41,38,47,20,6],[24,42,44,15,29,43,30]],"down":[0,1,2,3,4,5,6],"hist":[],"moves":23,"wins":2,"counted":false,"draw3":false}' ;;
    # Three space agencies' public accounts; the posts are live from FxTwitter.
    xfeed) json='{"users":["NASA","esa","Space_Station"]}' ;;
    # A city under the NWS's radars: the emulator's own location is outside them.
    nws-radar) json='{"city":"Oklahoma City"}' ;;
    # A preset city: its default ("auto") would show the host's own location from its IP.
    islamic) json='{"city":"Makkah","method":"4"}' ;;
    # Most of a day measured, with a few lost pings, so the day's figures show.
    network) json=$(printf '{"todayKey":"%s","todaySamples":5140,"todayFails":6,"todaySum":107800}' "$(adb shell date +%F | tr -d '\r')") ;;
    # A public profile with a long streak, so Duo shows a streak instead of asking for a username.
    duolingo) json='{"user":"luis"}' ;;
    *) return 0 ;;
  esac
  printf '{"v":1,"savedAt":%s,"values":%s}' "$(date +%s)" "$json" > "$WORK/state.json"
  adb push "$WORK/state.json" /data/local/tmp/store-preview-state.json >/dev/null
  adb shell "cp /data/local/tmp/store-preview-state.json $STATE/$2.json && chown $owner $STATE/$2.json && chmod 600 $STATE/$2.json && chcon $(adb shell ls -Zd $STATE | cut -d' ' -f1) $STATE/$2.json"
}

# Draws a face's page [n] from $1 into $2/screens/<n>/<theme>.webp, as its own face under the name $3.
draw_page() {
  local page=$1 dir=$2 pname=$3 n hash
  n=$(basename "$page" .owl)
  n=${n#page-}
  hash=$(shasum -a 256 "$page" | cut -d' ' -f1)
  adb shell "rm -rf $APP/.preview/themes"
  put "$page" "$pname"
  if ! wait_for 30 "grep -q $hash $APP/.check/$pname.txt"; then
    echo "  page $n didn't compile in time; skipped" >&2
    adb shell "rm -f $APP/$pname"
    return 1
  fi
  adb push "$WORK/empty.txt" /data/local/tmp/store-preview-screens.txt >/dev/null
  adb shell "cp /data/local/tmp/store-preview-screens.txt $APP/.check/$pname.themes"
  if ! wait_for 120 "test -f $APP/.preview/themes/$pname.done"; then
    echo "  page $n: no previews in time; skipped" >&2
    adb shell "rm -f $APP/$pname"
    return 1
  fi
  mkdir -p "$dir/screens/$n"
  for theme in $(adb shell "ls $APP/.preview/themes" | grep -v '\.done$'); do
    adb exec-out cat "$APP/.preview/themes/$theme/$pname.png" > "$WORK/png/$theme.$n.png"
    webp "$WORK/png/$theme.$n.png" "$dir/screens/$n/$theme.webp" -resize 540 0
  done
  adb shell "rm -f $APP/$pname; rm -rf $APP/.preview/themes"
}

demo on
trap 'demo off; rm -rf "$WORK"' EXIT

dirs=()
if [ $# -gt 0 ]; then
  # A name, or author/name when two authors share it.
  for n in "$@"; do
    case $n in
      */*) dirs+=("$(dirname "$(ls "$STORE"/widgets/"$n"/widget.owl "$STORE"/faces/"$n"/face.owl "$STORE"/apps/"$n"/app.owl 2>/dev/null | head -1)")") ;;
      *) dirs+=("$(dirname "$(ls "$STORE"/widgets/*/"$n"/widget.owl "$STORE"/faces/*/"$n"/face.owl "$STORE"/apps/*/"$n"/app.owl 2>/dev/null | head -1)")") ;;
    esac
  done
else
  for f in "$STORE"/widgets/*/*/widget.owl "$STORE"/faces/*/*/face.owl "$STORE"/apps/*/*/app.owl; do [ -f "$f" ] && dirs+=("$(dirname "$f")"); done
fi

for dir in "${dirs[@]}"; do
  name="store-$(basename "$dir").owl"
  # A phone-sized app view or face goes in at half size, to stay under the store's 100 KB a theme.
  if [ -f "$dir/app.owl" ]; then owl=$dir/app.owl; resize=(-resize 540 0)
  elif [ -f "$dir/face.owl" ]; then owl=$dir/face.owl; resize=(-resize 540 0)
  else owl=$dir/widget.owl; resize=(); fi
  hash=$(shasum -a 256 "$owl" | cut -d' ' -f1)
  echo "$(basename "$dir")"
  adb shell "rm -rf $APP/.preview/themes/$name.done"
  seed "$(basename "$dir")" "$name"
  put "$owl" "$name"
  if ! wait_for 30 "grep -q $hash $APP/.check/$name.txt"; then
    echo "  didn't compile in time; skipped" >&2
    continue
  fi
  # The screens to draw, if any; each takes a few seconds per theme.
  if [ -f "$dir/screens.txt" ]; then cp "$dir/screens.txt" "$WORK/screens.txt"; else : > "$WORK/screens.txt"; fi
  screens=$(awk '{ sub(/#.*/, "") } /[^[:space:]]/ { n++ } END { print n + 0 }' "$WORK/screens.txt")
  adb push "$WORK/screens.txt" /data/local/tmp/store-preview-screens.txt >/dev/null
  # The launcher reads it once it's written and closed, then deletes it.
  adb shell "cp /data/local/tmp/store-preview-screens.txt $APP/.check/$name.themes"
  waits=$(awk '{ sub(/#.*/, "") } match($0, /wait *= *[0-9]+/) { w = substr($0, RSTART, RLENGTH); sub(/[^0-9]+/, "", w); s += w } END { print s + 0 }' "$WORK/screens.txt")
  if ! wait_for $((120 + 60 * screens + waits)) "test -f $APP/.preview/themes/$name.done"; then
    echo "  no previews in time; skipped" >&2
    adb shell "rm -f $APP/$name"
    continue
  fi
  rm -rf "$WORK/png" "$dir/screens" && mkdir -p "$WORK/png" "$dir/previews"
  for theme in $(adb shell "ls $APP/.preview/themes" | grep -v '\.done$'); do
    adb exec-out cat "$APP/.preview/themes/$theme/$name.png" > "$WORK/png/$theme.png"
    webp "$WORK/png/$theme.png" "$dir/previews/$theme.webp" ${resize[@]+"${resize[@]}"}
    # Not seq: on macOS, seq 2 1 counts down.
    for ((n = 2; n <= screens; n++)); do
      mkdir -p "$dir/screens/$n"
      adb exec-out cat "$APP/.preview/themes/$theme/$name.$n.png" > "$WORK/png/$theme.$n.png"
      webp "$WORK/png/$theme.$n.png" "$dir/screens/$n/$theme.webp" ${resize[@]+"${resize[@]}"}
    done
  done
  small_png "$WORK/png/$DEFAULT_THEME.png" "$dir/preview.png"
  # A face's other pages, one at a time: all in this run, since it cleared screens/ above.
  adb shell "rm -f $APP/$name"
  : > "$WORK/empty.txt"
  pages=1
  for page in "$dir"/page-*.owl; do
    [ -f "$page" ] || continue
    draw_page "$page" "$dir" "store-$(basename "$dir").$(basename "$page")" && pages=$((pages + 1))
  done
  extra=""
  [ "$screens" -gt 1 ] && extra=", $screens screens, $(du -sh "$dir/screens" | cut -f1) more"
  [ "$pages" -gt 1 ] && extra=", $pages pages, $(du -sh "$dir/screens" | cut -f1) more"
  echo "  $(ls "$dir/previews" | wc -l | tr -d ' ') themes, $(du -sh "$dir/previews" | cut -f1)$extra"
  adb shell "rm -f $APP/$name; rm -rf $APP/.preview/themes"
done
