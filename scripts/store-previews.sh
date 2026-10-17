#!/usr/bin/env bash
# Renders every widget and app in the widget store in every bundled Omarchy theme, for the
# store's per-theme previews: widgets/<author>/<name>/previews/<theme>.webp (apps/... for an
# app, whose picture is its view at the phone's size), plus preview.png in the default theme
# for launchers that don't know about themes.
#
# An app with a screens.txt beside it is drawn on several screens: one line per screen, the
# states to set first (`view = "watch"; openId = ""`), each starting from the screen before;
# `wait = 60` there pauses first, for an API that allows only so many calls a minute.
# The first is its preview as above; the rest go to apps/<author>/<name>/screens/<n>/<theme>.webp.
#
# Needs a debug build on an emulator (adb root) with Linux installed, so widgets
# that run commands show real data, and the packages they require (curl, jq,
# python, sqlite, github-cli). Widgets that need an account show demo data instead; see
# store-previews-demo.sh. The launcher renders each tile off screen
# (WidgetEngine.previewThemes) and takes every theme's colors while it does.
#
#   scripts/store-previews.sh [store repo, default ../omarchy-widgets] [widget or app name, or author/name...]
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
    # A made-up key: the demo's python3 answers lastfm's helper without calling Last.fm.
    lastfm) json='{"username":"demo","apiKey":"demo"}' ;;
    # A few days into practice: a streak, a level and two sessions done.
    yoga) json=$(printf '{"done":[1,3],"streak":4,"lastDay":"%s","xp":250,"pace":1,"voice":true}' "$(date +%F)") ;;
    *) return 0 ;;
  esac
  printf '{"v":1,"savedAt":%s,"values":%s}' "$(date +%s)" "$json" > "$WORK/state.json"
  adb push "$WORK/state.json" /data/local/tmp/store-preview-state.json >/dev/null
  adb shell "cp /data/local/tmp/store-preview-state.json $STATE/$2.json && chown $owner $STATE/$2.json && chmod 600 $STATE/$2.json && chcon $(adb shell ls -Zd $STATE | cut -d' ' -f1) $STATE/$2.json"
}

demo on
trap 'demo off; rm -rf "$WORK"' EXIT

dirs=()
if [ $# -gt 0 ]; then
  # A name, or author/name when two authors share it.
  for n in "$@"; do
    case $n in
      */*) dirs+=("$(dirname "$(ls "$STORE"/widgets/"$n"/widget.owl "$STORE"/apps/"$n"/app.owl 2>/dev/null | head -1)")") ;;
      *) dirs+=("$(dirname "$(ls "$STORE"/widgets/*/"$n"/widget.owl "$STORE"/apps/*/"$n"/app.owl 2>/dev/null | head -1)")") ;;
    esac
  done
else
  for f in "$STORE"/widgets/*/*/widget.owl "$STORE"/apps/*/*/app.owl; do [ -f "$f" ] && dirs+=("$(dirname "$f")"); done
fi

for dir in "${dirs[@]}"; do
  name="store-$(basename "$dir").owl"
  # A phone-sized app view goes in at half size, to stay under the store's 100 KB a theme.
  if [ -f "$dir/app.owl" ]; then owl=$dir/app.owl; resize=(-resize 540 0); else owl=$dir/widget.owl; resize=(); fi
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
    cwebp -quiet -q 80 ${resize[@]+"${resize[@]}"} "$WORK/png/$theme.png" -o "$dir/previews/$theme.webp"
    # Not seq: on macOS, seq 2 1 counts down.
    for ((n = 2; n <= screens; n++)); do
      mkdir -p "$dir/screens/$n"
      adb exec-out cat "$APP/.preview/themes/$theme/$name.$n.png" > "$WORK/png/$theme.$n.png"
      cwebp -quiet -q 80 ${resize[@]+"${resize[@]}"} "$WORK/png/$theme.$n.png" -o "$dir/screens/$n/$theme.webp"
    done
  done
  small_png "$WORK/png/$DEFAULT_THEME.png" "$dir/preview.png"
  extra=""
  [ "$screens" -gt 1 ] && extra=", $screens screens, $(du -sh "$dir/screens" | cut -f1) more"
  echo "  $(ls "$dir/previews" | wc -l | tr -d ' ') themes, $(du -sh "$dir/previews" | cut -f1)$extra"
  adb shell "rm -f $APP/$name; rm -rf $APP/.preview/themes"
done
