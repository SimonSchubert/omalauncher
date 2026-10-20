#!/usr/bin/env bash
# Captures the Play listing's screens (store/screenshots/raw/, framed by store/screenshots/frame.html and
# scripts/render-screenshots.sh): home screens and a face in several themes with widgets from the widget store,
# and a few screens beyond home.
#
# The display is shrunk to 1080x1670 for the run, the room a slide leaves below its caption, so the launcher lays
# itself out for that and nothing is cropped or squashed. System UI demo mode keeps the clock and icons tidy.
#
# Needs a debug build (run-as, the `theme` and `exec` extras) with Linux and the coding agents in RECENT installed,
# on an emulator with Google Play so the Play Store is there to show: the Omalauncher_Play AVD, which needs no root.
# The launcher's widgets, home layout, pinned apps, recent apps and theme are backed up first and put back
# afterwards, as are the display size and demo mode.
#
#   scripts/store-captures.sh [store repo, default ../omarchy-widgets] [shot...]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
STORE="${1:-$HERE/../../omarchy-widgets}"
shift || true
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5562}"
OUT="$HERE/../store/screenshots/raw"
PKG=dev.omalauncher
DATA=/data/data/$PKG
BACKUP=cache/store-backup.tar
SIZE=1080x1670

# Shot, theme, then the widgets on its home screen in placement order (the launcher puts each in the first free
# spot) as `<name>` for simonschubert's or `<author>/<name>`, `face:<name>` for a face or `app:<name>` for an app. A shot with a `shot_<name>` function below runs that
# after they're placed, to open the screen it shows. Seven rows fit: two rows of 3-high tiles above the recent apps.
SCENES=(
  "home        tokyo-night       fetch today koi-pond"
  "face        kanagawa          face:terminal"
  "light       catppuccin-latte  boogercheeseonrye/dog-of-the-day travonted/apod weebowiz/trip-view rcrohmana/classic-clock"
  "live        osaka-jade        ssalentin/red-panda hieitheevileye/fatcat dimasmds/hootling asphyksia/companion"
  "store       gruvbox           today"
  "app         everforest        app:braincup"
  "linux       matte-black       today"
  "style       ristretto         today"
)

# The recent apps strip, first to last.
RECENT=(
  l:agent-claude l:foot l:agent-grok a:com.android.vending/.AssetBrowserActivity l:agent-codex l:agent-opencode
  l:agent-muse l:agent-kimi l:agent-omp
)
# The face's apps (faces list Android apps by use): ones the Play emulator comes with.
FACE_APPS=(
  a:com.android.chrome/com.google.android.apps.chrome.Main
  a:com.google.android.gm/.ConversationListActivityGmail
  a:com.google.android.apps.maps/com.google.android.maps.MapsActivity
  a:com.google.android.apps.photos/.home.HomeActivity
  a:com.google.android.calendar/com.android.calendar.AllInOneActivity
  a:com.google.android.apps.youtube.music/.activities.MusicActivity
  a:com.google.android.deskclock/com.android.deskclock.DeskClock
  a:com.android.vending/.AssetBrowserActivity
)

adb wait-for-device

# Runs a shell command as the launcher, in its data dir. Files from the Mac come in through /data/local/tmp.
app() { adb shell "run-as $PKG sh -c 'cd $DATA && $*'"; }

adb shell am force-stop $PKG
app "tar cf $BACKUP files/widgets shared_prefs"
restore() {
  adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null
  adb shell wm size reset
  adb shell settings delete secure stylus_handwriting_enabled >/dev/null
  if [ "${clock:-null}" = null ]; then adb shell settings delete system time_12_24 >/dev/null; else adb shell settings put system time_12_24 "$clock"; fi
  adb shell am force-stop $PKG
  app "rm -rf files/widgets/*.owl files/widgets/.store-ff.sh shared_prefs/app_usage.xml && tar xf $BACKUP && rm $BACKUP"
  adb shell am start -n $PKG/.MainActivity >/dev/null
  echo "restored the launcher's widgets and theme"
}
trap restore EXIT

adb shell wm size $SIZE
adb shell settings put global sysui_demo_allowed 1
# Otherwise Gboard can open its stylus tutorial over the drawer's keyboard.
adb shell settings put secure stylus_handwriting_enabled 0
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
# The real time on a 24-hour clock, so the status bar agrees with clock widgets and faces.
clock=$(adb shell settings get system time_12_24 | tr -d '\r')
adb shell settings put system time_12_24 24
demo clock -e hhmm "$(date +%H%M)"
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e fully true
demo network -e mobile hide
demo notifications -e visible false

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

# Puts store file $1 (relative to the store repo) into the widgets dir as $2.
install() {
  adb push "$STORE/$1" /data/local/tmp/store.owl >/dev/null
  app "cp /data/local/tmp/store.owl files/widgets/.$2 && mv files/widgets/.$2 files/widgets/$2"
}

# App use as the keys given: each a second apart, the first the latest.
usage() {
  local xml now i keys=("$@")
  xml=$(mktemp)
  now=$(($(date +%s) * 1000))
  {
    echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
    echo "<map>"
    for i in "${!keys[@]}"; do echo "    <string name=\"${keys[$i]}\">1.0,$((now - i * 1000))</string>"; done
    echo "</map>"
  } >"$xml"
  adb push "$xml" /data/local/tmp/store-usage.xml >/dev/null
  rm "$xml"
  app "cp /data/local/tmp/store-usage.xml shared_prefs/app_usage.xml"
}

# Taps the centre of the first node whose text or description is $1, from a uiautomator dump.
tap_text() {
  local xml bounds
  xml=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null)
  bounds=$(grep -o "<node[^>]*\(text\|content-desc\)=\"$1\"[^>]*>" <<<"$xml" | head -1 | grep -o 'bounds="[^"]*"' || true)
  [ -n "$bounds" ] || { echo "no \"$1\" on screen" >&2; return 1; }
  read -r x1 y1 x2 y2 <<<"$(tr -c '0-9' ' ' <<<"$bounds")"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

w=${SIZE%x*}
h=${SIZE#*x}
swipe_right() { adb shell input swipe $((w / 5)) $((h / 2)) $((w * 4 / 5)) $((h / 2)) 300; }

long_press_home() { adb shell input swipe $((w * 3 / 4)) $((h / 2)) $((w * 3 / 4)) $((h / 2)) 900; sleep 2; }
open_drawer() { tap_text "open an app[^\"]*"; sleep 2; }

# The terminal face tails the notifications; a few ordinary ones, posted by the shell.
shot_face() {
  adb shell cmd notification post -t Delivery store-1 "'Your package was delivered'" >/dev/null
  adb shell cmd notification post -t CI store-2 "'Build passed on main'" >/dev/null
  adb shell cmd notification post -t Mia store-3 "'See you at 7?'" >/dev/null
  swipe_right
  sleep 4
}
# The store's games: colourful previews (fun has sip, which Simon doesn't want shown).
shot_store() {
  long_press_home
  tap_text "browse store"
  sleep 4
  adb shell input swipe $((w * 5 / 6)) $((h * 26 / 100)) $((w / 10)) $((h * 26 / 100)) 400
  sleep 1
  tap_text "games [0-9]*"
  sleep 10
}
shot_app() {
  open_drawer
  tap_text braincup
  sleep 6
}
# A terminal with fastfetch and Omarchy's logo, without the host and hardware lines that give the emulator away.
shot_linux() {
  local ff
  ff=$(mktemp)
  printf '%s\n' clear 'fastfetch -c none --logo-type file --logo ~/.config/omarchy/branding/about.txt --logo-color-1 green --logo-position top -s os:uptime:packages:shell:terminal:memory:break:colors' 'exec bash' >"$ff"
  adb push "$ff" /data/local/tmp/store-ff.sh >/dev/null
  rm "$ff"
  app "cp /data/local/tmp/store-ff.sh files/widgets/.store-ff.sh"
  adb shell am start -n $PKG/.MainActivity --es exec "'foot bash /root/.config/omalauncher/widgets/.store-ff.sh'" >/dev/null
  sleep 10
}
shot_style() {
  long_press_home
  tap_text settings
  sleep 2
  tap_text style
  sleep 4
}

mkdir -p "$OUT"
n=0
for scene in "${SCENES[@]}"; do
  n=$((n + 1))
  read -r shot theme widgets <<<"$scene"
  if [ $# -gt 0 ] && [[ " $* " != *" $shot "* ]]; then continue; fi
  adb shell am force-stop $PKG
  # A bare home: no widgets, no layout, no pinned apps, and RECENT for recent apps.
  app "rm -f files/widgets/*.owl shared_prefs/widget_grid.xml shared_prefs/shortcuts.xml"
  if [ "$shot" = face ]; then usage "${FACE_APPS[@]}"; else usage "${RECENT[@]}"; fi
  adb shell am start -n $PKG/.MainActivity --es theme "$theme" >/dev/null
  sleep 3
  # One at a time, each once the one before has its spot: the launcher places tiles as they load, so adding them
  # all at once mixes up the order.
  for item in $widgets; do
    if [[ $item == app:* ]]; then
      a=${item#app:}
      install "apps/simonschubert/$a/app.owl" "market-app-simonschubert-$a.owl"
      continue
    elif [[ $item == face:* ]]; then
      # A face keeps no cell; it takes the empty page left of page 1.
      f=${item#face:}
      install "faces/simonschubert/$f/face.owl" "$f.owl"
      continue
    else
      [[ $item == */* ]] || item=simonschubert/$item
      install "widgets/$item/widget.owl" "${item#*/}.owl"
    fi
    wait_for 20 "run-as $PKG grep -q ${item#*/}.owl $DATA/shared_prefs/widget_grid.xml" || echo "$shot: $item got no spot" >&2
  done
  # Theme, wallpaper and the widgets' first data.
  sleep "${WAIT:-12}"
  # Away from home and back, so the recent apps strip starts at its first app: it keeps the first icon it showed
  # in view while the rest load in, and Android's apps load before Linux's.
  adb shell am start -a android.settings.SETTINGS >/dev/null
  sleep 2
  adb shell input keyevent KEYCODE_HOME
  sleep 2
  if declare -F "shot_$shot" >/dev/null; then "shot_$shot"; fi
  sleep 2
  adb exec-out screencap -p >"$OUT/$(printf %d $n)-$shot.png"
  echo "$OUT/$n-$shot.png"
done
