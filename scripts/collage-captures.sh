#!/usr/bin/env bash
# Captures the home screen in several Omarchy themes, each with its own widgets from the
# widget store, for the README collage (store/collage/collage.html, scripts/render-collage.sh).
#
# Needs a debug build on an emulator (adb root, the `theme` extra) with Linux installed, as
# for scripts/store-previews.sh. The launcher's widgets, home layout, pinned apps and theme
# are backed up first and put back afterwards.
#
#   scripts/collage-captures.sh [store repo, default ../omarchy-widgets] [theme...]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
STORE="${1:-$HERE/../../omarchy-widgets}"
shift || true
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
OUT="$HERE/../store/collage/raw"
PKG=dev.omalauncher
DATA=/data/data/$PKG
BACKUP=/data/local/tmp/collage-backup.tar

# Theme, then the widgets on its home screen in placement order: the launcher puts each in the
# first free spot, six columns and ten rows on the Pixel 9a profile.
SCENES=(
  "tokyo-night       analog-clock today forecast hacker-news"
  "kanagawa          today fetch forecast koi-pond"
  "gruvbox           fetch crypto earthquakes releases"
  "everforest        planes-overhead today launches lobsters"
  "retro-82          free-games github-trending air-and-pollen departures"
  "osaka-jade        koi-pond focus countdown wikipedia"
  "matte-black       fetch year-progress releases github-trending"
  "ristretto         today crypto exchange-rates hacker-news"
  "hackerman         fetch crypto earthquakes uptime"
  "ethereal          sun-and-moon year-progress free-games launches"
  "miasma            year-progress focus forecast releases"
  "lumon             analog-clock today world-clock habits exchange-rates"
)

adb root >/dev/null
adb wait-for-device
owner=$(adb shell stat -c %u:%g $DATA/files/widgets)
label=$(adb shell ls -Zd $DATA | cut -d' ' -f1)

adb shell am force-stop $PKG
adb shell "cd $DATA && tar cf $BACKUP files/widgets shared_prefs"
restore() {
  adb shell am force-stop $PKG
  adb shell "cd $DATA && rm -rf files/widgets/*.owl && tar xf $BACKUP && chown -R $owner files/widgets shared_prefs && chcon -R $label files/widgets shared_prefs && rm $BACKUP"
  adb shell am start -n $PKG/.MainActivity >/dev/null
  echo "restored the launcher's widgets and theme"
}
trap restore EXIT

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

# Whether the launcher is the activity in front.
front() {
  adb shell dumpsys activity activities | grep -q "topResumedActivity=.* $PKG/"
}

mkdir -p "$OUT"
for scene in "${SCENES[@]}"; do
  read -r theme widgets <<<"$scene"
  if [ $# -gt 0 ] && [[ " $* " != *" $theme "* ]]; then continue; fi
  adb shell am force-stop $PKG
  # A bare home: no widgets, no layout, no pinned apps.
  adb shell "rm -f $DATA/files/widgets/*.owl $DATA/shared_prefs/widget_grid.xml $DATA/shared_prefs/shortcuts.xml"
  adb shell am start -n $PKG/.MainActivity --es theme "$theme" >/dev/null
  sleep 3
  # One at a time, each once the one before has its spot: the launcher places tiles as they
  # load, so adding them all at once mixes up the order.
  for w in $widgets; do
    adb push "$STORE/widgets/simonschubert/$w/widget.owl" /data/local/tmp/collage.owl >/dev/null
    adb shell "cp /data/local/tmp/collage.owl $DATA/files/widgets/.$w.owl && chown $owner $DATA/files/widgets/.$w.owl && chmod 600 $DATA/files/widgets/.$w.owl && chcon $label $DATA/files/widgets/.$w.owl && mv $DATA/files/widgets/.$w.owl $DATA/files/widgets/$w.owl"
    wait_for 20 "grep -q '$w.owl' $DATA/shared_prefs/widget_grid.xml" || echo "$theme: $w got no spot" >&2
  done
  # Theme, wallpaper and the widgets' first data.
  sleep "${WAIT:-12}"
  # Home again in case another app came up meanwhile, and only keep a shot with the launcher in front throughout.
  for try in $(seq 5); do
    adb shell input keyevent KEYCODE_HOME
    sleep 2
    front || continue
    adb exec-out screencap -p >"$OUT/$theme.png"
    front && break
  done
  front || echo "$theme: another app kept coming to the front" >&2
  echo "$OUT/$theme.png"
done
